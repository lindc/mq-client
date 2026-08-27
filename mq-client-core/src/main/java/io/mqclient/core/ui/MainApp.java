package io.mqclient.core.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * JavaFX 入口。
 */
public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        MainController controller = new MainController();
        Scene scene = new Scene(controller.getRoot(), 1200, 800);

        primaryStage.setTitle("MQ Client");
        // 加载图标（可选）
        var iconStream = getClass().getResourceAsStream("/icons/app.png");
        if (iconStream != null) {
            primaryStage.getIcons().add(new Image(iconStream));
        }
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
