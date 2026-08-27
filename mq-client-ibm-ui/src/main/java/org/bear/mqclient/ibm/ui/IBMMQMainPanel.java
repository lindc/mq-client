package org.bear.mqclient.ibm.ui;

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

import java.util.List;

/**
 * IBM MQ 主操作面板。
 * <p>
 * Tab: Queue / Topic / Listener / 发送
 */
public class IBMMQMainPanel extends VBox {

    private final TabPane tabPane;
    private final MQService service;
    private final MQConnectionConfig config;

    public IBMMQMainPanel(MQConnectionConfig config, MQService service) {
        this.config = config;
        this.service = service;
        this.tabPane = new TabPane();
        tabPane.setTabMinWidth(100);

        // ── Queue Tab ──
        Tab queueTab = new Tab("Queue");
        queueTab.setClosable(false);
        queueTab.setContent(new QueueBrowserPanel(service));
        tabPane.getTabs().add(queueTab);

        // ── Topic Tab ──
        Tab topicTab = new Tab("Topic");
        topicTab.setClosable(false);
        topicTab.setContent(new TopicPanel(service));
        tabPane.getTabs().add(topicTab);

        // ── Listener Tab ──
        Tab listenerTab = new Tab("Listener");
        listenerTab.setClosable(false);
        listenerTab.setContent(new ListenerPanel(service));
        tabPane.getTabs().add(listenerTab);

        // ── 发送 Tab ──
        Tab sendTab = new Tab("发送");
        sendTab.setClosable(false);
        sendTab.setContent(new SendPanel(service));
        tabPane.getTabs().add(sendTab);

        getChildren().add(tabPane);
    }

    public List<Menu> getMenus() {
        Menu ibmMenu = new Menu("IBM MQ");
        ibmMenu.getItems().addAll(
                new MenuItem("清除队列..."),
                new MenuItem("创建队列..."),
                new SeparatorMenuItem(),
                new MenuItem("Channel 状态..."),
                new MenuItem("连接状态")
        );
        return List.of(ibmMenu);
    }

    public void cleanup() {
        // 清理资源
    }

    // ═══════════════════════════════════════════════
    //  Queue 浏览面板
    // ═══════════════════════════════════════════════

    static class QueueBrowserPanel extends VBox {
        private final TextField queueNameField;
        private final TableView<MQMessage> messageTable;
        private final ObservableList<MQMessage> messages;
        private final MQService service;

        QueueBrowserPanel(MQService svc) {
            this.service = svc;
            this.messages = FXCollections.observableArrayList();

            // 输入 + 按钮
            HBox topBar = new HBox(6);
            topBar.setPadding(new Insets(8));
            queueNameField = new TextField();
            queueNameField.setPromptText("输入队列名...");
            HBox.setHgrow(queueNameField, Priority.ALWAYS);
            Button browseBtn = new Button("浏览");
            Button getBtn = new Button("取消息");
            topBar.getChildren().addAll(new Label("队列:"), queueNameField, browseBtn, getBtn);

            // 消息表格
            messageTable = new TableView<>(messages);
            messageTable.setTableMenuButtonVisible(true);

            TableColumn<MQMessage, String> msgIdCol = new TableColumn<>("MsgId");
            msgIdCol.setCellValueFactory(d -> new SimpleStringProperty(abbr(d.getValue().getMessageId(), 16)));
            msgIdCol.setPrefWidth(120);

            TableColumn<MQMessage, String> formatCol = new TableColumn<>("Format");
            formatCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getContentType()));
            formatCol.setPrefWidth(80);

