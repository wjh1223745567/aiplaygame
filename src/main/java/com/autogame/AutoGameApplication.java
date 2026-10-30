package com.autogame;

import com.autogame.config.AutoGameProperties;
import com.autogame.game.wendao.WendaoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 桌面自动化工具的入口：应用启动/置顶、截图、鼠标键盘操作、视觉大模型识图，
 * 以及具体游戏（问道）的登录与日常任务。
 */
@SpringBootApplication
@EnableConfigurationProperties({AutoGameProperties.class, WendaoProperties.class})
public class AutoGameApplication {

    public static void main(String[] args) {
        // 截图与鼠标控制都依赖 AWT，必须在 Spring 启动前关掉 headless
        System.setProperty("java.awt.headless", "false");
        SpringApplication application = new SpringApplication(AutoGameApplication.class);
        application.setHeadless(false);
        application.run(args);
    }
}
