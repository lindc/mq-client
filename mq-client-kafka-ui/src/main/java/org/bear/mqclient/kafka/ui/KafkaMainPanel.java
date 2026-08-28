package org.bear.mqclient.kafka.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.model.MQMessage;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.ui.MessageDetailContainer;
import org.bear.mqclient.core.util.I18n;
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
 * Kafka 主操作面板。
 * <p>
 * Tab: Topics（浏览）/ Consume（消费）/ Produce（发送）
 */
public class KafkaMainPanel extends VBox {

    private final TabPane tabPane;
    private final MQService service;
    private final MQConnectionConfig config;

    public KafkaMainPanel(MQConnectionConfig config, MQService service) {
        this.config = config;
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
        Menu kafkaMenu = new Menu("Kafka");
        kafkaMenu.getItems().addAll(
                new MenuItem(I18n.get("kafka.refreshTopics")),
                new MenuItem(I18n.get("kafka.consumerGroups")),
                new SeparatorMenuItem(),
                new MenuItem(I18n.get("common.connectionStatus"))
        );
        return List.of(kafkaMenu);
    }

    public void cleanup() {}

    // ═══════════════════════════════════════
    //  Topics 浏览面板
    // ═══════════════════════════════════════

    static class TopicsPanel extends VBox {
        private final ListView<String> topicList;
        private final TableView<MQMessage> messageTable;
        private final ObservableList<MQMessage> messages;
        private final MQService service;
        private String selectedTopic;

        TopicsPanel(MQService svc) {
            this.service = svc;
            this.messages = FXCollections.observableArrayList();

            // 左：Topic 列表，右：消息
            topicList = new ListView<>();
            topicList.setPrefWidth(200);
            topicList.setPlaceholder(new Label(I18n.get("common.loading")));

            Button refreshBtn = new Button(I18n.get("common.refresh"));
            VBox leftSide = new VBox(4, refreshBtn, topicList);
            leftSide.setPadding(new Insets(8));

            // 消息表格
            messageTable = new TableView<>(messages);
            messageTable.setTableMenuButtonVisible(true);

            TableColumn<MQMessage, String> offsetCol = new TableColumn<>("Offset");
            offsetCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("offset"))));
            offsetCol.setPrefWidth(80);

            TableColumn<MQMessage, String> partitionCol = new TableColumn<>("Partition");
            partitionCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("partition"))));
            partitionCol.setPrefWidth(80);

            TableColumn<MQMessage, String> keyCol = new TableColumn<>("Key");
            keyCol.setCellValueFactory(d -> new SimpleStringProperty(
                    strField(d.getValue(), "kafkaKey")));
            keyCol.setPrefWidth(100);

            TableColumn<MQMessage, String> valueCol = new TableColumn<>("Value");
            valueCol.setCellValueFactory(d -> new SimpleStringProperty(
                    d.getValue().getPayloadText()));
            valueCol.setPrefWidth(300);

            TableColumn<MQMessage, String> timestampCol = new TableColumn<>("Timestamp");
            timestampCol.setCellValueFactory(d -> new SimpleStringProperty(
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS")
                            .format(new java.util.Date(d.getValue().getTimestamp()))));
            timestampCol.setPrefWidth(100);

            messageTable.getColumns().addAll(offsetCol, partitionCol, keyCol, valueCol, timestampCol);

            // 消息详情
            VBox detailArea = new VBox(4);
            SplitPane split = new SplitPane(messageTable, detailArea);
            split.setOrientation(javafx.geometry.Orientation.VERTICAL);
            split.setDividerPositions(0.5);
            VBox.setVgrow(split, Priority.ALWAYS);

            messageTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                detailArea.getChildren().clear();
                if (sel != null) {
                    detailArea.getChildren().add(new MessageDetailContainer(
                            new KafkaMessageDetailFactory(), sel));
                }
            });

            // 点击 topic → 浏览
            topicList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                if (sel != null) {
                    selectedTopic = sel;
                    browseTopic(sel);
                }
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
            topicField.setPromptText(I18n.get("kafka.topicNamePrompt"));
            TextField countField = new TextField("10");
            countField.setPrefWidth(60);

            TextArea outputArea = new TextArea();
            outputArea.setEditable(false);
            outputArea.setFont(Font.font("Consolas", 12));
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            Button consumeBtn = new Button(I18n.get("common.consume"));
            consumeBtn.setOnAction(e -> {
                String topic = topicField.getText().trim();
                if (topic.isEmpty()) return;
                int count = Integer.parseInt(countField.getText().trim());
                outputArea.clear();
                service.consumeMessages(topic, count, 5000).thenAccept(list ->
                        Platform.runLater(() -> {
                            for (MQMessage msg : list) {
                                outputArea.appendText("[" + msg.getNativeField("partition") + "/"
                                        + msg.getNativeField("offset") + "] "
                                        + msg.getPayloadText() + "\n");
                            }
                        }));
            });

            Button subscribeBtn = new Button(I18n.get("common.subscribeListen"));
            subscribeBtn.setOnAction(e -> {
                String topic = topicField.getText().trim();
                if (topic.isEmpty()) return;
                service.subscribe(topic, msg -> Platform.runLater(() ->
                        outputArea.appendText("[SUB] P" + msg.getNativeField("partition")
                                + "@O" + msg.getNativeField("offset") + " "
                                + msg.getPayloadText() + "\n")));
            });

            HBox topBar = new HBox(6, new Label("Topic:"), topicField,
                    new Label(I18n.get("common.count")), countField, consumeBtn, subscribeBtn);

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
            topicField.setPromptText(I18n.get("common.targetTopicPrompt"));

            TextField keyField = new TextField();
            keyField.setPromptText(I18n.get("common.keyOptional"));

            TextArea bodyArea = new TextArea();
            bodyArea.setPromptText(I18n.get("common.bodyPromptEllipsis"));
            bodyArea.setPrefRowCount(12);

            Button sendBtn = new Button(I18n.get("common.send"));
            sendBtn.setOnAction(e -> {
                MQMessage msg = new MQMessage();
                msg.setPayloadText(bodyArea.getText());
                String key = keyField.getText().trim();
                if (!key.isEmpty()) msg.setNativeField("kafkaKey", key);
                service.sendMessage(topicField.getText().trim(), msg)
                        .thenRun(() -> Platform.runLater(bodyArea::clear));
            });

            getChildren().addAll(
                    new Label("Topic:"), topicField,
                    new Label("Key:"), keyField,
                    new Label(I18n.get("common.messageBody")), bodyArea,
                    sendBtn
            );
        }
    }

    // ── 辅助 ──

    private static String strField(MQMessage msg, String key) {
        Object v = msg.getNativeField(key);
        return v != null ? String.valueOf(v) : "";
    }
}
