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
    // 单步推进类工具：一次只走一步，进度（executed/done）在返回值里。它们的参数每步都一样，
    // 所以不能按「同一工具+同一参数重复」判卡住，只能按「连续几步没发出任何动作」判。
    private static final Set<String> STEP_TOOLS = Set.of("daily_task", "market_scan");
    private static final int STEP_IDLE_LIMIT = 3;

    private static final String SYSTEM_PROMPT = """
            你是「问道」桌面自动化的对话调试助手，运行在 host 层，通过 MCP 客户端调用 mcpserver 的工具。

            每一轮只输出一个 JSON 对象（不要代码围栏、不要任何额外文字）：
            {"thought":"一句话推理","tool":"工具名或空字符串","args":{参数},"reply":"给用户的回答","done":false}

            工具清单随消息给出，每个工具的使用场景、触发说法、是否真实操作、返回字段都写在它的
            description 里；选工具前先读 description，按它说的判断该不该用、怎么用。

            规则：
            1) 看还是做，由你按用户这句话的真实意图判断：
               - 询问/诊断（游戏开了吗 / 屏幕上有什么 / 现在什么界面）：用只读工具查清后，
                 在 reply 里用中文自然语言按实际返回回答（不要把 JSON 或字段名贴给用户），
                 随即 done=true 收尾，不要自己再找下一步动作；
               - 明确要求动手（登录 / 打开游戏 / 帮我做师门任务 / 点某处）：才调用操作类工具。
            2) 参数按工具描述里写的名字和含义传；缺信息就先调别的工具查清楚。
            3) 工具返回 ok=false 时，把 error 原样转述给用户，绝不假装成功；
               同一个工具 + 同一参数失败两次就停下汇报，不要换着法子重试。
            4) tool 与 reply 二选一：要调工具就把 reply 留空；要回答就把 tool 留空并 done=true。
               工具结果已经能回答用户时（例如问「游戏开了吗」拿到 game_status 后），下一轮必须收尾，
               不要重复调用同一个工具。
            5) 单步推进类工具（daily_task / market_scan）一次只走一步，返回值里有
               scene / reason / chosen / executed / done：
               - done=true：目标已达成，用 reply 汇报结果并收尾；
               - executed>0：接着调同一个工具推下一步（goal 照旧即可，参数重复不算卡住）；
               - executed=0：先读 note。可点为空 / 窗口不在前台 / 被人工接管时不要连着重试，
                 先查清状态或直接告诉用户；连续两三次仍无动作就停下汇报。
               其余操作类工具不是单步，仍按第 3 条：同一工具同一参数失败两次就停。
            6) 只使用清单里真实存在的工具名，不要发明工具。
            7) 窗口不可见/被最小化时，如实告诉用户“需要手动切到前台”，不要尝试用按键去切窗口。
            8) 目标只来自用户原话：用户没提的事不要替他决定，不要自行补出「师门任务」「领奖励」。
            """;

    private final GameService game;
    private final AiSettings settings;
    private final LlmClient llm;
    private final DecisionLog decisions;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 工具清单渲染结果（首次使用时从 MCP listTools 生成后缓存）。 */
    private volatile String toolCatalog;

    public AiChatService(GameService game, AiSettings settings, LlmClient llm, DecisionLog decisions) {
        this.game = game;
        this.settings = settings;
        this.llm = llm;
        this.decisions = decisions;
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
        // 本轮所有模型判断先攒在 turn 里，结束时（含异常）一次性落到 ai-decisions.log
        TurnLog turn = new TurnLog(id, text, withVision, settings.model(), rounds);
        synchronized (session) {
            Map<String, Object> out = null;
            try {
                out = converse(session, text, withVision, rounds, turn);
                return out;
            } catch (RuntimeException e) {
                turn.note("异常：" + e);
                throw e;
            } finally {
                decisions.append(turn.render(out));
            }
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
                                         boolean withVision, int maxRounds, TurnLog turn) {
        session.stripImages();
        session.append(userMessage(userText, withVision));
        // 是「只查」还是「动手」由模型按用户意图自行判断，host 不再按关键词硬拦
        List<Map<String, Object>> steps = new ArrayList<>();
        // 同一「工具+参数」的尝试次数：模型有时会绕着同一件事反复撞墙，这里做硬限制
        Map<String, Integer> attempts = new LinkedHashMap<>();
        // 单步类工具的「空转」计数：按返回值判，而不是按参数重复次数判
        int stepIdle = 0;
        String catalog = catalog();

        for (int round = 1; round <= maxRounds; round++) {
            List<Map<String, Object>> payload = new ArrayList<>();
            payload.add(Map.of("role", "system",
                    "content", SYSTEM_PROMPT + "\n【可用工具（MCP listTools 实时清单，JSON）】\n" + catalog));
            payload.addAll(session.snapshot());

            String raw;
            long llmStart = System.currentTimeMillis();
            try {
                raw = llm.chat(payload);
                turn.useModel(llm.modelUsed());
            } catch (Exception e) {
                log.warn("AI 调用失败: {}", e.toString());
                turn.round(round, System.currentTimeMillis() - llmStart, null, null)
                        .put("note", "模型调用失败：" + e.getMessage());
                return result(false, "AI 服务调用失败：" + e.getMessage(), steps, round);
            }
            long llmMs = System.currentTimeMillis() - llmStart;

            String answer = raw == null ? "" : raw.trim();
            session.append(Map.of("role", "assistant", "content", answer));

            Map<String, Object> plan = extractJson(answer);
            Map<String, Object> record = turn.round(round, llmMs, answer, plan);
            if (plan == null) {
                // 模型没按协议输出 JSON：把原文当最终回答，不猜它想调什么
                record.put("note", "未按协议输出 JSON，原文直接作为回答");
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
            String rendered = LlmClient.truncate(toJson(output), MAX_TOOL_RESULT_CHARS);
            // 工具自己报 ok=false 也算失败（服务层只抛异常时才置 failed）
            boolean bad = failed || (output instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("ok")));

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("tool", tool);
            step.put("args", args);
            step.put("ms", ms);
            step.put("error", bad);
            step.put("result", rendered);
            steps.add(step);
            record.put("tool_ms", ms);
            record.put("tool_ok", !bad);
            record.put("tool_result", rendered);

            session.append(Map.of("role", "user", "content",
                    "【工具结果】" + tool + " -> " + rendered
                            + "\n（继续则输出下一个 JSON 指令；已完成则 tool 留空并给出 reply）"));
            session.trim(MAX_HISTORY_MESSAGES);

            // 收尾类工具 ok=true 就说明目标已达成，直接用确定性文案收尾，不再追加一轮模型复核
            if (!bad && terminalNow(tool, userText)) {
                record.put("terminal", true);
                turn.note(tool + " 已成功，按工具结果直接收尾（跳过模型复核）");
                return result(true, terminalReply(tool, output), steps, round);
            }

            if (STEP_TOOLS.contains(tool)) {
                // 单步推进：参数每步都一样，不能按「同参数重复」判卡住；看返回值里有没有真的动了
                int executed = executedCount(output);
                if (bad || executed == 0) {
                    stepIdle++;
                    turn.note(tool + " 本步无动作（" + (bad ? "调用失败" : "executed=0") + "）");
                    if (stepIdle >= STEP_IDLE_LIMIT) {
                        return result(false, "连续 " + stepIdle + " 步没有产生任何动作，疑似卡住先停在这里："
                                + rendered, steps, round);
                    }
                } else {
                    stepIdle = 0;
                }
            } else {
                int tried = attempts.merge(tool + " " + toJson(args), 1, Integer::sum);
                if (tried >= 2 && bad) {
                    return result(false, "同一操作已失败 " + tried + " 次，先停在这里：" + rendered, steps, round);
                }
                if (tried >= 3) {
                    // 模型已经给出结论，只是漏了 done：直接用它的 reply 收尾，别把回答埋进错误里
                    String reply = text(plan.get("reply"));
                    if (!reply.isBlank()) {
                        turn.note("同一操作重复 " + tried + " 次且模型已给出结论，按其 reply 收尾");
                        return result(true, reply, steps, round);
                    }
                    return result(false, "同一操作重复 " + tried + " 次，疑似卡住，先停在这里：" + rendered,
                            steps, round);
                }
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

    /**
     * 这次工具成功是否足以结束本轮：用户同一句话里还要求了后续动作时（如「打开游戏并登录」
     * 「登录后做师门任务」）不能提前收尾，否则后半句会被丢掉。
     */
    private static boolean terminalNow(String tool, String userText) {
        // 终止工具与「是否还有后续动作」的判定合在一处：以前另有一份 TERMINAL_TOOLS 集合
        // 要跟着这个 switch 同步改，漏一处就会出现点完不收尾或提前收尾
        return switch (tool) {
            case "open_game" -> !containsAny(userText, "登录", "进入游戏", "师门", "任务", "日常", "扫货");
            case "login" -> !containsAny(userText, "师门", "任务", "日常", "扫货", "领取");
            case "back_to_main" -> !containsAny(userText, "师门", "任务", "日常", "扫货", "领取", "登录");
            case "close_game" -> !containsAny(userText, "登录", "打开", "启动");
            default -> false;
        };
    }

    /** 单步类工具本步实际发出了几个动作（读返回值里的 executed，缺失当 0）。 */
    private static int executedCount(Object output) {
        if (output instanceof Map<?, ?> m && m.get("executed") instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private static boolean containsAny(String text, String... hints) {
        for (String hint : hints) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 收尾类工具成功后的确定性回复：直接读工具返回的关键字段，不依赖模型再总结。 */
    private static String terminalReply(String tool, Object output) {
        Map<?, ?> m = output instanceof Map<?, ?> map ? map : Map.of();
        switch (tool) {
            case "close_game" -> {
                if (Boolean.TRUE.equals(m.get("already_closed"))) {
                    return "游戏本来就没在运行，无需关闭。";
                }
                boolean forced = Boolean.TRUE.equals(m.get("forced"));
                return "游戏已关闭" + (forced ? "（未响应正常退出，已强制结束进程 " : "（已正常退出，进程 ")
                        + text(m.get("closed")) + "）。";
            }
            case "open_game" -> {
                if (Boolean.TRUE.equals(m.get("already_running"))) {
                    return "游戏已经在运行，窗口：" + text(m.get("window"));
                }
                return "游戏已启动，窗口：" + text(m.get("window"));
            }
            case "login" -> {
                String stage = text(m.get("stage"));
                if ("main_scene".equals(stage)) {
                    return Boolean.TRUE.equals(m.get("already_in_game"))
                            ? "本来就已在游戏主场景，没有重复登录。"
                            : "已登录并进入游戏主场景。";
                }
                return "登录流程结束（阶段：" + stage + "）。";
            }
            case "back_to_main" -> {
                if (Boolean.TRUE.equals(m.get("already_in_main"))) {
                    return "本来就在游戏主界面，没有做任何操作。";
                }
                String closed = text(m.get("closed"));
                return "已回到游戏主界面"
                        + (closed.isEmpty() || closed.equals("[]") ? "。" : "，关掉了 " + closed + "。");
            }
            default -> {
                return tool + " 已执行：" + toJson(output);
            }
        }
    }

    /** 用户消息：文字 +（可选）当前游戏截图；默认不发图，让模型用 layout 拿识别结果。 */
    private Map<String, Object> userMessage(String text, boolean withVision) {
        List<Map<String, Object>> content = new ArrayList<>();
        // 每轮附一句轻提醒：是否动手由模型按用户意图自行判断，host 不再按关键词拦截
        content.add(Map.of("type", "text", "text", text
                + "\n（提醒：按这句话的真实意图选工具——只是询问就用只读工具，"
                + "明确要求动手才执行操作；拿不准就先只读，再在 reply 里问清楚。）"
                + (withVision ? "" : "\n（本轮没有附截图。需要看画面时调 layout 工具："
                   + "它返回 YOLO + OCR 融合后的区域清单，含类型、文字、方位与坐标。）")));
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
            // 模型选工具主要看 name + description；inputSchema 会把清单揍大一倍多，
            // 小模型（glm-4v-flash）在长 JSON 里反而抓不住描述，这里只透这两项。
            List<Map<String, Object>> brief = new ArrayList<>();
            for (Map<String, Object> spec : game.toolSpecs()) {
                brief.add(Map.of(
                        "name", String.valueOf(spec.get("name")),
                        "description", String.valueOf(spec.get("description"))));
            }
            cached = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(brief);
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

    private static String normalizeSession(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    // ---------- 判断日志 ----------

    /**
     * 一轮用户输入里所有模型判断的暂存块，最后整块交给 {@link DecisionLog} 落盘。
     *
     * <p>每次模型返回都产生一条 {@code #n} 记录（原始输出 + 解析出的 tool/args/done/reply），
     * 工具执行后再补上耗时与结果，日志能完整还原「模型怎么想 -> 调了什么 -> 得到什么」。
     */
    static final class TurnLog {

        private final String session;
        private final String user;
        private final boolean vision;
        /** 实际调用的模型：限流降级后会变成备用模型，每轮更新。 */
        private String model;
        private final int maxRounds;
        private final long startedAt = System.currentTimeMillis();
        private final List<Map<String, Object>> rounds = new ArrayList<>();
        private String note = "";

        TurnLog(String session, String user, boolean vision, String model, int maxRounds) {
            this.session = session;
            this.user = user;
            this.vision = vision;
            this.model = model;
            this.maxRounds = maxRounds;
        }

        /** 记录本轮实际用的模型（限流降级时日志能看到真相）。 */
        void useModel(String name) {
            if (name != null && !name.isBlank()) {
                this.model = name;
            }
        }

        /** 登记一轮模型调用，返回可继续补充工具结果的记录。 */
        Map<String, Object> round(int index, long llmMs, String raw, Map<String, Object> plan) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("round", index);
            record.put("llm_ms", llmMs);
            if (plan != null) {
                record.put("thought", plan.get("thought"));
                record.put("tool", plan.get("tool"));
                record.put("args", plan.get("args"));
                record.put("done", Boolean.TRUE.equals(plan.get("done")));
                record.put("reply", plan.get("reply"));
            }
            record.put("raw", raw == null ? "" : raw);
            rounds.add(record);
            return record;
        }

        void note(String text) {
            this.note = text == null ? "" : text;
        }

        /** 渲染成一块可读日志（out 为 null 表示这轮没走到结论）。 */
        String render(Map<String, Object> out) {
            StringBuilder sb = new StringBuilder(1024);
            sb.append(DecisionLog.stamp(startedAt))
                    .append("  source=host  session=").append(oneLine(session))
                    .append("  model=").append(oneLine(model))
                    .append("  vision=").append(vision)
                    .append("  最多 ").append(maxRounds).append(" 轮\n");
            sb.append("用户：").append(oneLine(user)).append('\n');
            for (Map<String, Object> record : rounds) {
                renderRound(sb, record);
            }
            if (out == null) {
                sb.append("结果：未拿到最终答复");
                if (!note.isBlank()) {
                    sb.append("（").append(oneLine(note)).append("）");
                }
                sb.append('\n');
                return sb.toString();
            }
            sb.append("结果 ok=").append(out.get("ok"))
                    .append("  rounds=").append(out.get("rounds"))
                    .append("  用时 ").append(System.currentTimeMillis() - startedAt).append("ms\n");
            sb.append("  回复：").append(oneLine(out.get("reply"))).append('\n');
            if (!note.isBlank()) {
                sb.append("  备注：").append(oneLine(note)).append('\n');
            }
            return sb.toString();
        }

        private static void renderRound(StringBuilder sb, Map<String, Object> record) {
            sb.append('#').append(record.get("round"))
                    .append("  模型 ").append(record.get("llm_ms")).append("ms\n");
            if (record.containsKey("tool")) {
                sb.append("  判断 tool=").append(oneLine(record.get("tool")))
                        .append("  args=").append(oneLine(json(record.get("args"))))
                        .append("  done=").append(record.get("done")).append('\n');
                String thought = oneLine(record.get("thought"));
                if (!thought.isBlank()) {
                    sb.append("       thought=").append(thought).append('\n');
                }
                String reply = oneLine(record.get("reply"));
                if (!reply.isBlank()) {
                    sb.append("       reply=").append(reply).append('\n');
                }
            }
            String raw = oneLine(record.get("raw"));
            if (!raw.isBlank()) {
                sb.append("  原始 ").append(raw).append('\n');
            }
            if (record.containsKey("tool_ms")) {
                sb.append("  工具 ").append(record.get("tool_ms")).append("ms  ok=")
                        .append(record.get("tool_ok")).append('\n');
                sb.append("       结果 ").append(oneLine(record.get("tool_result"))).append('\n');
            }
            if (record.containsKey("note")) {
                sb.append("  备注 ").append(oneLine(record.get("note"))).append('\n');
            }
        }

        /** 日志一行一事：把换行压平并截断长文本。 */
        private static String oneLine(Object value) {
            String s = text(value).replace("\r", "").replace("\n", " / ");
            return s.length() <= 1200 ? s : s.substring(0, 1200) + "…(已截断)";
        }

        private static String json(Object value) {
            if (value == null) {
                return "{}";
            }
            try {
                return JSON.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
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
            // 调用方多用 Map.of 传参（不可变），而 stripImages 要改写历史里的 content，
            // 所以入历史前统一转成可变副本，避免 UnsupportedOperationException。
            messages.add(new LinkedHashMap<>(message));
        }

        void trim(int max) {
            while (messages.size() > max) {
                messages.remove(0);
            }
        }

        /** 历史截图统一降级成文字，只保留最新一轮的图。 */
        void stripImages() {
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> message = messages.get(i);
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
                    // 换一个可变副本回填，兼容历史里可能存在的不可变 Map
                    Map<String, Object> copy = new LinkedHashMap<>(message);
                    copy.put("content", replaced);
                    messages.set(i, copy);
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
