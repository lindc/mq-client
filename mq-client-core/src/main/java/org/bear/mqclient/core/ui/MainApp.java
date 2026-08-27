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
 * 与 Spring Boot 整合方式（经典模式）：
 * <ul>
 *   <li>{@code init()} 中启动 Spring ApplicationContext（DI + 外部化配置）；</li>
 *   <li>{@code start()} 中从容器获取 Bean 构建界面；</li>
 *   <li>{@code stop()} 中关闭容器。</li>
 * </ul>
 * MQ 插件仍通过 ServiceLoader SPI 发现，Spring 仅管理 core 内的单例与配置。
 */
public class MainApp extends Application {

    private ConfigurableApplicationContext context;

    @Override
    public void init() {
        context = SpringApplication.run(CoreConfig.class);
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

    public static void main(String[] args) {
        launch(args);
    }
}
