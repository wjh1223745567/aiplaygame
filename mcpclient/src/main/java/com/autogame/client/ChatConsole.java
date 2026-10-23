package com.autogame.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话式调试台：用自然语言驱动 host 的 AI 服务，实时看到 AI 选了哪些 MCP 工具、参数与返回值。
 *
 * <p>职责边界：本类<b>不做</b>任何决策，只负责「发一句话 -> 打印 AI 的工具调用明细与回答」；
 * 大模型调用与会话上下文都在 host（{@code /ai/chat}）里，工具执行则在 mcpserver。
 *
 * <pre>
 * bash scripts/run-host.sh serve        # 先起 host（AI 服务，18080）
 * bash scripts/run-chat.sh              # 再进对话调试台
 * </pre>
 *
 * 除自然语言外还支持 {@code :} 开头的本地命令（:help 查看）。
 */
public class ChatConsole {

    private static final String HELP = """
            可用命令（都以 : 开头，其余输入都会转成对话发给 host）：
              :help              显示本帮助
              :tools             列出 mcpserver 当前暴露的工具（MCP listTools）
              :history           打印本轮会话的原始 messages（排查 AI 为何这么选）
              :reset             清空当前会话上下文
              :vision            查看/切换截图开关（:vision on | :vision off）
              :session <id>      切换会话 id（不同 id 上下文互相隔离）
              :q                 退出
            """;

    private final String base;
    private final HostClient host;
    private final BufferedReader in;

    private String session;
    /** null 表示不指定，交给 host 的 llm.chat_vision 默认值。 */
    private Boolean vision;

