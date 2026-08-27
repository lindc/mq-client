package org.bear.mqclient.app;

import org.bear.mqclient.core.CoreConfig;
import org.bear.mqclient.core.ui.MainApp;
import javafx.application.Application;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 应用入口（聚合运行模块）。
 * <p>
 * mq-client-app 作为可运行模块，依赖所有 MQ 的 UI/service 实现，
 * 通过 core 的 {@link MainApp} 启动 JavaFX 并在运行时经 SPI 发现各 Provider。
 * <p>
 * 启动方式与掘金《JavaFX 桌面开发——进阶 · SpringBoot 整合》一致：
 * main 中先 {@code SpringApplication.run} 启动 Spring 容器，
 * 再 {@code Application.launch} 启动 JavaFX。
 */
public class App {

    public static void main(String[] args) {
        // 先启动 Spring 容器，再启动 JavaFX
        ConfigurableApplicationContext context = SpringApplication.run(CoreConfig.class, args);
        MainApp.setContext(context);
        Application.launch(MainApp.class, args);
    }
}
