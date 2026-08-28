package org.bear.mqclient.rabbitmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.model.MQMessage;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.util.I18n;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * RabbitMQ 主操作面板。
 * <p>
 * 包含多个 Tab：Queue、Exchange、Publish、Consume，完全由 RabbitMQ 控制。
 */
public class RabbitMQMainPanel extends VBox {

    private final TabPane tabPane;
    private final MQService service;
    private final MQConnectionConfig config;

    public RabbitMQMainPanel(MQConnectionConfig config, MQService service) {
        this.config = config;
        this.service = service;

        this.tabPane = new TabPane();
        this.tabPane.setTabMinWidth(100);

        // ── Queue 管理 Tab ──
        Tab queueTab = new Tab("Queue");
        queueTab.setClosable(false);
        queueTab.setContent(new QueuePanel(service));
        tabPane.getTabs().add(queueTab);

        // ── Exchange 管理 Tab ──
        Tab exchangeTab = new Tab("Exchange");
        exchangeTab.setClosable(false);
        exchangeTab.setContent(new Label(I18n.get("rabbitmq.exchangePanel")));

        // ── Binding 管理 Tab ──
        Tab bindingTab = new Tab("Binding");
        bindingTab.setClosable(false);
        bindingTab.setContent(new Label(I18n.get("rabbitmq.bindingPanel")));

        // ── Publish Tab ──
        Tab publishTab = new Tab(I18n.get("rabbitmq.publish"));
        publishTab.setClosable(false);
        publishTab.setContent(new PublishPanel(service));

        // ── Consume Tab ──
        Tab consumeTab = new Tab(I18n.get("common.consume"));
        consumeTab.setClosable(false);
        consumeTab.setContent(new Label(I18n.get("rabbitmq.consumePanel")));

        tabPane.getTabs().addAll(exchangeTab, bindingTab, publishTab, consumeTab);
        getChildren().add(tabPane);
    }

    /** 返回该面板对应的菜单项 */
    public List<Menu> getMenus() {
        Menu rabbitMenu = new Menu("RabbitMQ");
        rabbitMenu.getItems().addAll(
                new MenuItem(I18n.get("rabbitmq.declareQueue")),
                new MenuItem(I18n.get("rabbitmq.declareExchange")),
                new MenuItem(I18n.get("rabbitmq.clearQueue")),
                new SeparatorMenuItem(),
                new MenuItem(I18n.get("rabbitmq.connectionStatus"))
        );
        return List.of(rabbitMenu);
    }

    public void cleanup() {
        // 清理订阅等资源
    }

    // ── 内部面板（示例） ──

    /** Queue 浏览面板（示例实现） */
    static class QueuePanel extends VBox {
        QueuePanel(MQService service) {
            ListView<String> queueList = new ListView<>();
            queueList.getItems().addAll(I18n.get("rabbitmq.sampleQueues"));

            Button refreshBtn = new Button(I18n.get("rabbitmq.refreshQueues"));
            refreshBtn.setOnAction(e -> {
                service.listDestinations().thenAccept(dests ->
                        Platform.runLater(() -> {
                            queueList.getItems().clear();
                            dests.forEach(d ->
                                queueList.getItems().add(d.getName() + "  [" + d.getType() + "]")
                            );
                        })
                );
            });

            getChildren().addAll(refreshBtn, queueList);
        }
    }

    /** 消息发布面板（示例实现） */
    static class PublishPanel extends VBox {
        PublishPanel(MQService service) {
            TextField targetField = new TextField();
            targetField.setPromptText(I18n.get("common.targetQueuePrompt"));

            TextArea bodyArea = new TextArea();
            bodyArea.setPromptText(I18n.get("common.bodyPromptEllipsis"));
            bodyArea.setPrefRowCount(10);

            Button sendBtn = new Button(I18n.get("common.send"));
            sendBtn.setOnAction(e -> {
                MQMessage msg = new MQMessage();
                msg.setPayloadText(bodyArea.getText());
                msg.setContentType("text/plain");
                service.sendMessage(targetField.getText(), msg)
                        .thenRun(() -> Platform.runLater(() ->
                                bodyArea.clear()
                        ));
            });

            getChildren().addAll(
                    new Label(I18n.get("common.target")), targetField,
                    new Label(I18n.get("common.messageBody")), bodyArea,
                    sendBtn
            );
        }
    }
}
