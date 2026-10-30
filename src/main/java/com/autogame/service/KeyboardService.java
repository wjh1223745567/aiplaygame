package com.autogame.service;

import java.awt.event.KeyEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.platform.DesktopRobot;
import com.autogame.support.ToolException;

/**
 * 键盘输入：把字符串逐个敲进当前焦点控件，或按单个/组合按键。
 *
 * <p>只支持 US 键盘布局能直接打出来的 ASCII 字符（账号、密码、指令都是这类）。
 * 中文要靠系统输入法，合成按键绕不过去，所以遇到非 ASCII 直接报错，不要静默丢字符。
 */
@Service
public class KeyboardService {

    private static final Logger log = LoggerFactory.getLogger(KeyboardService.class);

    /** 每个字符之间的间隔：太短时游戏客户端容易漏字符。 */
    private static final long TYPE_GAP_MILLIS = 45;
    /** 按键按住时长，部分客户端要求按下与抬起之间有间隔。 */
    private static final int KEY_HOLD_MILLIS = 12;

    private final DesktopRobot desktopRobot;

    public KeyboardService(DesktopRobot desktopRobot) {
        this.desktopRobot = desktopRobot;
    }

    /** 逐个字符敲入；遇到不支持的字符整串拒绝，避免只输一半。 */
    public void type(String text) {
        if (text == null || text.isEmpty()) {
            throw ToolException.badRequest("要输入的文本不能为空");
        }
        Stroke[] strokes = new Stroke[text.length()];
        for (int i = 0; i < text.length(); i++) {
            strokes[i] = strokeOf(text.charAt(i));
        }
        for (Stroke stroke : strokes) {
            tap(stroke);
            sleep(TYPE_GAP_MILLIS);
        }
        log.info("键盘输入 {} 个字符", strokes.length);
    }

    /** 单个按键，如 {@link KeyEvent#VK_ENTER}。 */
    public void tap(int keyCode) {
        desktopRobot.robot().keyPress(keyCode);
        sleep(KEY_HOLD_MILLIS);
        desktopRobot.robot().keyRelease(keyCode);
    }

    /** 组合键，如 {@code chord(KeyEvent.VK_META, KeyEvent.VK_A)} 表示 ⌘A。 */
    public void chord(int modifier, int keyCode) {
        desktopRobot.robot().keyPress(modifier);
        sleep(KEY_HOLD_MILLIS);
        desktopRobot.robot().keyPress(keyCode);
        sleep(KEY_HOLD_MILLIS);
        desktopRobot.robot().keyRelease(keyCode);
        desktopRobot.robot().keyRelease(modifier);
    }

    private void tap(Stroke stroke) {
        if (!stroke.shift()) {
            tap(stroke.keyCode());
            return;
        }
        desktopRobot.robot().keyPress(KeyEvent.VK_SHIFT);
        sleep(KEY_HOLD_MILLIS);
        desktopRobot.robot().keyPress(stroke.keyCode());
        sleep(KEY_HOLD_MILLIS);
        desktopRobot.robot().keyRelease(stroke.keyCode());
        desktopRobot.robot().keyRelease(KeyEvent.VK_SHIFT);
    }

    /** US 布局下的字符 → 键位映射；shift 表示需要配合上档键。 */
    private static Stroke strokeOf(char c) {
        if (c >= 'a' && c <= 'z') {
            return new Stroke(KeyEvent.VK_A + (c - 'a'), false);
        }
        if (c >= 'A' && c <= 'Z') {
            return new Stroke(KeyEvent.VK_A + (c - 'A'), true);
        }
        if (c >= '0' && c <= '9') {
            return new Stroke(KeyEvent.VK_0 + (c - '0'), false);
        }
        return switch (c) {
            case ' ' -> new Stroke(KeyEvent.VK_SPACE, false);
            case '-' -> new Stroke(KeyEvent.VK_MINUS, false);
            case '_' -> new Stroke(KeyEvent.VK_MINUS, true);
            case '=' -> new Stroke(KeyEvent.VK_EQUALS, false);
            case '+' -> new Stroke(KeyEvent.VK_EQUALS, true);
            case '[' -> new Stroke(KeyEvent.VK_OPEN_BRACKET, false);
            case '{' -> new Stroke(KeyEvent.VK_OPEN_BRACKET, true);
            case ']' -> new Stroke(KeyEvent.VK_CLOSE_BRACKET, false);
            case '}' -> new Stroke(KeyEvent.VK_CLOSE_BRACKET, true);
            case '\\' -> new Stroke(KeyEvent.VK_BACK_SLASH, false);
            case '|' -> new Stroke(KeyEvent.VK_BACK_SLASH, true);
            case ';' -> new Stroke(KeyEvent.VK_SEMICOLON, false);
            case ':' -> new Stroke(KeyEvent.VK_SEMICOLON, true);
            case '\'' -> new Stroke(KeyEvent.VK_QUOTE, false);
            case '"' -> new Stroke(KeyEvent.VK_QUOTE, true);
            case ',' -> new Stroke(KeyEvent.VK_COMMA, false);
            case '<' -> new Stroke(KeyEvent.VK_COMMA, true);
            case '.' -> new Stroke(KeyEvent.VK_PERIOD, false);
            case '>' -> new Stroke(KeyEvent.VK_PERIOD, true);
            case '/' -> new Stroke(KeyEvent.VK_SLASH, false);
            case '?' -> new Stroke(KeyEvent.VK_SLASH, true);
            case '`' -> new Stroke(KeyEvent.VK_BACK_QUOTE, false);
            case '~' -> new Stroke(KeyEvent.VK_BACK_QUOTE, true);
            case '!' -> new Stroke(KeyEvent.VK_1, true);
            case '@' -> new Stroke(KeyEvent.VK_2, true);
            case '#' -> new Stroke(KeyEvent.VK_3, true);
            case '$' -> new Stroke(KeyEvent.VK_4, true);
            case '%' -> new Stroke(KeyEvent.VK_5, true);
            case '^' -> new Stroke(KeyEvent.VK_6, true);
            case '&' -> new Stroke(KeyEvent.VK_7, true);
            case '*' -> new Stroke(KeyEvent.VK_8, true);
            case '(' -> new Stroke(KeyEvent.VK_9, true);
            case ')' -> new Stroke(KeyEvent.VK_0, true);
            default -> throw ToolException.badRequest("不支持的字符「" + c
                    + "」：键盘输入只支持 US 布局的 ASCII 字符，中文请改用系统输入法或粘贴");
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ToolException.internal("键盘输入被中断", e);
        }
    }

    /** 一个字符对应的键位；shift 为 true 时要先按下上档键。 */
    private record Stroke(int keyCode, boolean shift) {
    }
}
