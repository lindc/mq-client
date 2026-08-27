package org.bear.mqclient.rocketmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.model.MQMessage;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.ui.MessageDetailContainer;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.List;

/**
 * RocketMQ 主操作面板。
 * <p>
 * Tab: Topics / Consume / Produce
 */
public class RocketMQMainPanel extends VBox {

    private final TabPane tabPane;
    private final MQService service;

    public RocketMQMainPanel(MQConnectionConfig config, MQService service) {
        this.service = service;
        this.tabPane = new TabPane();
        tabPane.setTabMinWidth(100);

        Tab topicsTab = new Tab("Topics");
        topicsTab.setClosable(false);
        topicsTab.setContent(new TopicsPanel(service));
        tabPane.getTabs().add(topicsTab);

        Tab consumeTab = new Tab("Consume");
        consumeTab.setClosable(false);
        consumeTab.setContent(new ConsumePanel(service));
        tabPane.getTabs().add(consumeTab);

        Tab produceTab = new Tab("Produce");
        produceTab.setClosable(false);
        produceTab.setContent(new ProducePanel(service));
        tabPane.getTabs().add(produceTab);

        getChildren().add(tabPane);
    }

    public List<Menu> getMenus() {
        Menu menu = new Menu("RocketMQ");
        menu.getItems().addAll(
                new MenuItem("刷新 Topic"),
                new MenuItem("查看消费者状态..."),
                new SeparatorMenuItem(),
                new MenuItem("连接状态")
        );
        return List.of(menu);
    }

    public void cleanup() {}

    // ═══════════════════════════════════════
    //  Topics 面板
    // ═══════════════════════════════════════

    static class TopicsPanel extends VBox {
        private final ListView<String> topicList;
        private final TableView<MQMessage> messageTable;
        private final ObservableList<MQMessage> messages;
        private final MQService service;

        TopicsPanel(MQService svc) {
            this.service = svc;
            this.messages = FXCollections.observableArrayList();

            topicList = new ListView<>();
            topicList.setPrefWidth(200);
            topicList.setPlaceholder(new Label("加载中..."));

            Button refreshBtn = new Button("刷新");
            VBox leftSide = new VBox(4, refreshBtn, topicList);
            leftSide.setPadding(new Insets(8));

            messageTable = new TableView<>(messages);
            messageTable.setTableMenuButtonVisible(true);

            TableColumn<MQMessage, String> msgIdCol = new TableColumn<>("MsgId");
            msgIdCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getMessageId()));
            msgIdCol.setPrefWidth(200);

