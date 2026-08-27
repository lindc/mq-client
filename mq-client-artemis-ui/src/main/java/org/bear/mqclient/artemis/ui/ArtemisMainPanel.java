package org.bear.mqclient.artemis.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.model.MQMessage;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.ui.MessageDetailContainer;
import javafx.application.Platform;

import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

/**
 * Artemis 主操作面板。
 * <p>
 * Tab: Queue / Topic / Listener / 发送
 */
public class ArtemisMainPanel extends VBox {

    private final TabPane tabPane;
    private final MQService service;
    private final MQConnectionConfig config;

    public ArtemisMainPanel(MQConnectionConfig config, MQService service) {
        this.config = config;
        this.service = service;
        this.tabPane = new TabPane();
        tabPane.setTabMinWidth(100);

        Tab queueTab = new Tab("Queue");
        queueTab.setClosable(false);
        queueTab.setContent(new QueuePanel(service));
        tabPane.getTabs().add(queueTab);

        Tab topicTab = new Tab("Topic");
        topicTab.setClosable(false);
        topicTab.setContent(new TopicPanel(service));
        tabPane.getTabs().add(topicTab);

        Tab listenerTab = new Tab("Listener");
        listenerTab.setClosable(false);
        listenerTab.setContent(new ListenerPanel(service));
        tabPane.getTabs().add(listenerTab);

        Tab sendTab = new Tab("发送");
        sendTab.setClosable(false);
        sendTab.setContent(new SendPanel(service));
        tabPane.getTabs().add(sendTab);

        getChildren().add(tabPane);
    }

    public List<Menu> getMenus() {
        Menu menu = new Menu("Artemis");
        menu.getItems().addAll(
                new MenuItem("创建队列..."),
                new MenuItem("创建主题..."),
                new SeparatorMenuItem(),
                new MenuItem("连接状态")
        );
        return List.of(menu);
    }

    public void cleanup() {}

    // ═══════════════════════════════════════
    //  Queue 面板
    // ═══════════════════════════════════════

    static class QueuePanel extends VBox {
        private final TextField queueField;
        private final TableView<MQMessage> table;
        private final ObservableList<MQMessage> messages;
        private final MQService service;

