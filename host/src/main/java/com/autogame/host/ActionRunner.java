package com.autogame.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一次性命令行入口：
 * {@code java -jar host.jar --action=ping|windows|status|login|close|daily|market|chat}。
 *
 * <p>可选参数：{@code --goal=...}、{@code --max-steps=120}、{@code --dry-run=true}、
 * {@code --timeout=120}（login/close 的连接超时，close 默认 8）、
 * {@code --auto-open=false}（仅 login 使用）、{@code --force=true}（仅 close：跳过优雅退出直接强杀）、
 * {@code --message=...} 与 {@code --vision=true|false}（仅 chat 使用）。
 * 执行完把结果按 JSON 打到 stdout，并以 0/1 退出，方便脚本与 CI 判断。
 */
@Component
public class ActionRunner implements ApplicationRunner {

    private final GameService game;
    private final AiChatService ai;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper mapper = new ObjectMapper();

    public ActionRunner(GameService game, AiChatService ai, ConfigurableApplicationContext context) {
        this.game = game;
        this.ai = ai;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("action")) {
            return;
        }
        List<String> values = args.getOptionValues("action");
        String action = (values == null || values.isEmpty()) ? "" : values.get(0);
        String goal = option(args, "goal", "");
        int maxSteps = Integer.parseInt(option(args, "max-steps", "120"));
        boolean dryRun = Boolean.parseBoolean(option(args, "dry-run", "false"));
        double timeout = Double.parseDouble(option(args, "timeout",
                "close".equals(action) ? "8" : "120"));
        boolean autoOpen = Boolean.parseBoolean(option(args, "auto-open", "true"));
        boolean force = Boolean.parseBoolean(option(args, "force", "false"));
        String message = option(args, "message", "");
        // 不传 --vision 时交给 AiSettings 的 chat_vision 默认值
        Boolean vision = args.containsOption("vision")
                ? Boolean.valueOf(option(args, "vision", "true")) : null;

        int code = 0;
        try {
            Object result = dispatch(action, goal, message, vision, maxSteps, dryRun, timeout, autoOpen, force);
            System.out.println(mapper.writeValueAsString(result));
        } catch (Exception e) {
            code = 1;
            System.err.println("[失败] " + action + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
        int exitCode = code;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private Object dispatch(String action, String goal, String message, Boolean vision,
                            int maxSteps, boolean dryRun, double timeout, boolean autoOpen,
                            boolean force) {
        return switch (action) {
            case "ping" -> game.ping();
            case "windows" -> game.windows();
            case "status" -> game.status();
            case "login" -> game.login(timeout, autoOpen);
            // 关游戏：默认走正常退出，超时未退自动升级强杀
            case "close" -> game.close(timeout, force);
            case "daily" -> game.daily(goal, maxSteps, dryRun);
            case "market" -> game.market(goal, maxSteps, dryRun);
            // 单轮对话：AI 决策 + 工具编排都在 host 内完成
            case "chat" -> ai.chat("cli", message, vision, maxSteps);
            default -> throw new IllegalArgumentException(
                    "未知 action: '" + action + "'（可选 ping|windows|status|login|close|daily|market|chat）");
        };
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? fallback : values.get(0);
    }
}
