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
    MAIN("游戏主页面，中心点有任务，做下角聊天信息，左上角地图，右侧有任务队列"),

    /** 画面上盖着要点一下的框（含客户端提示框）。描述里必须带「看得见框体本身」，防止看到文字就当弹窗。 */
    DIALOG("有弹窗，包含确认按钮或X关闭等"),

    /** 回合制战斗画面。 */
    BATTLE("战斗中：回合制场景，角色头顶有血条/蓝条，下方是技能选择栏",
            "等待，不要操作"),

    /** 还没进游戏。 */
    LOGIN("登录页面，包含区组，进入游戏操作按钮"),

    /** 看不出来。 */
    UNKNOWN("看不清，或上面都不是",
            "人工看一眼");

    /**
     * 提示词开头：限定只回一个 JSON，并把截图真实像素尺寸告诉模型，让它按像素报坐标。
     *
     * <p>示例只能用 MAIN + null 坐标：示例里出现 DIALOG 和具体数字，免费小模型会连页面带
     * 坐标整句抄下来，不管截图里是什么都回弹窗 + 中心点（实测连续四次分毫不差）。
     */
    private static String header(int pixelWidth, int pixelHeight) {
        return """
                这是问道游戏页面，识别当前页面所属场景：""".formatted(pixelWidth, pixelHeight);
    }

    /** 坐标约定 + 兜底。实测模型爱抄示例、爱把看不清当成弹窗，这里逐条封死。 */
    private static String footer(int pixelWidth, int pixelHeight) {
        return """
               返回坐标来实现点击操作
               """
                .formatted(pixelWidth - 1, pixelHeight - 1, pixelWidth / 2, pixelHeight / 2);
    }

    /** 候选词表；按出现位置取最靠前的那个，兼容模型输出「MAIN。游戏主界面...」这类原文。 */
    private static final Map<String, WendaoStage> TOKENS = tokens();

    private final String describe;
    private final String action;

    WendaoStage(String describe, String action) {
        this.describe = describe;
        this.action = action;
    }

    /** 只关心页面特征、不给动作的阶段：action 留空。 */
    WendaoStage(String describe) {
        this(describe, "");
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
        return Map.copyOf(map);
    }
}