        QueuePanel(MQService svc) {
            this.service = svc;
            this.messages = FXCollections.observableArrayList();

            HBox topBar = new HBox(6);
            topBar.setPadding(new Insets(8));
            queueField = new TextField();
            queueField.setPromptText("输入队列名...");
            HBox.setHgrow(queueField, Priority.ALWAYS);
            Button browseBtn = new Button("浏览");
            Button consumeBtn = new Button("消费");
            topBar.getChildren().addAll(new Label("队列:"), queueField, browseBtn, consumeBtn);

            table = new TableView<>(messages);

            TableColumn<MQMessage, String> idCol = new TableColumn<>("MsgId");
            idCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("Artemis.messageId"))));
            idCol.setPrefWidth(80);

            TableColumn<MQMessage, String> priCol = new TableColumn<>("Pri");
            priCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("Artemis.priority"))));
            priCol.setPrefWidth(40);

            TableColumn<MQMessage, String> bodyCol = new TableColumn<>("Body");
            bodyCol.setCellValueFactory(d -> new SimpleStringProperty(
                    d.getValue().getPayloadText()));
            bodyCol.setPrefWidth(400);

            TableColumn<MQMessage, String> tsCol = new TableColumn<>("Timestamp");
            tsCol.setCellValueFactory(d -> new SimpleStringProperty(
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS")
                            .format(new java.util.Date(d.getValue().getTimestamp()))));
            tsCol.setPrefWidth(100);

            table.getColumns().addAll(idCol, priCol, bodyCol, tsCol);

            VBox detailArea = new VBox(4);
            SplitPane split = new SplitPane(table, detailArea);
            split.setOrientation(javafx.geometry.Orientation.VERTICAL);
            split.setDividerPositions(0.5);
            VBox.setVgrow(split, Priority.ALWAYS);

            table.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                detailArea.getChildren().clear();
                if (sel != null) {
                    detailArea.getChildren().add(new MessageDetailContainer(
                            new ArtemisMessageDetailFactory(), sel));
                }
            });

            browseBtn.setOnAction(e -> browse());
            consumeBtn.setOnAction(e -> consume());

            getChildren().addAll(topBar, split);
        }

        private void browse() {
            String q = queueField.getText().trim();
            if (q.isEmpty()) return;
            service.browseMessages(q, 30).thenAccept(list ->
                    Platform.runLater(() -> messages.setAll(list)));
        }

        private void consume() {
            String q = queueField.getText().trim();
            if (q.isEmpty()) return;
            service.consumeMessages(q, 10, 3000).thenAccept(list ->
                    Platform.runLater(() -> messages.setAll(list)));
        }
    }

    // ═══════════════════════════════════════
    //  Topic 面板
    // ═══════════════════════════════════════

    static class TopicPanel extends VBox {
        TopicPanel(MQService service) {
            TextField topicField = new TextField();
            topicField.setPromptText("Topic 名 (如 news.> )");

            TextArea outputArea = new TextArea();
            outputArea.setEditable(false);
            outputArea.setFont(Font.font("Consolas", 12));
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            Button browseBtn = new Button("浏览");
            browseBtn.setOnAction(e -> {
                String t = topicField.getText().trim();
                if (t.isEmpty()) return;
                service.browseMessages(t, 30).thenAccept(list ->
                        Platform.runLater(() -> {
                            outputArea.clear();
                            list.forEach(msg ->
                                    outputArea.appendText("[" + msg.getNativeField("Artemis.messageId")
                                            + "] " + msg.getPayloadText() + "\n"));
                        }));
            });

            getChildren().addAll(
                    new HBox(6, new Label("Topic:"), topicField, browseBtn),
                    outputArea
            );
        }
    }

    // ═══════════════════════════════════════
    //  Listener 面板
    // ═══════════════════════════════════════

    static class ListenerPanel extends VBox {
        ListenerPanel(MQService service) {
            TextField destField = new TextField();
            destField.setPromptText("队列名 / Topic 名");

            TextArea outputArea = new TextArea();
            outputArea.setEditable(false);
            outputArea.setFont(Font.font("Consolas", 12));
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            Button listenBtn = new Button("开始监听");
            listenBtn.setOnAction(e -> {
                String d = destField.getText().trim();
                if (d.isEmpty()) return;
                service.subscribe(d, msg -> Platform.runLater(() ->
                        outputArea.appendText("[" + java.time.LocalTime.now()
                                + "] " + msg.getPayloadText() + "\n")));
            });

            getChildren().addAll(
                    new HBox(6, new Label("目的地:"), destField, listenBtn),
                    outputArea
            );
        }
    }

    // ═══════════════════════════════════════
    //  发送面板
    // ═══════════════════════════════════════

    static class SendPanel extends VBox {
        SendPanel(MQService service) {
            setPadding(new Insets(12));
            setSpacing(8);

            TextField targetField = new TextField();
            targetField.setPromptText("目标队列/主题");

            TextField propField = new TextField();
            propField.setPromptText("属性 (key=val, 可选)");

            TextArea bodyArea = new TextArea();
            bodyArea.setPromptText("消息内容");
            bodyArea.setPrefRowCount(12);

            Button sendBtn = new Button("发送");
            sendBtn.setOnAction(e -> {
                MQMessage msg = new MQMessage();
                msg.setPayloadText(bodyArea.getText());
                String prop = propField.getText().trim();
                if (!prop.isEmpty()) {
                    String[] parts = prop.split("=", 2);
                    if (parts.length == 2) {
                        msg.setUserProperty(parts[0], parts[1]);
                    }
                }
                service.sendMessage(targetField.getText().trim(), msg)
                        .thenRun(() -> Platform.runLater(bodyArea::clear));
            });

            getChildren().addAll(
                    new Label("目标:"), targetField,
                    new Label("属性:"), propField,
                    new Label("消息体:"), bodyArea,
                    sendBtn
            );
        }
    }
}
