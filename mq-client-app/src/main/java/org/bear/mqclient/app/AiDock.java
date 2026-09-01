package org.bear.mqclient.app;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 右侧 AI 助手面板（可最小化）。
 * <p>
 * 上方为文字输出区，下方为输入 textarea + 发送按钮。目前仅做 UI 占位，
 * 发送逻辑预留 TODO 以便后续接入 AI 服务。
 */
final class AiDock extends VBox {

    static final double WIDTH = 320;

    private final TextArea output;

    AiDock(Runnable onMinimize) {
        setId("ai-dock");
        setMinWidth(220);
        setPrefWidth(WIDTH);
        setMaxWidth(560);

        var title = new Label("AI 助手");
        title.getStyleClass().addAll(Styles.TITLE_4, "header-label");

        var minimizeBtn = new Button();
        minimizeBtn.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("mdal-close"));
        minimizeBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);
        minimizeBtn.setTooltip(new javafx.scene.control.Tooltip("最小化"));
        minimizeBtn.setOnAction(e -> onMinimize.run());

        var header = new HBox(8, title, new Spacer(), minimizeBtn);
        header.getStyleClass().add("dock-header");
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        output = new TextArea();
        output.setEditable(false);
        output.setWrapText(true);
        output.setPromptText("AI 回复将显示在这里…");
        output.getStyleClass().add("output");
        VBox.setVgrow(output, Priority.ALWAYS);

        var input = new TextArea();
        input.setPromptText("输入消息，回车发送…");
        input.setPrefRowCount(2);
        input.setWrapText(true);
        input.getStyleClass().add("input");

        var sendBtn = new Button("发送");
        sendBtn.setDefaultButton(true);
        sendBtn.setOnAction(e -> send(input));

        var bottom = new VBox(6, input, sendBtn);
        bottom.getStyleClass().add("input-area");
        bottom.setPadding(new Insets(8));

        getChildren().setAll(header, output, bottom);
    }

    private void send(TextArea input) {
        var text = input.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        output.appendText("你: " + text.strip() + "\n");
        input.clear();
        // TODO: 接入 AI 服务，把回复流式写入 output
    }
}
