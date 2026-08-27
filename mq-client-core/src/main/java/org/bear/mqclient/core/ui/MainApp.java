package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.CoreConfig;
import org.bear.mqclient.core.config.AppProperties;
import org.bear.mqclient.core.config.ConnectionStore;
import org.bear.mqclient.core.config.LocaleHolder;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.MessageSource;

/**
 * JavaFX 入口。
 * <p>
 * 与 Spring Boot 整合方式（掘金《JavaFX 桌面开发——进阶 · SpringBoot 整合》的启动方式）：
 * <ul>
 *   <li>{@code main()} 中先 {@code SpringApplication.run} 启动 Spring 容器，
 *       再 {@code Application.launch} 启动 JavaFX；</li>
 *   <li>{@code start()} 中从容器获取 Bean 构建界面；</li>
 *   <li>{@code stop()} 中关闭容器。</li>
 * </ul>
 * MQ 插件仍通过 ServiceLoader SPI 发现，Spring 仅管理 core 内的单例与配置。
 */
public class MainApp extends Application {

    private static ConfigurableApplicationContext context;

    /** 由 {@link org.bear.mqclient.app.App#main(String[])} 在 launch 前注入已启动的容器。 */
    public static void setContext(ConfigurableApplicationContext ctx) {
        context = ctx;
    }

    @Override
    public void start(Stage primaryStage) {
        ConnectionStore store = context.getBean(ConnectionStore.class);
        AppProperties props = context.getBean(AppProperties.class);
        MessageSource messageSource = context.getBean(MessageSource.class);
        LocaleHolder localeHolder = context.getBean(LocaleHolder.class);

        MainController controller = new MainController(store, messageSource, localeHolder);
        int width = props.getWindow().getWidth();
        int height = props.getWindow().getHeight();
        Scene scene = new Scene(controller.getRoot(), width, height);

        primaryStage.setTitle("MQ Client");
        // 加载图标（可选）
        var iconStream = getClass().getResourceAsStream("/icons/app.png");
        if (iconStream != null) {
            primaryStage.getIcons().add(new Image(iconStream));
        }
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
    }

    /** 直接运行本类时的备用入口：同样先启动 Spring 再启动 JavaFX。 */
    public static void main(String[] args) {
        context = SpringApplication.run(CoreConfig.class, args);
        launch(args);
    }
}
