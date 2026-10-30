package com.autogame.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.support.ToolException;

/**
 * macOS 实现：启动与置顶走 LaunchServices（open 命令），窗口枚举走 CoreGraphics。
 *
 * <p>启动/置顶用 open 不触发任何隐私授权；只有 System Events 兜底与截图需要额外权限：
 * 截图要「屏幕录制」，System Events 兜底要「自动化 + 辅助功能」。
 */
@Service
public class MacDesktopPlatform implements DesktopPlatform {

    private static final Logger log = LoggerFactory.getLogger(MacDesktopPlatform.class);

    /** 兜底方案：按 pid 把进程设为前台应用（需要辅助功能/自动化授权）。 */
    private static final String ACTIVATE_SCRIPT = """
            on run argv
                set targetPid to (item 1 of argv) as integer
                tell application "System Events"
                    set frontmost of (first application process whose unix id is targetPid) to true
                end tell
                return "OK"
            end run
            """;

    @Override
    public String name() {
        return "macos-" + System.getProperty("os.version", "unknown");
    }

    @Override
    public void launch(String app, List<String> args, Duration wait) {
        if (app == null || app.isBlank()) {
            throw ToolException.badRequest("app 不能为空：填应用名（如 TextEdit）或绝对路径（如 /Applications/X.app）");
        }
        String target = app.trim();
        List<String> cmd = new ArrayList<>();
        cmd.add("open");
        if (target.startsWith("/") || target.endsWith(".app") || target.endsWith(".app/")) {
            cmd.add(target);
        } else {
            cmd.add("-a");
            cmd.add(target);
        }
        if (args != null && !args.isEmpty()) {
            cmd.add("--args");
            cmd.addAll(args);
        }
        Result result = exec(cmd, Duration.ofSeconds(30));
        if (result.exitCode() != 0) {
            throw ToolException.badRequest("启动 " + target + " 失败：" + result.output().strip());
        }
        log.info("已启动 {}（参数 {}）", target, args);
        sleepQuietly(wait);
    }

    @Override
    public List<WindowInfo> windows() {
        try {
            return MacWindowList.list(false);
        } catch (RuntimeException e) {
            throw ToolException.internal("枚举窗口失败：" + e.getMessage(), e);
        }
    }

    @Override
    public WindowInfo activate(String app, Long pid, String title) {
        // 先找当前桌面可见窗口；找不到再算上最小化/其他桌面的窗口（用于拿到真实进程名）
        WindowInfo target = pick(app, pid, title, false);
        if (target == null) {
            target = pick(app, pid, title, true);
        }
        String owner = target != null ? target.app() : (app == null ? null : app.trim());
        if (owner == null || owner.isBlank()) {
            return null;
        }
        // open -a 要包名或路径，而窗口属主是本地化显示名（如「计算器」），所以三条路依次试：
        // 用户传入的应用名 → 由进程可执行文件反推的 .app 路径 → 窗口属主名
        List<String> candidates = new ArrayList<>();
        if (app != null && !app.isBlank()) {
            candidates.add(app.trim());
        }
        if (target != null) {
            String bundle = bundlePath(target.pid());
            if (bundle != null && !candidates.contains(bundle)) {
                candidates.add(bundle);
            }
        }
        if (!candidates.contains(owner)) {
            candidates.add(owner);
        }

        String lastFailure = "未知原因";
        for (String candidate : candidates) {
            // LaunchServices：已运行的实例会被拉到前台，不需要任何隐私授权
            Result opened = exec(List.of("open", "-a", candidate), Duration.ofSeconds(15));
            if (opened.exitCode() == 0) {
                sleepQuietly(Duration.ofMillis(400));
                WindowInfo refreshed = pick(app, pid, title, false);
                log.info("已置顶 {}（open -a {}）", refreshed == null ? owner : refreshed, candidate);
                return refreshed == null ? target : refreshed;
            }
            lastFailure = opened.output().strip();
        }

        // 兜底：按 pid 用 System Events 设前台（需要自动化/辅助功能授权）
        if (target != null) {
            Result script = exec(List.of("osascript", "-e", ACTIVATE_SCRIPT, String.valueOf(target.pid())),
                    Duration.ofSeconds(20));
            if (script.exitCode() != 0) {
                throw permissionAware(script.output());
            }
            sleepQuietly(Duration.ofMillis(400));
            WindowInfo refreshed = pick(app, pid, title, false);
            log.info("已置顶 {}（System Events）", refreshed == null ? owner : refreshed);
            return refreshed == null ? target : refreshed;
        }
        throw ToolException.notFound("无法置顶 " + owner + "：" + lastFailure
                + "；当前运行中的应用：" + runningApps());
    }

    /** 从进程的可执行路径反推 .app 包路径，例如 .../Calculator.app/Contents/MacOS/Calculator。 */
    private String bundlePath(long pid) {
        Result result = exec(List.of("ps", "-p", String.valueOf(pid), "-o", "comm="), Duration.ofSeconds(5));
        if (result.exitCode() != 0) {
            return null;
        }
        String executable = result.output().strip();
        int index = executable.indexOf(".app/");
        if (index < 0) {
            return null;
        }
        return executable.substring(0, index + ".app".length());
    }

