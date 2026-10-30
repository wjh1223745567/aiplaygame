package com.autogame.platform;

import java.util.List;

/**
 * 输入接管检测：动作前确认没有人在用鼠标键盘、也没有远程控制工具接管。
 *
 * <p>这是「动作能不能安全执行」的判断依据，不负责具体操作。
 */
public interface InputActivity {

    /** 距上一次真实输入事件（鼠标、键盘、触控板）的毫秒数；本平台判断不了时返回 -1。 */
    long idleMillis();

    /** 正在接管输入的远程控制/自动操作进程，空列表表示没有。 */
    List<String> hijackers();
}
