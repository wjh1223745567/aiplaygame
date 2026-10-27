package com.autogame.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用台：直接调 host 的 {@code /ai/invoke}，看每个识别工具返回什么。
 *
 * <p>职责边界：本类<b>不做</b>任何决策，也不解释识别结果——只负责把「工具名 + JSON 参数」
 * 发出去，把 host 原样返回的 JSON 打印出来。工具执行在 mcpserver，转发在 host。
 *
 * <pre>
 * bash scripts/run-host.sh              # 先起 host（18080）
 * bash scripts/run-chat.sh              # 再进调用台
 * bash scripts/run-chat.sh --tool=detect --args='{"max_n":20}'   # 单次调用（冒烟）
 * </pre>
 *
 * 提示符下输入格式为 {@code 工具名 [JSON 参数]}，{@code :} 开头的是本地命令（:help 查看）。
 */
public class ChatConsole {

    private static final String HELP = """
            可用命令（都以 : 开头）：
              :help             显示本帮助
              :tools            列出 mcpserver 当前暴露的只读工具
              :frame [路径]      抓一帧当前画面存成 JPEG（默认系统临时目录）
              :q                退出

            其余输入就是一次工具调用，格式：工具名 [JSON 参数]
              ping
              list_windows
              ocr
              ocr {"region":[0,0,1200,800],"min_confidence":0.6}
              detect {"max_n":20}
            """;

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 返回体可能很长（一屏几十个识别框），打印时给个上限，别把终端刷爆。 */
    private static final int MAX_PRINT_CHARS = 6000;

    private final String base;
    private final HostClient host;
    private final BufferedReader in;

    public ChatConsole(String base) {
        this.host = new HostClient(base);
        this.base = host.base();
        this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    public static void main(String[] args) {
        Map<String, String> opts = parseOptions(args);
        if (opts.containsKey("help")) {
            System.out.println("用法: java -jar chat.jar [--base=http://localhost:18080]"
                    + " [--tool=detect] [--args='{\"max_n\":20}']");
            return;
        }
        String base = opts.getOrDefault("base", "http://localhost:18080");
        ChatConsole console = new ChatConsole(base);
        String tool = opts.get("tool");
        // --tool 一次性模式，方便脚本冒烟测试
        int code = tool == null ? console.run() : console.once(tool, opts.getOrDefault("args", ""));
        System.exit(code);
    }

    // ---------- 主循环 ----------

    private int run() {
        if (!healthCheck()) {
            return 1;
        }
        banner();
        while (true) {
            System.out.print("工具> ");
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
            call(text);
        }
    }

    private int once(String tool, String args) {
        if (!healthCheck()) {
            return 1;
        }
        String line = args.isBlank() ? tool : tool + " " + args;
        return call(line) ? 0 : 1;
    }

    /** 启动前先确认 host 在跑，并顺带展示工具数量。 */
    private boolean healthCheck() {
        try {
            JsonNode tools = host.tools();
            System.out.println("[client] host: " + base + "，工具 " + tools.path("count").asInt(0) + " 个");
            return true;
        } catch (Exception e) {
            System.err.println("[client] 连不上 host（" + base + "）：" + HostClient.rootMessage(e));
            System.err.println("[client] 先启动 host：bash scripts/run-host.sh");
            return false;
        }
    }

    private void banner() {
        System.out.println("[client] 输入 :help 看命令，:q 退出；工具名后可直接跟 JSON 参数");
        System.out.println("[client] 例：ocr / detect {\"max_n\":20} / :frame");
        System.out.println();
    }

    /** 执行一次工具调用并打印结果；返回是否成功。 */
    private boolean call(String line) {
        String[] parts = line.trim().split("\\s+", 2);
        String tool = parts[0];
        String raw = parts.length > 1 ? parts[1].trim() : "";

        Map<String, Object> args;
        try {
            args = parseArgs(raw);
        } catch (Exception e) {
            System.err.println("[参数错误] " + e.getMessage());
            System.err.println("           示例：detect {\"max_n\":20}");
            return false;
        }

        System.out.println();
        System.out.println("→ " + tool + " " + JSON.valueToTree(args));
        long started = System.currentTimeMillis();
        JsonNode out;
        try {
            out = host.invoke(tool, args);
        } catch (Exception e) {
            System.err.println("[失败] " + HostClient.rootMessage(e));
            return false;
        }
        long ms = System.currentTimeMillis() - started;

        boolean ok = out.path("ok").asBoolean(false);
        if (ok) {
            printJson(out.path("result"));
        } else {
            System.err.println("[失败] " + out.path("error").asText(""));
        }
        System.out.println("   （" + ms + "ms）");
        System.out.println();
        return ok;
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
                case ":frame" -> saveFrame(arg);
                default -> System.out.println("[client] 未知命令 " + cmd + "，输入 :help 查看");
            }
        } catch (Exception e) {
            System.err.println("[失败] " + HostClient.rootMessage(e));
        }
        return true;
    }

    /** 抓一帧存成文件：命令行里没法看图，至少给个路径让用户自己打开。 */
    private void saveFrame(String target) throws IOException, InterruptedException {
        byte[] jpeg = host.frame();
        Path path = target.isEmpty()
                ? Path.of(System.getProperty("java.io.tmpdir"), "autogame-frame.jpg")
                : Path.of(target);
        Files.write(path, jpeg);
        System.out.println("[frame] 已保存 " + path.toAbsolutePath()
                + "（" + Math.max(1, jpeg.length / 1024) + "KB）");
    }

    // ---------- 打印 ----------

    private static Map<String, Object> parseArgs(String raw) throws IOException {
        if (raw.isEmpty()) {
            return Map.of();
        }
        if (!raw.startsWith("{")) {
            throw new IllegalArgumentException("参数必须是 JSON 对象，当前是: " + raw);
        }
        return JSON.readValue(raw, JSON.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    private static void printTools(JsonNode out) {
        System.out.println("[tools] 共 " + out.path("count").asInt(0) + " 个（全部只读）：");
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

    /** 结果按缩进 JSON 打印，长内容截断——终端里看的是结构，不是完整数据。 */
    private static void printJson(JsonNode node) {
        String text;
        try {
            text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            text = String.valueOf(node);
        }
        if (text.length() > MAX_PRINT_CHARS) {
            text = text.substring(0, MAX_PRINT_CHARS) + "\n…（已截断，完整结果请用图形客户端或脚本读取）";
        }
        for (String line : text.split("\n", -1)) {
            System.out.println("  " + line);
        }
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
