package io.mqclient.app;

import io.mqclient.core.ui.MainApp;

/**
 * 应用入口（聚合运行模块）。
 * <p>
 * mq-client-app 作为可运行模块，依赖所有 MQ 的 UI/service 实现，
 * 通过 core 的 {@link MainApp} 启动 JavaFX 并在运行时经 SPI 发现各 Provider。
 */
public class App {

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
