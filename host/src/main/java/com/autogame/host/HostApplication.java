package com.autogame.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 宿主层入口：常驻提供 {@code /ai/*} 工具门面（端口见 application.yml）。
 *
 * <p>本身不含业务与决策逻辑，只负责把客户端的工具调用转到 mcpserver。
 */
@SpringBootApplication
public class HostApplication {

    public static void main(String[] args) {
        SpringApplication.run(HostApplication.class, args);
    }
}
