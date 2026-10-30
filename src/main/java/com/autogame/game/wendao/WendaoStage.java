package com.autogame.game.wendao;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 截图里「现在是什么页面」的判断结果。只做识别，不做任何操作。
 *
 * <p>每个常量带两段文字：{@link #describe()} 说当前页面长什么样，{@link #action()} 说这个页面
 * 需要执行什么动作。提示词就由这两段拼成（见 {@link #prompt()}），
 * **要调识别效果，只改这个文件**：改页面特征就动 {@code describe}，改判定倾向就动 {@code action}
 * 或末尾那句兜底。
 *
 * <p>取值由视觉模型给出，但收敛到这里，模型回中文/英文、带标点都能认；
 * 认不出来就是 {@link #UNKNOWN}。宁缺勿猜：把主界面误判成 {@link #LOGIN}
 * 会让调用方白等一场登录。
 *
 * <p>登录前的客户端提示框（更新、公告、协议、安全风险）不再单列阶段：
 * 它和游戏内的 NPC 对话一样都是「盖在画面上、要先点一下的框」，统一归 {@link #DIALOG}。
 */
public enum WendaoStage {

    /** 角色在场景里，能看到 HUD。 */
    MAIN("游戏主界面：角色站在地图里，画面底部有一排技能/功能按钮，左下角有聊天框",
            "无，已经在玩游戏"),

    /** 画面上盖着要点一下的框（含客户端提示框）。 */
    DIALOG("有弹窗或对话框盖住画面：NPC 对话、接/交任务、安全风险提示、版本更新、公告、用户协议",
            "点一下关闭（X）或确认"),

    /** 回合制战斗画面。 */
    BATTLE("战斗中：回合制场景，角色头顶有血条/蓝条，下方是技能选择栏",
            "等待，不要操作"),

    /** 还没进游戏。 */
    LOGIN("登录/选服页：能看到服务器列表、账号输入框、「进入游戏」按钮或扫码二维码",
            "点「进入游戏」"),

    /** 掉线提示。 */
    OFFLINE("已掉线：连接断开、网络错误、线路繁忙、重新连接这类提示",
            "重新登录"),

    /** 看不出来。 */
    UNKNOWN("看不清，或上面都不是",
            "人工看一眼");

    /** 提示词开头：限定只回一个 JSON，并把截图真实像素尺寸告诉模型，让它按像素报坐标。 */
    private static String header(int pixelWidth, int pixelHeight) {
        return """
                这是一张 %dx%d 像素的「问道」游戏客户端截图。判断当前页面，并给出要点一下的位置，\
                只输出一个 JSON，不要解释、不要 markdown 代码块：
                {"stage":"DIALOG","x":%d,"y":%d}
                stage 只能取下面其中一个：""".formatted(pixelWidth, pixelHeight, pixelWidth / 2, pixelHeight / 2);
    }

    /** 坐标约定 + 兜底。实测模型爱把主界面猜成 LOGIN，这里明确不许猜。 */
    private static String footer(int pixelWidth, int pixelHeight) {
        return """

                x、y = 这个动作要点的控件中心点，整数像素坐标，原点在截图左上角，
                x 范围 0~%d，y 范围 0~%d。要框住那个按钮本身，不要把点给到框的边角或空白处。
                这个页面不需要点（MAIN / BATTLE / UNKNOWN）时，x、y 都给 null。
                拿不准页面就答 UNKNOWN，不要猜 LOGIN。""".formatted(pixelWidth - 1, pixelHeight - 1);
    }

    /** 候选词表；按出现位置取最靠前的那个，兼容模型输出「MAIN。游戏主界面...」这类原文。 */
    private static final Map<String, WendaoStage> TOKENS = tokens();

    private final String describe;
    private final String action;

    WendaoStage(String describe, String action) {
        this.describe = describe;
        this.action = action;
    }

    /** 当前页面是什么样，同时给模型当判定特征，也给调用方看。 */
    public String describe() {
        return describe;
    }

    /** 这个页面需要执行什么动作。只作为提示词的一部分和返回里的说明，本服务不会去执行它。 */
    public String action() {
        return action;
    }

    /**
     * 拼出提示词：每个阶段一行「{@code NAME = <页面描述>；动作：<要做什么>}」，
     * 外加「按截图像素报坐标」的约定。
     *
     * <p>必须把截图的真实像素尺寸传进去：不告诉模型图片多大，它只会给归一化坐标或猜一个数。
     * 词表由枚举自己生成，加一个阶段不用记得去提示词里补一行。
     */
    public static String prompt(int pixelWidth, int pixelHeight) {
        String vocabulary = Arrays.stream(values())
                .map(stage -> stage.name() + " = " + stage.describe() + "；动作：" + stage.action())
                .collect(Collectors.joining("\n"));
        return header(pixelWidth, pixelHeight) + "\n" + vocabulary + footer(pixelWidth, pixelHeight);
    }

    /** 从模型输出里解析阶段；解析不出来返回 {@link #UNKNOWN}。 */
    public static WendaoStage parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return UNKNOWN;
        }
        String upper = answer.toUpperCase(Locale.ROOT);
        WendaoStage best = UNKNOWN;
        int bestIndex = Integer.MAX_VALUE;
        for (Map.Entry<String, WendaoStage> entry : TOKENS.entrySet()) {
            int index = upper.indexOf(entry.getKey());
            if (index >= 0 && index < bestIndex) {
                bestIndex = index;
                best = entry.getValue();
            }
        }
        return best;
    }

    private static Map<String, WendaoStage> tokens() {
        Map<String, WendaoStage> map = new LinkedHashMap<>();
        map.put("MAIN", MAIN);
        map.put("BATTLE", BATTLE);
        map.put("DIALOG", DIALOG);
        map.put("LOGIN", LOGIN);
        map.put("OFFLINE", OFFLINE);
        map.put("UNKNOWN", UNKNOWN);
        map.put("主界面", MAIN);
        map.put("地图", MAIN);
        map.put("战斗", BATTLE);
        map.put("弹窗", DIALOG);
        map.put("对话框", DIALOG);
        map.put("提示框", DIALOG);
        map.put("确认框", DIALOG);
        map.put("公告", DIALOG);
        map.put("登录", LOGIN);
        map.put("选服", LOGIN);
        map.put("掉线", OFFLINE);
        map.put("断开", OFFLINE);
        return Map.copyOf(map);
    }
}