    public ChatConsole(String base, String session, Boolean vision) {
        this.host = new HostClient(base);
        this.base = host.base();
        this.session = session;
        this.vision = vision;
        this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    public static void main(String[] args) {
        Map<String, String> opts = parseOptions(args);
        if (opts.containsKey("help")) {
            System.out.println("用法: java -jar chat.jar [--base=http://localhost:18080] [--session=debug]"
                    + " [--vision=true|false] [--message=一句话]");
            return;
        }
        String base = opts.getOrDefault("base", "http://localhost:18080");
        String session = opts.getOrDefault("session", "debug");
        Boolean vision = opts.containsKey("vision") ? Boolean.valueOf(opts.get("vision")) : null;

        ChatConsole console = new ChatConsole(base, session, vision);
        String message = opts.get("message");
        // --message 一次性模式，方便脚本冒烟测试
        int code = message == null ? console.run() : console.once(message);
        System.exit(code);
    }

    // ---------- 主循环 ----------

    private int run() {
        if (!healthCheck()) {
            return 1;
        }
        banner();
        while (true) {
            System.out.print("你> ");
            System.out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                System.out.println("\n[读取输入失败] " + e.getMessage());
                return 1;
            }
            if (line == null) {
                System.out.println();
                return 0;
            }
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith(":")) {
                if (!command(text)) {
                    return 0;
                }
                continue;
            }
            ask(text);
        }
    }

    private int once(String message) {
        if (!healthCheck()) {
            return 1;
        }
        boolean ok = ask(message);
        return ok ? 0 : 1;
    }

    /** 启动前先确认 host 在跑，并顺带展示工具数量。 */
    private boolean healthCheck() {
        try {
            JsonNode tools = host.tools();
            System.out.println("[chat] host: " + base + "，工具 " + tools.path("count").asInt(0) + " 个，会话: " + session);
            return true;
        } catch (Exception e) {
            System.err.println("[chat] 连不上 host（" + base + "）：" + HostClient.rootMessage(e));
            System.err.println("[chat] 先启动 AI 服务：bash scripts/run-host.sh serve");
            return false;
        }
    }

    private void banner() {
        System.out.println("[chat]");
        System.out.println("[chat] 截图: " + visionText() + "    输入 :help 看命令，:q 退出");
        System.out.println("[chat] 例：游戏开了吗 / 现在停在什么界面 / 帮我看看有没有红点提示");
        System.out.println();
    }

    /** 发一轮对话，打印每一步工具调用与最终回答。返回是否成功。 */
    private boolean ask(String message) {
        System.out.println();
        long started = System.currentTimeMillis();
        JsonNode out;
        try {
            out = host.chat(session, message, vision);
        } catch (Exception e) {
            System.err.println("[失败] " + HostClient.rootMessage(e));
            return false;
        }
        long ms = System.currentTimeMillis() - started;

        JsonNode steps = out.path("steps");
        int index = 0;
        for (JsonNode step : steps) {
            index++;
            String args = step.path("args").toString();
            System.out.println("  ↳ [" + index + "] " + step.path("tool").asText()
                    + " " + args + "  " + step.path("ms").asLong() + "ms"
                    + (step.path("error").asBoolean() ? " [出错]" : ""));
            printBlock(step.path("result").asText(""), "       ");
        }

        String reply = out.path("reply").asText("");
        if (out.path("ok").asBoolean(false)) {
            System.out.println("AI> " + reply.replace("\n", "\n    "));
        } else {
            System.out.println("AI(未完成)> " + reply.replace("\n", "\n    "));
        }
        System.out.println("    （" + index + " 次工具调用 / " + out.path("rounds").asInt(0)
                + " 轮模型请求 / " + ms + "ms）");
        System.out.println();
        return out.path("ok").asBoolean(false);
    }

    // ---------- 本地命令 ----------

    /** 处理 : 命令；返回 false 表示要退出。 */
    private boolean command(String text) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case ":q", ":quit", ":exit" -> {
                    return false;
                }
                case ":help", ":h", ":?" -> System.out.println(HELP);
                case ":tools" -> printTools(host.tools());
                case ":history" -> printHistory(host.history(session));
                case ":reset" -> {
                    JsonNode out = host.reset(session);
                    System.out.println("[chat] 已清空会话 " + out.path("session").asText(session));
                }
                case ":session" -> switchSession(arg);
                case ":vision" -> switchVision(arg);
                default -> System.out.println("[chat] 未知命令 " + cmd + "，输入 :help 查看");
            }
        } catch (Exception e) {
            System.err.println("[失败] " + HostClient.rootMessage(e));
        }
        return true;
    }

    private void switchSession(String arg) {
        if (arg.isEmpty()) {
            System.out.println("[chat] 当前会话: " + session);
            return;
        }
        session = arg;
        System.out.println("[chat] 已切换会话: " + session + "（历史独立）");
    }

    private void switchVision(String arg) {
        if (arg.isEmpty()) {
            System.out.println("[chat] 截图当前为: " + visionText());
            return;
        }
        if ("on".equalsIgnoreCase(arg) || "true".equalsIgnoreCase(arg)) {
            vision = Boolean.TRUE;
        } else if ("off".equalsIgnoreCase(arg) || "false".equalsIgnoreCase(arg)) {
            vision = Boolean.FALSE;
        } else {
            System.out.println("[chat] 用法: :vision on | :vision off");
            return;
        }
        System.out.println("[chat] 截图已切换为: " + visionText() + "（下一轮生效）");
    }

    private String visionText() {
        if (vision == null) {
            return "跟随 host 配置（llm.chat_vision）";
        }
        return vision ? "开（每轮附当前画面）" : "关（只发文字）";
    }

    // ---------- 打印 ----------

    private static void printTools(JsonNode out) {
        System.out.println("[tools] 共 " + out.path("count").asInt(0) + " 个：");
        for (JsonNode tool : out.path("tools")) {
            String name = tool.path("name").asText();
            String desc = tool.path("description").asText("");
            if (desc.length() > 80) {
                desc = desc.substring(0, 80) + "…";
            }
            System.out.println("  - " + name + "  " + desc);
            JsonNode required = tool.path("required");
            if (required.isArray() && !required.isEmpty()) {
                System.out.println("      必填: " + required);
            }
        }
    }

    private static void printHistory(JsonNode out) {
        JsonNode messages = out.path("messages");
        System.out.println("[history] 会话 " + out.path("session").asText() + "，"
                + messages.size() + " 条消息：");
        int i = 0;
        for (JsonNode message : messages) {
            i++;
            String content = message.path("content").isTextual()
                    ? message.path("content").asText()
                    : message.path("content").toString();
            System.out.println("  " + i + ". [" + message.path("role").asText() + "] " + HostClient.oneLine(content, 300));
        }
    }

    private static void printBlock(String text, String indent) {
        if (text == null || text.isEmpty()) {
            return;
        }
        System.out.println(indent + HostClient.oneLine(text, 900));
    }

    // ---------- 小工具 ----------

    /** 支持 --key=value 与 --key value 两种写法。 */
    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> list = new ArrayList<>(List.of(args));
        for (int i = 0; i < list.size(); i++) {
            String item = list.get(i);
            if (!item.startsWith("--")) {
                continue;
            }
            String body = item.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                out.put(body.substring(0, eq), body.substring(eq + 1));
            } else if (i + 1 < list.size() && !list.get(i + 1).startsWith("--")) {
                out.put(body, list.get(i + 1));
                i++;
            } else {
                out.put(body, "true");
            }
        }
        return out;
    }
}
