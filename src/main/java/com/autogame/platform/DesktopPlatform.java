package com.autogame.platform;

import java.time.Duration;
import java.util.List;

/**
 * 与操作系统桌面交互的最小能力集。
 *
 * <p>当前只实现 macOS；后续要支持 Windows 时新增一个实现类即可，业务层不感知差异。
 */
public interface DesktopPlatform {

    /** 平台标识，用于健康检查。 */
    String name();

    /**
     * 启动应用。已运行时相当于激活它（macOS 的 open 语义）。
     *
     * @param app  应用名（如 "TextEdit"）或绝对路径（如 "/Applications/X.app"）
     * @param args 传给应用的启动参数，可为空
     * @param wait 启动后等待多久，留给调用方截图
     */
    void launch(String app, List<String> args, Duration wait);

    /** 当前所有可见窗口。 */
    List<WindowInfo> windows();

    /**
     * 把应用切到顶层。
     *
     * @param app   进程名包含匹配，可为空
     * @param pid   精确进程号，可为空
     * @param title 窗口标题包含匹配，可为空
     * @return 命中的窗口；未命中返回空
     */
    WindowInfo activate(String app, Long pid, String title);

    /** 按进程名查 pid，含没有窗口的后台进程；查不到返回空列表。 */
    List<Long> findPids(String app);

    /**
     * 结束进程：先要求优雅退出，超过 grace 仍在就强杀。
     *
     * @return 是否已确认退出
     */
    boolean close(long pid, Duration grace);
}
