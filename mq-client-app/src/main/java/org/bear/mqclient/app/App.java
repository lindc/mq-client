package org.bear.mqclient.app;

import org.bear.mqclient.core.ui.MainApp;
import javafx.application.Application;

/**
 * 应用入口（聚合运行模块）。
 * <p>
 * mq-client-app 作为可运行模块，依赖所有 MQ 的 UI/service 实现，
 * 通过 core 的 {@link MainApp} 启动 JavaFX，运行时经 ServiceLoader SPI 发现各 Provider。
 * <p>
 * 无 Spring 容器：{@link MainApp#bootstrap()} 手动装配 core 组件
 * （连接存储、配置、国际化绑定），再启动 JavaFX。
 */
public class App {

    public static void main(String[] args) {
        // 手动装配 core 组件，再启动 JavaFX
        MainApp.bootstrap();
        Application.launch(MainApp.class, args);
    }
}
