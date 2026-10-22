package com.autogame.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * 宿主层入口。
 *
 * <p>带 {@code --action=...} 启动时是一次性命令行任务，不启 Web 容器；
 * 不带该参数时作为 AI 服务常驻（默认端口见 application.yml）。
 */
@SpringBootApplication
public class HostApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HostApplication.class);
        boolean oneShot = Arrays.stream(args).anyMatch(a -> a.startsWith("--action"));
        if (oneShot) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
