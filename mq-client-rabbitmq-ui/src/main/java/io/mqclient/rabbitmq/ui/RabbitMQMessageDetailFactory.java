package io.mqclient.rabbitmq.ui;

import io.mqclient.core.model.BodyEncoding;
import io.mqclient.core.model.MQMessage;
import io.mqclient.core.spi.MessageDetailViewFactory;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;

import java.util.List;

/**
 * RabbitMQ 消息详情展示工厂。
 */
public class RabbitMQMessageDetailFactory implements MessageDetailViewFactory {

    @Override
    public String getType() { return "RABBITMQ"; }

    @Override
    public Node createHeaderPane(MQMessage message) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));

        grid.addRow(0, label("Routing Key"),   value(strField(message, "routingKey")));
        grid.addRow(1, label("Exchange"),      value(strField(message, "exchange")));
        grid.addRow(2, label("Delivery Tag"),  value(strField(message, "deliveryTag")));
        grid.addRow(3, label("Redeliver"),     value(String.valueOf(message.getNativeField("redeliver"))));
        grid.addRow(4, label("Content Type"),  value(message.getContentType()));
        grid.addRow(5, label("Message ID"),    value(message.getMessageId()));
        grid.addRow(6, label("Correlation ID"),value(message.getCorrelationId()));
        grid.addRow(7, label("Reply To"),      value(message.getReplyTo()));
        grid.addRow(8, label("Timestamp"),     value(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
                .format(new java.util.Date(message.getTimestamp()))));
        grid.addRow(9, label("Delivery Mode"), value(strField(message, "deliveryMode")));
        grid.addRow(10, label("Priority"),     value(strField(message, "priority")));
        grid.addRow(11, label("Expiration"),   value(strField(message, "expiration")));

        return grid;
    }

    @Override
    public Node createBodyPane(MQMessage message) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setFont(Font.font("Consolas", 13));

        if (message.getPayload() != null) {
            area.setText(message.getPayloadText());
        } else {
            area.setText("");
        }

        // 自动换行
        area.setWrapText(true);
        return area;
    }

    @Override
    public List<BodyEncoding> getSupportedEncodings() {
        return List.of(BodyEncoding.TEXT, BodyEncoding.RAW, BodyEncoding.HEX, BodyEncoding.JSON);
    }

    @Override
    public List<TableColumn<MQMessage, ?>> getMessageTableColumns() {
        TableColumn<MQMessage, String> exchangeCol = new TableColumn<>("Exchange");
        exchangeCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(
                        strField(d.getValue(), "exchange")));

        TableColumn<MQMessage, String> routingKeyCol = new TableColumn<>("Routing Key");
        routingKeyCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(
                        strField(d.getValue(), "routingKey")));

        return List.of(exchangeCol, routingKeyCol);
    }

    // ── 辅助 ──

    private static String strField(MQMessage msg, String key) {
        Object v = msg.getNativeField(key);
        return v != null ? String.valueOf(v) : "";
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-min-width: 110;");
        return l;
    }

    private static Label value(String text) {
        return new Label(text != null ? text : "");
    }
}
