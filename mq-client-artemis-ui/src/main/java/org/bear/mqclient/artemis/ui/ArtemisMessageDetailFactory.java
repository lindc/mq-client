package org.bear.mqclient.artemis.ui;

import org.bear.mqclient.core.model.BodyEncoding;
import org.bear.mqclient.core.model.MQMessage;
import org.bear.mqclient.core.spi.MessageDetailViewFactory;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Artemis 消息详情展示工厂。
 */
public class ArtemisMessageDetailFactory implements MessageDetailViewFactory {

    @Override
    public String getType() { return "ARTEMIS"; }

    @Override
    public Node createHeaderPane(MQMessage message) {
        TabPane tabs = new TabPane();
        tabs.setTabMinWidth(80);

        tabs.getTabs().add(new Tab("Artemis 属性", buildGrid(message,
                "Artemis.messageId", "Artemis.priority",
                "Artemis.expiration", "Artemis.durable",
                "Artemis.largeMessage", "Artemis.type",
                "Artemis.timestamp", "Artemis.userID",
                "Artemis.address", "Artemis.replyTo"
        )));

        tabs.getTabs().add(new Tab("通用", timeGrid(message)));

        tabs.getTabs().add(new Tab("属性", propsGrid(message)));

        return tabs;
    }

    @Override
    public Node createBodyPane(MQMessage message) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setFont(Font.font("Consolas", 13));
        area.setWrapText(true);
        area.setText(message.getPayloadText());
        VBox box = new VBox(area);
        VBox.setVgrow(area, Priority.ALWAYS);
        return box;
    }

    @Override
    public List<BodyEncoding> getSupportedEncodings() {
        return List.of(BodyEncoding.TEXT, BodyEncoding.HEX, BodyEncoding.JSON);
    }

    // ── 辅助 ──

    private Node buildGrid(MQMessage msg, String... keys) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(5);
        grid.setPadding(new Insets(12));
        int row = 0;
        for (String key : keys) {
            Object val = msg.getNativeField(key);
            grid.addRow(row++, label(key.replace("Artemis.", "")),
                    value(val != null ? String.valueOf(val) : ""));
        }
        return grid;
    }

    private Node timeGrid(MQMessage msg) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(5);
        grid.setPadding(new Insets(12));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        grid.addRow(0, label("Timestamp"), value(sdf.format(new Date(msg.getTimestamp()))));
        grid.addRow(1, label("MessageId"), value(msg.getMessageId()));
        grid.addRow(2, label("CorrelationId"), value(msg.getCorrelationId()));
        grid.addRow(3, label("ContentType"), value(msg.getContentType()));
        grid.addRow(4, label("ReplyTo"), value(msg.getReplyTo()));
        return grid;
    }

    private Node propsGrid(MQMessage msg) {
        VBox box = new VBox(2);
        box.setPadding(new Insets(8));
        Map<String, Object> props = msg.getUserProperties();
        if (props.isEmpty()) {
            box.getChildren().add(new Label("(无额外属性)"));
            return box;
        }
        for (Map.Entry<String, Object> e : props.entrySet()) {
            HBox row = new HBox(8);
            Label keyLbl = new Label(e.getKey());
            keyLbl.setStyle("-fx-font-weight: bold; -fx-min-width: 120;");
            row.getChildren().addAll(keyLbl, new Label(String.valueOf(e.getValue())));
            box.getChildren().add(row);
        }
        return box;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-min-width: 100;");
        return l;
    }

    private static Label value(String text) {
        return new Label(text != null ? text : "");
    }
}
