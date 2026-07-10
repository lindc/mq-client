package com.mqbrowser;

import com.mqbrowser.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

/** JavaFX 应用程序入口。 */
public class MQBrowserApp extends Application {

    @Override
    public void start(Stage stage) {
        // 构建主界面，放入 1000×640 的场景
        MainView mainView = new MainView();
        Scene scene = new Scene(mainView.getRoot(), 1000, 640);
        // 挂载全局样式表（界面颜色、表格斑马纹、悬停效果等都在这里定义）
        scene.getStylesheets().add(
                Objects.requireNonNull(MQBrowserApp.class.getResource("/styles/app.css")).toExternalForm());

        stage.setTitle("MQBrowser");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
