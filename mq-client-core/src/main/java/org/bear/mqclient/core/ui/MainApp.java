package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.config.AppProperties;
import org.bear.mqclient.core.config.ConnectionStore;
import org.bear.mqclient.core.config.LocaleHolder;
import org.bear.mqclient.core.util.I18nBinder;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * JavaFX 入口。
 * <p>
 * 无 Spring 容器：{@link #bootstrap()} 完成手动装配（连接存储、外部配置、
 * 国际化绑定与插件 bundle 注册），再由 {@code App.main} 或本类的
 * {@code main} 调用 {@code launch} 启动 JavaFX。
 * <p>
 * MQ 插件仍通过 ServiceLoader SPI 发现，装配逻辑不依赖任何框架。
 */
public class MainApp extends Application {

    private static ConnectionStore store;
    private static AppProperties props;
    private static LocaleHolder localeHolder;

    /** 手动装配：I18n 绑定 + 插件 bundle 注册 + 组件实例化（launch 前调用一次） */
    public static void bootstrap() {
        localeHolder = new LocaleHolder();
        I18nBinder.bind(localeHolder);
        store = new ConnectionStore();
        props = AppProperties.load();
    }

    @Override
    public void start(Stage primaryStage) {
        MainController controller = new MainController(store, localeHolder);
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

    /** 直接运行本类时的备用入口：与 {@code App.main} 等价。 */
    public static void main(String[] args) {
        bootstrap();
        launch(args);
    }
}
