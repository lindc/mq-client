package io.mqclient.rabbitmq.ui;

import io.mqclient.core.model.MQConnectionConfig;
import io.mqclient.core.model.MQMessage;
import io.mqclient.core.service.MQService;
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
        exchangeTab.setContent(new Label("Exchange 管理面板 — 待实现"));

        // ── Binding 管理 Tab ──
        Tab bindingTab = new Tab("Binding");
        bindingTab.setClosable(false);
        bindingTab.setContent(new Label("Binding 管理面板 — 待实现"));

        // ── Publish Tab ──
        Tab publishTab = new Tab("发布");
        publishTab.setClosable(false);
        publishTab.setContent(new PublishPanel(service));

        // ── Consume Tab ──
        Tab consumeTab = new Tab("消费");
        consumeTab.setClosable(false);
        consumeTab.setContent(new Label("消息消费面板 — 待实现"));

        tabPane.getTabs().addAll(exchangeTab, bindingTab, publishTab, consumeTab);
        getChildren().add(tabPane);
    }

    /** 返回该面板对应的菜单项 */
    public List<Menu> getMenus() {
        Menu rabbitMenu = new Menu("RabbitMQ");
        rabbitMenu.getItems().addAll(
                new MenuItem("声明队列..."),
                new MenuItem("声明 Exchange..."),
                new MenuItem("清空队列"),
                new SeparatorMenuItem(),
                new MenuItem("Connection 状态")
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
            queueList.getItems().addAll("示例队列 — 连接后自动加载");

            Button refreshBtn = new Button("刷新队列列表");
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
            targetField.setPromptText("目标队列名");

            TextArea bodyArea = new TextArea();
            bodyArea.setPromptText("消息内容...");
            bodyArea.setPrefRowCount(10);

            Button sendBtn = new Button("发送");
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
                    new Label("目标:"), targetField,
                    new Label("消息体:"), bodyArea,
                    sendBtn
            );
        }
    }
}
