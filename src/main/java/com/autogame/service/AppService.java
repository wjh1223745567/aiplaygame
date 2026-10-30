package com.autogame.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.platform.DesktopPlatform;
import com.autogame.platform.WindowInfo;
import com.autogame.support.ToolException;

/**
 * 应用与窗口操作：启动、枚举、切到顶层、等待窗口、关闭、状态查询。
 */
@Service
public class AppService {

    private static final Logger log = LoggerFactory.getLogger(AppService.class);
    private static final long POLL_MILLIS = 200;

    private final DesktopPlatform platform;

    public AppService(DesktopPlatform platform) {
        this.platform = platform;
    }

    public void launch(String app, List<String> args, Duration wait) {
        platform.launch(app, args, wait);
    }

    /** 启动并等窗口出现；windowTimeout 为 0 表示不等。 */
    public WindowInfo launchAndWait(String app, List<String> args, Duration wait, String title,
            Duration windowTimeout) {
        launch(app, args, wait);
        return isNoWait(windowTimeout) ? null : waitForWindow(app, title, windowTimeout);
    }

    /**
     * 关闭后重新启动：先确认旧进程真的退干净，否则新实例可能沿用旧的启动状态（如登录态、单实例锁）。
     */
    public RestartResult restart(String app, List<String> args, Duration wait, String title,
            Duration windowTimeout, Duration grace) {
        StatusResult before = status(app, null, null);
        if (before.running()) {
            CloseResult closed = close(app, null, grace);
            if (!closed.stillRunning().isEmpty()) {
                throw ToolException.internal("重启前关闭失败，仍在运行：" + closed.stillRunning());
            }
            waitUntilStopped(app, grace.plusSeconds(2));
        }
        launch(app, args, wait);
        WindowInfo window = isNoWait(windowTimeout) ? null : waitForWindow(app, title, windowTimeout);
        log.info("重启 {} 完成（重启前进程 {}，窗口 {}）", app, before.pids(), window);
        return new RestartResult(before.pids(), window);
    }

    private void waitUntilStopped(String app, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!status(app, null, null).running()) {
                return;
            }
            sleep(POLL_MILLIS);
        }
        throw ToolException.internal("关闭 " + app + " 后进程仍未消失，放弃重启");
    }

    private static boolean isNoWait(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative();
    }

    public List<WindowInfo> windows() {
        return platform.windows();
    }

    public WindowInfo activate(String app, Long pid, String title) {
        return platform.activate(app, pid, title);
    }

    /** 轮询等待窗口出现，超时返回 null——启动后紧接着截图/点击时用得上。 */
    public WindowInfo waitForWindow(String app, String title, Duration timeout) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeout.toMillis());
        do {
            WindowInfo hit = matchWindows(app, title, null).stream()
                    .max(frontmostThenLargest())
                    .orElse(null);
            if (hit != null) {
                return hit;
            }
            sleep(POLL_MILLIS);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /** 结束应用：给了 pid 就只关它，否则按「进程名 + 窗口属主」把所有匹配进程都关掉。 */
    public CloseResult close(String app, Long pid, Duration grace) {
        List<Long> targets = resolvePids(app, pid);
        if (targets.isEmpty()) {
            throw ToolException.notFound("没有找到运行中的进程：app=" + app + " pid=" + pid);
        }
        List<Long> closed = new ArrayList<>();
        List<Long> stillRunning = new ArrayList<>();
        for (Long target : targets) {
            if (platform.close(target, grace)) {
                closed.add(target);
            } else {
                stillRunning.add(target);
            }
        }
        log.info("关闭 app={} 目标={} 已退出={} 仍在={}", app, targets, closed, stillRunning);
        return new CloseResult(targets, List.copyOf(closed), List.copyOf(stillRunning));
    }

    public StatusResult status(String app, Long pid, String title) {
        List<Long> pids = resolvePids(app, pid);
        List<WindowInfo> matched = matchWindows(app, title, pid);
        return new StatusResult(!pids.isEmpty() || !matched.isEmpty(), pids, matched);
    }

    /**
     * 窗口匹配同时看两样东西：窗口标题里的进程名，以及进程号。
     * 后者是为了处理本地化应用名（如 TextEdit 的窗口属主显示为「文本编辑」）。
     */
    private List<WindowInfo> matchWindows(String app, String title, Long pid) {
        Set<Long> pids = new LinkedHashSet<>(resolvePids(app, pid));
        boolean hasApp = app != null && !app.isBlank();
        boolean hasTitle = title != null && !title.isBlank();
        return windows().stream()
                .filter(window -> pid == null || window.pid() == pid || pids.contains(window.pid()))
                .filter(window -> !hasTitle || window.matches(null, title))
                .filter(window -> !hasApp || pid != null || window.matches(app, null) || pids.contains(window.pid()))
                .toList();
    }

    private static Comparator<WindowInfo> frontmostThenLargest() {
        return Comparator.comparing(WindowInfo::frontmost).thenComparingInt(WindowInfo::area);
    }

    private List<Long> resolvePids(String app, Long pid) {
        Set<Long> pids = new LinkedHashSet<>();
        if (pid != null) {
            pids.add(pid);
        }
        if (app != null && !app.isBlank()) {
            pids.addAll(platform.findPids(app));
            windows().stream()
                    .filter(window -> window.matches(app, null))
                    .map(WindowInfo::pid)
                    .forEach(pids::add);
        }
        return List.copyOf(pids);
    }

    /** 按进程名查 pid 集合，供外部拼接查询用。 */
    public List<Long> pids(String app) {
        return resolvePids(app, null);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @param targets      本次尝试关闭的进程
     * @param closed       已确认退出
     * @param stillRunning 仍在运行（强杀也没结束）
     */
    public record CloseResult(List<Long> targets, List<Long> closed, List<Long> stillRunning) {
    }

    /**
     * @param previousPids 重启前匹配到的进程
     * @param window       重启后等到的窗口；没等到为 null
     */
    public record RestartResult(List<Long> previousPids, WindowInfo window) {
    }

    /**
     * @param running 是否有进程或窗口存在
     * @param pids    匹配到的进程号
     * @param windows 匹配到的窗口（只含当前桌面可见的）
     */
    public record StatusResult(boolean running, List<Long> pids, List<WindowInfo> windows) {
    }
}
