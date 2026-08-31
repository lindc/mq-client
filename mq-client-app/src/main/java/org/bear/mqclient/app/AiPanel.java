package org.bear.mqclient.app;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 右侧面板：上半部分显示内容（当前选中对象、后续 AI 返回的回答），下半部分是输入框 + 发送按钮。
 * <p>
 * 可收起到右侧边缘（只剩一条窄边条），点击边条上的按钮再拉出来。
 */
class AiPanel {

    private static final double EXPANDED_WIDTH = 320;
    private static final double COLLAPSED_WIDTH = 36;

    private final VBox content = new VBox(8);
    private final TextArea input = new TextArea();
    private final VBox expandedView = new VBox(10);
    private final VBox collapsedStrip = new VBox(6);
    private final StackPane container = new StackPane(expandedView, collapsedStrip);

    private Consumer<Boolean> onCollapsedChange;
    private boolean collapsed;

    AiPanel() {
        Label title = new Label("AI 助手");
        title.getStyleClass().add(Styles.TITLE_4);

        Button collapse = new Button("»");
        collapse.getStyleClass().add(Styles.BUTTON_ICON);
        collapse.setOnAction(e -> setCollapsed(true));

        HBox titleRow = new HBox(8, title, spacer(), collapse);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        content.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        input.setPromptText("描述你要查询的内容…");
        input.setPrefRowCount(3);

        Button send = new Button("发送");
        send.getStyleClass().add(Styles.ACCENT);
        send.setOnAction(e -> send());

        HBox footer = new HBox(8, spacer(), send);
        footer.setAlignment(Pos.CENTER_RIGHT);

        expandedView.getChildren().setAll(titleRow, scroll, input, footer);
        expandedView.setPadding(new Insets(12));

        buildCollapsedStrip();
        setCollapsed(false);
    }

    private void buildCollapsedStrip() {
        Button expand = new Button("«");
        expand.getStyleClass().add(Styles.BUTTON_ICON);
        expand.setOnAction(e -> setCollapsed(false));

        VBox verticalText = new VBox(2);
        verticalText.setAlignment(Pos.TOP_CENTER);
        for (char c : "AI 助手".toCharArray()) {
            Label letter = new Label(String.valueOf(c));
            letter.getStyleClass().addAll(Styles.TEXT_CAPTION, Styles.TEXT_MUTED);
            verticalText.getChildren().add(letter);
        }

        collapsedStrip.getChildren().setAll(expand, verticalText);
        collapsedStrip.setAlignment(Pos.TOP_CENTER);
        collapsedStrip.setPadding(new Insets(8, 0, 8, 0));
    }

    Node getView() {
        return container;
    }

    boolean isCollapsed() {
        return collapsed;
    }

    void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        expandedView.setVisible(!collapsed);
        expandedView.setManaged(!collapsed);
        collapsedStrip.setVisible(collapsed);
        collapsedStrip.setManaged(collapsed);

        container.setPrefWidth(collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH);
        container.setMinWidth(collapsed ? COLLAPSED_WIDTH : 260);

        if (onCollapsedChange != null) {
            onCollapsedChange.accept(collapsed);
        }
    }

    void setOnCollapsedChange(Consumer<Boolean> onCollapsedChange) {
        this.onCollapsedChange = onCollapsedChange;
    }

    /** 在上方内容区追加一条，后续接入 AI 服务时替换成模型返回即可。 */
    void show(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(Styles.TEXT_SMALL);

        StackPane bubble = new StackPane(label);
        bubble.getStyleClass().addAll(Styles.BG_SUBTLE, Styles.ROUNDED);
        bubble.setPadding(new Insets(8));

        content.getChildren().add(bubble);
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        input.clear();
        show("你：" + text);
        show("AI：服务未接入，这里将展示模型返回内容。");
    }

    private static Spacer spacer() {
        Spacer spacer = new Spacer();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
