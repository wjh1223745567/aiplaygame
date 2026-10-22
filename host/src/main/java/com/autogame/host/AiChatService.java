package com.autogame.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话式 AI 服务（host 层）：自然语言 -> 模型决策 -> 调用 MCP 工具 -> 复述结果。
 *
 * <p>模型不是原生 function-calling，而是每轮只输出一个 JSON 指令：
 * <pre>
 * {"thought":"…","tool":"ocr","args":{},"reply":"","done":false}
 * {"thought":"…","tool":"","args":{},"reply":"结论","done":true}
 * </pre>
 * 这样对任意 OpenAI 兼容网关（含 GLM-4V-Flash）都成立，且工具清单由
 * {@link GameService#toolSpecs()} 实时生成，Python 侧加工具无需改 Java。
 *
 * <p>会话按 sessionId 隔离：每个会话保存 messages 历史；带截图的老消息在下一轮
 * 会被替换成文字占位，避免 base64 把上下文撑爆。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_TOOL_RESULT_CHARS = 1600;
    private static final int MAX_HISTORY_MESSAGES = 40;

    /** 只读工具：调用它们不会改变游戏状态。 */
    private static final Set<String> READ_ONLY_TOOLS =
            Set.of("ping", "list_windows", "game_status", "capture", "ocr", "detect");

    /** 用户话里出现这些词，才算「明确要求执行操作」，否则本轮按只读处理。 */
    private static final List<String> ACTION_HINTS = List.of(
            "打开", "启动", "登录", "点击", "点一下", "点开", "按一下", "按下", "执行", "操作",
            "帮我一", "帮我", "帮忙", "领一", "做一", "扫货", "日常", "任务", "跑一下",
            "进入", "关掉", "关闭", "切到前台", "弄到前台", "激活", "试一下", "试试");

    private static final String SYSTEM_PROMPT = """
            你是「问道」桌面自动化的对话调试助手，运行在 host 层，通过 MCP 客户端调用 mcpserver 的工具。

            每一轮只输出一个 JSON 对象（不要代码围栏、不要任何额外文字）：
            {"thought":"一句话推理","tool":"工具名或空字符串","args":{参数},"reply":"给用户的回答","done":false}

            规则：
            1) 只看不点：询问/诊断类问题（游戏开了吗 / 屏幕上有什么 / 现在什么界面）只用只读工具
               （ping、list_windows、game_status、capture、ocr），在 reply 里按工具实际返回说明，
               不要凭想象回答，也不要自作主张去按键、点屏。
            2) 谨慎操作：click_point、click_text、press_key、advise、login、daily_task、market_scan
               是真实操作，只有用户明确要求执行时才调用；open_game 也仅在用户明确说“打开游戏”时用。
               用户只是询问时，给结论和建议即可。
            3) 参数必须符合工具清单里的 JSON Schema；缺信息就先调别的工具查清楚。
            4) 工具返回 ok=false 时，把 error 原样转述给用户，绝不假装成功；
               同一个工具 + 同一参数失败两次就停下汇报，不要换着法子重试。
            5) 不需要再调工具、或目标已达成时：tool 留空、done=true，用 reply 给结论。
            6) 用户给的是游戏目标（例如“领一下每日”）时，按「先看屏 -> 再决策 -> 执行 -> 复检」推进，
               最多几步就先汇报进展，不要无限点。
            7) 只使用清单里真实存在的工具名，不要发明工具。
            8) 窗口不可见/被最小化时，如实告诉用户“需要手动切到前台”，不要尝试用按键去切窗口。
            """;

    private final GameService game;
    private final AiSettings settings;
    private final LlmClient llm;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 工具清单渲染结果（首次使用时从 MCP listTools 生成后缓存）。 */
    private volatile String toolCatalog;

    public AiChatService(GameService game, AiSettings settings, LlmClient llm) {
        this.game = game;
        this.settings = settings;
        this.llm = llm;
    }

    // ---------- 对外能力 ----------

    /** 一轮对话：可能内含多次工具调用，直到模型给出结论或用尽轮数。 */
    public Map<String, Object> chat(String sessionId, String message, Boolean vision, int maxSteps) {
        String id = normalizeSession(sessionId);
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return Map.of("ok", false, "error", "message 不能为空");
        }
        if (!settings.ready()) {
            return Map.of("ok", false,
                    "error", "未配置 llm.api_key：请填 host/config.json 的 llm.api_key，或设环境变量 LLM_API_KEY");
        }
        boolean withVision = vision != null ? vision : settings.visionByDefault();
        int rounds = maxSteps > 0 ? maxSteps : settings.maxRounds();
        Session session = sessions.computeIfAbsent(id, Session::new);
        synchronized (session) {
            return converse(session, text, withVision, rounds);
        }
    }

    /** 工具清单（调试用，也是给模型看的同一份）。 */
    public Map<String, Object> tools() {
        List<Map<String, Object>> specs = game.toolSpecs();
        return Map.of("ok", true, "count", specs.size(), "tools", specs);
    }

    /** 会话历史（原始 messages，便于排查模型为什么这么选）。 */
    public Map<String, Object> history(String sessionId) {
        String id = normalizeSession(sessionId);
        Session session = sessions.get(id);
        return Map.of("ok", true, "session", id, "messages",
                session == null ? List.of() : session.snapshot());
    }

    /** 清空某个会话的上下文。 */
    public Map<String, Object> reset(String sessionId) {
        String id = normalizeSession(sessionId);
        sessions.remove(id);
        return Map.of("ok", true, "session", id, "reset", true);
    }

    // ---------- 对话主体 ----------

    private Map<String, Object> converse(Session session, String userText,
                                         boolean withVision, int maxRounds) {
        session.stripImages();
        session.append(userMessage(userText, withVision));
        // 小模型对「只读」约束经常阳奉阴违，所以这里做确定性拦截：
        // 用户这句话没有操作类措辞时，本轮只允许只读工具，第一次越界就收尾。
        boolean readOnlyTurn = !looksLikeAction(userText);
        List<Map<String, Object>> steps = new ArrayList<>();
        // 同一「工具+参数」的尝试次数：模型有时会绕着同一件事反复撞墙，这里做硬限制
        Map<String, Integer> attempts = new LinkedHashMap<>();
        String catalog = catalog();

        for (int round = 1; round <= maxRounds; round++) {
            List<Map<String, Object>> payload = new ArrayList<>();
            payload.add(Map.of("role", "system",
                    "content", SYSTEM_PROMPT + "\n【可用工具（MCP listTools 实时清单，JSON）】\n" + catalog));
            payload.addAll(session.snapshot());

            String raw;
            try {
                raw = llm.chat(payload);
            } catch (Exception e) {
                log.warn("AI 调用失败: {}", e.toString());
                return result(false, "AI 服务调用失败：" + e.getMessage(), steps, round);
            }

            String answer = raw == null ? "" : raw.trim();
            session.append(Map.of("role", "assistant", "content", answer));

            Map<String, Object> plan = extractJson(answer);
            if (plan == null) {
                // 模型没按协议输出 JSON：把原文当最终回答，不猜它想调什么
                return result(true, answer, steps, round);
            }

            String tool = text(plan.get("tool"));
            Map<String, Object> args = asArgMap(plan.get("args"));
            boolean done = Boolean.TRUE.equals(plan.get("done"));

            if (tool.isBlank() || done) {
                String reply = text(plan.get("reply"));
                if (reply.isBlank()) {
                    reply = text(plan.get("thought"));
                }
                return result(true, reply.isBlank() ? "（本轮没有内容）" : reply, steps, round);
            }

            if (readOnlyTurn && !READ_ONLY_TOOLS.contains(tool)) {
                String note = "已拒绝 " + tool + "：这句话是在询问，本轮只读，没有执行任何操作";
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("tool", tool);
                step.put("args", args);
                step.put("ms", 0L);
                step.put("error", false);
                step.put("refused", true);
                step.put("result", note);
                steps.add(step);
                return result(true, note + "。如果确实要我动手，请明确说「打开游戏」「登录」"
                        + "「帮我做每日」之类。", steps, round);
            }

            long started = System.currentTimeMillis();
            Object output;
            boolean failed = false;
            try {
                output = game.invoke(tool, args);
            } catch (Exception e) {
                failed = true;
                output = Map.of("ok", false, "error", String.valueOf(e.getMessage()));
            }
            long ms = System.currentTimeMillis() - started;
            String rendered = truncate(toJson(output), MAX_TOOL_RESULT_CHARS);
            // 工具自己报 ok=false 也算失败（服务层只抛异常时才置 failed）
            boolean bad = failed || (output instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("ok")));

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("tool", tool);
            step.put("args", args);
            step.put("ms", ms);
            step.put("error", bad);
            step.put("result", rendered);
            steps.add(step);

            session.append(Map.of("role", "user", "content",
                    "【工具结果】" + tool + " -> " + rendered
                            + "\n（继续则输出下一个 JSON 指令；已完成则 tool 留空并给出 reply）"));
            session.trim(MAX_HISTORY_MESSAGES);

            int tried = attempts.merge(tool + " " + toJson(args), 1, Integer::sum);
            if (tried >= 2 && bad) {
                return result(false, "同一操作已失败 " + tried + " 次，先停在这里：" + rendered, steps, round);
            }
            if (tried >= 3) {
                return result(false, "同一操作重复 " + tried + " 次，疑似卡住，先停在这里：" + rendered,
                        steps, round);
            }
        }
        return result(false, "工具调用轮数已达上限（" + maxRounds + "），请补充指令继续",
                steps, maxRounds);
    }

    private static Map<String, Object> result(boolean ok, String reply,
                                             List<Map<String, Object>> steps, int rounds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("reply", reply);
        out.put("steps", steps);
        out.put("rounds", rounds);
        return out;
    }

    /** 用户消息：文字 +（可选）当前游戏截图，让视觉模型直接看画面。 */
    private Map<String, Object> userMessage(String text, boolean withVision) {
        List<Map<String, Object>> content = new ArrayList<>();
        // 小模型容易忽略 system 里的约束，每轮在用户消息尾部再强调一次只读默认
        content.add(Map.of("type", "text", "text", text
                + "\n（提醒：本轮默认只读。除非这句话明确要求执行操作，否则只允许"
                + " ping、list_windows、game_status、capture、ocr；越界的工具会被 host 直接拒绝。）"));
        if (withVision) {
            String dataUrl = snapshotDataUrl();
            if (dataUrl != null) {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
            } else {
                content.add(Map.of("type", "text", "text", "（当前拿不到游戏截图：窗口可能未打开或权限不足）"));
            }
        }
        return Map.of("role", "user", "content", content);
    }

    /** 调 MCP capture 截图 -> 读 png -> 缩放到 max_image_side 后转 jpeg data url。 */
    private String snapshotDataUrl() {
        try {
            Object raw = game.invoke("capture", Map.of("tag", "chat"));
            if (!(raw instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("ok"))) {
                return null;
            }
            Object pathValue = map.get("path");
            if (pathValue == null) {
                return null;
            }
            Path image = resolveCapturedPath(String.valueOf(pathValue));
            if (image == null) {
                return null;
            }
            return jpegDataUrl(ImageIO.read(image.toFile()), settings.maxImageSide());
        } catch (Exception e) {
            log.debug("取截图失败: {}", e.toString());
            return null;
        }
    }

    /** capture 返回的是相对 mcpserver 工作目录的路径，两种可能都试一下。 */
    private Path resolveCapturedPath(String value) {
        Path given = Path.of(value);
        if (given.isAbsolute()) {
            return Files.exists(given) ? given : null;
        }
        Path fromRoot = settings.projectRoot().resolve(given).normalize();
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        Path fromServer = settings.projectRoot().resolve("mcpserver").resolve(given).normalize();
        return Files.exists(fromServer) ? fromServer : null;
    }

    private static String jpegDataUrl(BufferedImage source, int maxSide) throws IOException {
        if (source == null) {
            return null;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        // jpeg 不支持透明通道，统一铺到 RGB 画布
        BufferedImage rgb = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpg", out)) {
            throw new IOException("JPEG 编码失败");
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** 工具清单：首次从 listTools 生成并缓存。 */
    private String catalog() {
        String cached = toolCatalog;
        if (cached != null) {
            return cached;
        }
        try {
            cached = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(game.toolSpecs());
        } catch (Exception e) {
            return "（工具清单生成失败：" + e.getMessage() + "）";
        }
        toolCatalog = cached;
        return cached;
    }

    // ---------- 解析与工具 ----------

    /** 从模型输出里抠出第一个 JSON 对象（容忍 ```json 围栏与前后废话）。 */
    static Map<String, Object> extractJson(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        int start = s.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        Object value = JSON.readValue(s.substring(start, i + 1), Object.class);
                        if (value instanceof Map<?, ?> map) {
                            Map<String, Object> out = new LinkedHashMap<>();
                            map.forEach((k, v) -> out.put(String.valueOf(k), v));
                            return out;
                        }
                    } catch (Exception ignored) {
                        return null;
                    }
                    return null;
                }
            }
        }
        return null;
    }

    /** 模型给的 args 归一化成 Map（可能缺失/写错类型）。 */
    private static Map<String, Object> asArgMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String text, int max) {
        return LlmClient.truncate(text, max);
    }

    private static String normalizeSession(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    /** 这句话里有没有「让我动手」的措辞；没有就按只读处理。 */
    private static boolean looksLikeAction(String text) {
        for (String hint : ACTION_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 会话 ----------

    /** 单个对话的上下文。messages 采用 OpenAI 格式（system 每轮现拼）。 */
    private static final class Session {

        private final String id;
        private final List<Map<String, Object>> messages = new ArrayList<>();

        Session(String id) {
            this.id = id;
        }

        void append(Map<String, Object> message) {
            messages.add(message);
        }

        void trim(int max) {
            while (messages.size() > max) {
                messages.remove(0);
            }
        }

        /** 历史截图统一降级成文字，只保留最新一轮的图。 */
        void stripImages() {
            for (Map<String, Object> message : messages) {
                Object content = message.get("content");
                if (!(content instanceof List<?> parts)) {
                    continue;
                }
                List<Object> replaced = new ArrayList<>(parts.size());
                boolean changed = false;
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> p && "image_url".equals(String.valueOf(p.get("type")))) {
                        replaced.add(Map.of("type", "text", "text", "[历史截图已省略]"));
                        changed = true;
                    } else {
                        replaced.add(part);
                    }
                }
                if (changed) {
                    message.put("content", replaced);
                }
            }
        }

        List<Map<String, Object>> snapshot() {
            return new ArrayList<>(messages);
        }

        @Override
        public String toString() {
            return "Session[" + id + ", messages=" + messages.size() + "]";
        }
    }
}
