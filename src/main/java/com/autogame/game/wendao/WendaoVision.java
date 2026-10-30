package com.autogame.game.wendao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.service.VisionService;

/**
 * 只看一件事：当前截图属于哪个阶段。
 *
 * <p>提示词把候选词写死，并要求只回一个词，尽量压住模型的自由发挥；
 * 解析失败（{@link WendaoStage#UNKNOWN}）时流程只等待，不乱点。
 */
@Service
public class WendaoVision implements StageReader {

    private static final Logger log = LoggerFactory.getLogger(WendaoVision.class);

    private static final String PROMPT = """
            这是一张「问道」游戏客户端的截图。只回答下面一个词，不要解释、不要标点：
            MAIN = 游戏主界面，没有战斗、没有对话框
            DIALOG = 打开了 NPC 对话框或确认弹窗，需要点一下按钮
            BATTLE = 正在战斗（回合操作、血条、技能栏）
            LOGIN = 登录界面（账号密码框、进入游戏按钮、选服列表）
            OFFLINE = 掉线、连接断开、网络错误提示
            UNKNOWN = 以上都不是或看不出来
            """;

    private final VisionService visionService;
    private final WendaoProperties config;

    public WendaoVision(VisionService visionService, WendaoProperties config) {
        this.visionService = visionService;
        this.config = config;
    }

    /**
     * @param context 当前正在做什么，例如「师门任务流程」；会拼进提示词帮助模型聚焦
     */
    @Override
    public WendaoStage detect(String context) {
        String prompt = context == null || context.isBlank() ? PROMPT : PROMPT + "\n当前正在做：" + context;
        VisionService.VisionResult result = visionService.analyze(prompt, "window", config.app(), config.title(), null);
        WendaoStage stage = WendaoStage.parse(result.content());
        log.info("阶段判定：{}（模型 {}，耗时 {}ms，原文 {}）", stage, result.model(), result.elapsedMs(),
                trim(result.content()));
        return stage;
    }

    private static String trim(String content) {
        String single = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        return single.length() <= 40 ? single : single.substring(0, 40) + "...";
    }
}