            TableColumn<MQMessage, String> expiryCol = new TableColumn<>("Expiry");
            expiryCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("MQMD.Expiry"))));
            expiryCol.setPrefWidth(60);

            TableColumn<MQMessage, String> priorityCol = new TableColumn<>("Pri");
            priorityCol.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().getNativeField("MQMD.Priority"))));
            priorityCol.setPrefWidth(40);

            TableColumn<MQMessage, String> sizeCol = new TableColumn<>("Size");
            sizeCol.setCellValueFactory(d -> {
                byte[] p = d.getValue().getPayload();
                return new SimpleStringProperty(p != null ? formatSize(p.length) : "0");
            });
            sizeCol.setPrefWidth(70);

            messageTable.getColumns().addAll(msgIdCol, formatCol, expiryCol, priorityCol, sizeCol);

            // 点击消息 → 底部分隔面板显示详情
            VBox detailArea = new VBox(4);
            SplitPane split = new SplitPane(messageTable, detailArea);
            split.setOrientation(javafx.geometry.Orientation.VERTICAL);
            split.setDividerPositions(0.6);
            VBox.setVgrow(split, Priority.ALWAYS);

            messageTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                detailArea.getChildren().clear();
                if (sel != null) {
                    IBMMQMessageDetailFactory factory = new IBMMQMessageDetailFactory();
                    detailArea.getChildren().add(new MessageDetailContainer(factory, sel));
                }
            });

            // 事件
            browseBtn.setOnAction(e -> browse());
            getBtn.setOnAction(e -> consume());

            getChildren().addAll(topBar, split);
        }

        private void browse() {
            String qName = queueNameField.getText().trim();
            if (qName.isEmpty()) return;
            service.browseMessages(qName, 50).thenAccept(list ->
                    Platform.runLater(() -> messages.setAll(list)));
        }

        private void consume() {
            String qName = queueNameField.getText().trim();
            if (qName.isEmpty()) return;
            service.consumeMessages(qName, 10, 3000).thenAccept(list ->
                    Platform.runLater(() -> messages.setAll(list)));
        }
    }

    // ═══════════════════════════════════════════════
    //  Topic 面板
    // ═══════════════════════════════════════════════

    static class TopicPanel extends VBox {
        TopicPanel(MQService service) {
            TextField topicField = new TextField();
            topicField.setPromptText("输入主题名 (如 TOPIC://... )");

            Button subscribeBtn = new Button("订阅并监听");

            TextArea outputArea = new TextArea();
            outputArea.setEditable(false);
            outputArea.setPromptText("接收到的消息将显示在这里...");
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            subscribeBtn.setOnAction(e -> {
                String topic = topicField.getText().trim();
                if (topic.isEmpty()) return;
                service.subscribe(topic, msg -> Platform.runLater(() ->
                        outputArea.appendText("[" + java.time.LocalTime.now() + "] "
                                + msg.getPayloadText() + "\n")));
            });

            getChildren().addAll(
                    new HBox(6, new Label("主题:"), topicField, subscribeBtn),
                    new Label("消息输出:"),
                    outputArea
            );
        }
    }

    // ═══════════════════════════════════════════════
    //  Listener 面板
    // ═══════════════════════════════════════════════

    static class ListenerPanel extends VBox {
        private final ListView<String> listenerList;

        ListenerPanel(MQService service) {
            listenerList = new ListView<>();
            listenerList.setPlaceholder(new Label("尚未添加监听器"));

            HBox inputBar = new HBox(6);
            inputBar.setPadding(new Insets(8));
            TextField destField = new TextField();
            destField.setPromptText("队列名/主题名");
            HBox.setHgrow(destField, Priority.ALWAYS);
            Button listenBtn = new Button("开始监听");

            listenBtn.setOnAction(e -> {
                String dest = destField.getText().trim();
                if (dest.isEmpty()) return;
                listenerList.getItems().add("🟢 监听中: " + dest);
                service.subscribe(dest, msg -> Platform.runLater(() -> {
                    // 更新状态显示
                }));
            });

            inputBar.getChildren().addAll(new Label("目的地:"), destField, listenBtn);
            VBox.setVgrow(listenerList, Priority.ALWAYS);
            getChildren().addAll(inputBar, listenerList);
        }
    }

    // ═══════════════════════════════════════════════
    //  发送面板
    // ═══════════════════════════════════════════════

    static class SendPanel extends VBox {
        SendPanel(MQService service) {
            setPadding(new Insets(12));
            setSpacing(8);

            TextField targetField = new TextField();
            targetField.setPromptText("目标队列名");

            TextField correlIdField = new TextField();
            correlIdField.setPromptText("CorrelationId (Hex, 可选)");

            TextField formatField = new TextField("MQSTR");
            formatField.setPromptText("Format (MQSTR / MQBYTES)");

            TextArea bodyArea = new TextArea();
            bodyArea.setPromptText("消息内容...");
            bodyArea.setPrefRowCount(12);

            Button sendBtn = new Button("发送");
            sendBtn.setOnAction(e -> {
                MQMessage msg = new MQMessage();
                msg.setPayloadText(bodyArea.getText());
                String format = formatField.getText().trim();
                msg.setContentType(!format.isEmpty() ? format : "MQSTR");
                String corr = correlIdField.getText().trim();
                if (!corr.isEmpty()) msg.setCorrelationId(corr);

                service.sendMessage(targetField.getText().trim(), msg)
                        .thenRun(() -> Platform.runLater(() -> {
                            bodyArea.clear();
                            correlIdField.clear();
                        }));
            });

            getChildren().addAll(
                    new Label("目标队列:"), targetField,
                    new Label("CorrelationId:"), correlIdField,
                    new Label("Format:"), formatField,
                    new Label("消息体:"), bodyArea,
                    sendBtn
            );
        }
    }

    // ═══════════════════════════════════════════════
    //  辅助
    // ═══════════════════════════════════════════════

    private static String abbr(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
