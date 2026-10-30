package com.autogame.game.wendao;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.autogame.AutoGameApplication;

/**
 * 问道页面识别的接口调用测试：调 {@link WendaoStageFunction}（和 POST /api/function/wendaoStage
 * 同一条路），客户端没开时由 @RequiresApp 切面自动启动，再真调一次视觉大模型，把结果打出来。
 *
 * <p>只负责「调一次、看结果」，不做任何验收断言。跑法：
 *
 * <pre>
 * mvn -o test -Dtest=WendaoVisionLiveTest
 * </pre>
 */
@SpringBootTest(classes = AutoGameApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WendaoVisionLiveTest {

    private static final Logger log = LoggerFactory.getLogger(WendaoVisionLiveTest.class);

    @Autowired
    private WendaoStageFunction function;

    @Test
    void 调用一次页面识别() {
        WendaoVision.Reading reading = function.execute(new WendaoStageFunction.Request());

        log.info("识别结果：页面={} 说明={} 动作={} 截图像素点位={} 屏幕点位={} 模型={} 耗时={}ms",
                reading.stage(), reading.describe(), reading.action(),
                reading.spot(), reading.screenPoint(), reading.model(), reading.elapsedMs());
        log.info("模型原文：{}", reading.answer());
    }
}
