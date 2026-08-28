package org.bear.mqclient.ibm.ui;

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
        Tab sendTab = new Tab(I18n.get("common.send"));
        sendTab.setClosable(false);
        sendTab.setContent(new SendPanel(service));
        tabPane.getTabs().add(sendTab);

        getChildren().add(tabPane);
    }

    public List<Menu> getMenus() {
        Menu ibmMenu = new Menu("IBM MQ");
        ibmMenu.getItems().addAll(
                new MenuItem(I18n.get("ibm.clearQueue")),
                new MenuItem(I18n.get("common.createQueue")),
                new SeparatorMenuItem(),
                new MenuItem(I18n.get("ibm.channelStatus")),
                new MenuItem(I18n.get("common.connectionStatus"))
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
            queueNameField.setPromptText(I18n.get("common.queueNamePrompt"));
            HBox.setHgrow(queueNameField, Priority.ALWAYS);
            Button browseBtn = new Button(I18n.get("common.browse"));
            Button getBtn = new Button(I18n.get("ibm.getMessages"));
            topBar.getChildren().addAll(new Label(I18n.get("common.queue")), queueNameField, browseBtn, getBtn);

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
            topicField.setPromptText(I18n.get("ibm.topicNamePrompt"));

            Button subscribeBtn = new Button(I18n.get("ibm.subscribeListen"));

            TextArea outputArea = new TextArea();
            outputArea.setEditable(false);
            outputArea.setPromptText(I18n.get("ibm.outputPrompt"));
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            subscribeBtn.setOnAction(e -> {
                String topic = topicField.getText().trim();
                if (topic.isEmpty()) return;
                service.subscribe(topic, msg -> Platform.runLater(() ->
                        outputArea.appendText("[" + java.time.LocalTime.now() + "] "
                                + msg.getPayloadText() + "\n")));
            });

            getChildren().addAll(
                    new HBox(6, new Label(I18n.get("common.topic")), topicField, subscribeBtn),
                    new Label(I18n.get("ibm.messageOutput")),
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
            listenerList.setPlaceholder(new Label(I18n.get("common.noListeners")));

            HBox inputBar = new HBox(6);
            inputBar.setPadding(new Insets(8));
            TextField destField = new TextField();
            destField.setPromptText(I18n.get("common.destinationPrompt"));
            HBox.setHgrow(destField, Priority.ALWAYS);
            Button listenBtn = new Button(I18n.get("common.startListening"));

            listenBtn.setOnAction(e -> {
                String dest = destField.getText().trim();
                if (dest.isEmpty()) return;
                listenerList.getItems().add(I18n.get("common.listening", dest));
                service.subscribe(dest, msg -> Platform.runLater(() -> {
                    // 更新状态显示
                }));
            });

            inputBar.getChildren().addAll(new Label(I18n.get("common.destination")), destField, listenBtn);
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
            targetField.setPromptText(I18n.get("common.targetQueuePrompt"));

            TextField correlIdField = new TextField();
            correlIdField.setPromptText(I18n.get("ibm.corrId"));

            TextField formatField = new TextField("MQSTR");
            formatField.setPromptText("Format (MQSTR / MQBYTES)");

            TextArea bodyArea = new TextArea();
            bodyArea.setPromptText(I18n.get("common.bodyPromptEllipsis"));
            bodyArea.setPrefRowCount(12);

            Button sendBtn = new Button(I18n.get("common.send"));
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
                    new Label(I18n.get("common.targetQueue")), targetField,
                    new Label("CorrelationId:"), correlIdField,
                    new Label("Format:"), formatField,
                    new Label(I18n.get("common.messageBody")), bodyArea,
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