            TableColumn<MQMessage, String> qIdCol = new TableColumn<>("QId");
            qIdCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("RocketMQ.queueId"))));
            qIdCol.setPrefWidth(50);

            TableColumn<MQMessage, String> offsetCol = new TableColumn<>("Offset");
            offsetCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("RocketMQ.queueOffset"))));
            offsetCol.setPrefWidth(80);

            TableColumn<MQMessage, String> bodyCol = new TableColumn<>("Body");
            bodyCol.setCellValueFactory(d -> new SimpleStringProperty(
                    d.getValue().getPayloadText()));
            bodyCol.setPrefWidth(300);

            messageTable.getColumns().addAll(msgIdCol, qIdCol, offsetCol, bodyCol);

            VBox detailArea = new VBox(4);
            SplitPane split = new SplitPane(messageTable, detailArea);
            split.setOrientation(javafx.geometry.Orientation.VERTICAL);
            split.setDividerPositions(0.5);
            VBox.setVgrow(split, Priority.ALWAYS);

            messageTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                detailArea.getChildren().clear();
                if (sel != null) {
                    detailArea.getChildren().add(new MessageDetailContainer(
                            new RocketMQMessageDetailFactory(), sel));
                }
            });

            topicList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                if (sel != null) browseTopic(sel);
            });

            refreshBtn.setOnAction(e -> refreshTopics());

            SplitPane mainSplit = new SplitPane(leftSide, split);
            mainSplit.setDividerPositions(0.2);
            getChildren().add(mainSplit);

            refreshTopics();
        }

        private void refreshTopics() {
            service.listDestinations().thenAccept(dests ->
                    Platform.runLater(() -> {
                        topicList.getItems().clear();
                        dests.forEach(d -> topicList.getItems().add(d.getName()));
                    }));
        }

        private void browseTopic(String topic) {
            service.browseMessages(topic, 30).thenAccept(list ->
                    Platform.runLater(() -> messages.setAll(list)));
        }
    }

    // ═══════════════════════════════════════
    //  Consume 面板
    // ═══════════════════════════════════════

    static class ConsumePanel extends VBox {
        ConsumePanel(MQService service) {
            setPadding(new Insets(12));
            setSpacing(8);

            TextField topicField = new TextField();
            topicField.setPromptText("输入 Topic");
            TextField countField = new TextField("10");
            countField.setPrefWidth(60);

            TextArea outputArea = new TextArea();
            outputArea.setEditable(false);
            outputArea.setFont(Font.font("Consolas", 12));
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            Button consumeBtn = new Button("消费");
            consumeBtn.setOnAction(e -> {
                String topic = topicField.getText().trim();
                if (topic.isEmpty()) return;
                int count = Integer.parseInt(countField.getText().trim());
                outputArea.clear();
                service.consumeMessages(topic, count, 5000).thenAccept(list ->
                        Platform.runLater(() -> list.forEach(msg ->
                                outputArea.appendText("[" + msg.getNativeField("RocketMQ.queueId")
                                        + "/" + msg.getNativeField("RocketMQ.queueOffset") + "] "
                                        + msg.getPayloadText() + "\n"))));
            });

            Button subscribeBtn = new Button("订阅");
            subscribeBtn.setOnAction(e -> {
                String topic = topicField.getText().trim();
                if (topic.isEmpty()) return;
                service.subscribe(topic, msg -> Platform.runLater(() ->
                        outputArea.appendText("[SUB] " + msg.getPayloadText() + "\n")));
            });

            HBox topBar = new HBox(6, new Label("Topic:"), topicField,
                    new Label("条数:"), countField, consumeBtn, subscribeBtn);
            getChildren().addAll(topBar, outputArea);
        }
    }

    // ═══════════════════════════════════════
    //  Produce 面板
    // ═══════════════════════════════════════

    static class ProducePanel extends VBox {
        ProducePanel(MQService service) {
            setPadding(new Insets(12));
            setSpacing(8);

            TextField topicField = new TextField();
            topicField.setPromptText("目标 Topic");

            TextField tagField = new TextField();
            tagField.setPromptText("Tag (可选)");

            TextField keyField = new TextField();
            keyField.setPromptText("Key (可选)");

            TextField delayField = new TextField();
            delayField.setPromptText("Delay Level (可选, 1-18)");

            TextArea bodyArea = new TextArea();
            bodyArea.setPromptText("消息内容");
            bodyArea.setPrefRowCount(10);

            Button sendBtn = new Button("发送");
            sendBtn.setOnAction(e -> {
                MQMessage msg = new MQMessage();
                msg.setPayloadText(bodyArea.getText());
                String tag = tagField.getText().trim();
                if (!tag.isEmpty()) msg.setNativeField("rocketTag", tag);
                String key = keyField.getText().trim();
                if (!key.isEmpty()) msg.setNativeField("rocketKey", key);
                String delay = delayField.getText().trim();
                if (!delay.isEmpty()) msg.setNativeField("delayLevel", delay);
                service.sendMessage(topicField.getText().trim(), msg)
                        .thenRun(() -> Platform.runLater(bodyArea::clear));
            });

            getChildren().addAll(
                    new Label("Topic:"), topicField,
                    new Label("Tag:"), tagField,
                    new Label("Key:"), keyField,
                    new Label("Delay Level:"), delayField,
                    new Label("消息体:"), bodyArea,
                    sendBtn
            );
        }
    }
}