    /** 命中窗口优先选「属于前台应用」且面积最大的那个，避免抓到工具面板。 */
    private WindowInfo pick(String app, Long pid, String title, boolean includeOffscreen) {
        List<WindowInfo> candidates;
        try {
            candidates = MacWindowList.list(includeOffscreen);
        } catch (RuntimeException e) {
            throw ToolException.internal("枚举窗口失败：" + e.getMessage(), e);
        }
        // 应用名和窗口属主名可能不一致（本地化显示名），所以补一条按进程号匹配的路
        Set<Long> pids = new LinkedHashSet<>();
        if (pid != null) {
            pids.add(pid);
        }
        if (app != null && !app.isBlank()) {
            pids.addAll(findPids(app));
        }
        boolean hasApp = app != null && !app.isBlank();
        boolean hasTitle = title != null && !title.isBlank();
        return candidates.stream()
                .filter(w -> pid == null || w.pid() == pid || pids.contains(w.pid()))
                .filter(w -> !hasTitle || w.matches(null, title))
                .filter(w -> !hasApp || pid != null || w.matches(app, null) || pids.contains(w.pid()))
                .max(Comparator.comparing(WindowInfo::frontmost).thenComparingInt(WindowInfo::area))
                .orElse(null);
    }

    private String runningApps() {
        return windows().stream()
                .map(WindowInfo::app)
                .distinct()
                .limit(15)
                .reduce((left, right) -> left + "、" + right)
                .orElse("无");
    }

    @Override
    public List<Long> findPids(String app) {
        if (app == null || app.isBlank()) {
            return List.of();
        }
        // 不能对整条命令行做包含匹配（pgrep -f），否则把命令行里带应用名的 shell/curl 也算进来；
        // comm 是可执行文件路径，只按它的文件名或路径匹配。
        String name = app.trim().toLowerCase(Locale.ROOT);
        Result result = exec(List.of("ps", "-Ao", "pid=,comm="), Duration.ofSeconds(5));
        if (result.exitCode() != 0) {
            return List.of();
        }
        long self = ProcessHandle.current().pid();
        return result.output().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(MacDesktopPlatform::parsePsLine)
                .filter(java.util.Objects::nonNull)
                .filter(entry -> entry.getKey() != self)
                .filter(entry -> {
                    String comm = entry.getValue().toLowerCase(Locale.ROOT);
                    String executable = comm.substring(comm.lastIndexOf('/') + 1);
                    return executable.equals(name) || comm.contains(name);
                })
                .map(Map.Entry::getKey)
                .distinct()
                .toList();
    }

    private static Map.Entry<Long, String> parsePsLine(String line) {
        int space = line.indexOf(' ');
        if (space <= 0) {
            return null;
        }
        try {
            return Map.entry(Long.parseLong(line.substring(0, space).strip()), line.substring(space + 1).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 先 SIGTERM 请它自己退，超过 grace 还在就 SIGKILL。
     *
     * <p>客户端忽略 Cmd+Q / SIGTERM 是常见情况（游戏客户端尤其如此），所以强杀不能省。
     */
    @Override
    public boolean close(long pid, Duration grace) {
        if (!alive(pid)) {
            return true;
        }
        exec(List.of("kill", "-TERM", String.valueOf(pid)), Duration.ofSeconds(5));
        long deadline = System.currentTimeMillis() + Math.max(0, grace.toMillis());
        while (System.currentTimeMillis() < deadline) {
            if (!alive(pid)) {
                log.info("进程 {} 已优雅退出", pid);
                return true;
            }
            sleepQuietly(Duration.ofMillis(100));
        }
        exec(List.of("kill", "-KILL", String.valueOf(pid)), Duration.ofSeconds(5));
        for (int i = 0; i < 20 && alive(pid); i++) {
            sleepQuietly(Duration.ofMillis(100));
        }
        boolean closed = !alive(pid);
        if (closed) {
            log.warn("进程 {} 未响应退出请求，已强杀", pid);
        } else {
            log.error("进程 {} 强杀后仍在运行", pid);
        }
        return closed;
    }

    private boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private ToolException permissionAware(String output) {
        String message = output == null ? "" : output.strip();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("-1743") || lower.contains("not authorized to send apple events") || message.contains("未获得授权")) {
            return ToolException.internal("缺少自动化权限：请在 系统设置 → 隐私与安全性 → 自动化 里允许运行本服务的终端/IDE 控制 System Events，"
                    + "然后重启服务。原始错误：" + message);
        }
        if (lower.contains("assistive access") || lower.contains("not allowed") || message.contains("-1719")) {
            return ToolException.internal("缺少辅助功能权限：请在 系统设置 → 隐私与安全性 → 辅助功能 里勾选运行本服务的终端或 IDE，"
                    + "然后重启服务。原始错误：" + message);
        }
        return ToolException.internal("置顶失败：" + message);
    }

    private Result exec(List<String> command, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw ToolException.internal("命令超时：" + String.join(" ", command.subList(0, Math.min(2, command.size()))));
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            throw ToolException.internal("无法执行命令 " + command.getFirst() + "：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ToolException.internal("命令被中断：" + command.getFirst(), e);
        }
    }

    private void sleepQuietly(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Result(int exitCode, String output) {
    }
}
