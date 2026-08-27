package io.mqclient.core.ui;

import io.mqclient.core.model.BodyEncoding;
import io.mqclient.core.model.MQMessage;
import io.mqclient.core.spi.MessageDetailViewFactory;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 消息详情容器 — core 提供统一框架，内容由各 MQ 的 factory 填充。
 * <p>
 * 包含两个 Tab：
 * - Headers：调用 MessageDetailViewFactory.createHeaderPane()
 * - Body：  调用 MessageDetailViewFactory.createBodyPane()
 * <p>
 * Body Tab 右上角支持编码格式切换（如 RAW / TEXT / JSON / Hex）
 */
public class MessageDetailContainer extends VBox {

    private final TabPane tabPane;
    private final MessageDetailViewFactory factory;
    private final MQMessage message;

    public MessageDetailContainer(MessageDetailViewFactory factory, MQMessage message) {
        this.factory = factory;
        this.message = message;
        this.tabPane = new TabPane();

        // Headers Tab
        Tab headerTab = new Tab("Headers", factory.createHeaderPane(message));
        headerTab.setClosable(false);

        // Body Tab
        Tab bodyTab = new Tab("Body");
        bodyTab.setClosable(false);
        buildBodyTab(bodyTab);

        tabPane.getTabs().addAll(headerTab, bodyTab);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        getChildren().add(tabPane);
    }

    private void buildBodyTab(Tab bodyTab) {
        VBox content = new VBox(4);

        // 编码切换工具栏
        List<BodyEncoding> encodings = factory.getSupportedEncodings();
        if (encodings.size() > 1) {
            HBox toolbar = new HBox(4);
            toolbar.setStyle("-fx-padding: 4; -fx-background-color: #f5f5f5;");
            ToggleGroup group = new ToggleGroup();
            for (BodyEncoding enc : encodings) {
                ToggleButton btn = new ToggleButton(enc.getDisplayName());
                btn.setToggleGroup(group);
                btn.setUserData(enc);
                btn.setOnAction(e -> updateBody(group));
                toolbar.getChildren().add(btn);
                if (enc == BodyEncoding.TEXT || enc == BodyEncoding.JSON) {
                    btn.setSelected(true); // 默认选第一个文本类格式
                }
            }
            content.getChildren().add(toolbar);
        }

        // Body 内容面板
        StackPane bodyPane = new StackPane();
        bodyPane.getChildren().add(factory.createBodyPane(message));
        VBox.setVgrow(bodyPane, Priority.ALWAYS);
        content.getChildren().add(bodyPane);

        bodyTab.setContent(content);
    }

    @SuppressWarnings("unchecked")
    private void updateBody(ToggleGroup group) {
        // 由 MQ 的 createBodyPane() 决定是否支持动态切换
        // 简单实现：重新创建 body pane
        ToggleButton sel = (ToggleButton) group.getSelectedToggle();
        if (sel == null) return;
        BodyEncoding encoding = (BodyEncoding) sel.getUserData();

        // 通知 MQ factory 切换编码（如果有 setEncoding 的话）
        if (factory instanceof MessageDetailViewFactory f) {
            try {
                var method = factory.getClass().getMethod("setEncoding", BodyEncoding.class);
                method.invoke(factory, encoding);
            } catch (Exception ignored) {
                // 不支持动态切换，重新 createBodyPane
            }
        }

        // 刷新 body 内容
        Tab bodyTab = tabPane.getTabs().get(1);
        VBox vbox = (VBox) bodyTab.getContent();
        StackPane pane = (StackPane) vbox.getChildren().get(vbox.getChildren().size() - 1);
        pane.getChildren().setAll(factory.createBodyPane(message));
    }
}
