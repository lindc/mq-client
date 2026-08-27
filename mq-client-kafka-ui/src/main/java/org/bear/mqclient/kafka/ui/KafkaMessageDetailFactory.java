package org.bear.mqclient.kafka.ui;

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
 * Kafka 消息详情展示工厂。
 * <p>
 * Header 展示：partition / offset / key / timestamp / headers
 * Body 展示：消息体
 */
public class KafkaMessageDetailFactory implements MessageDetailViewFactory {

    @Override
    public String getType() { return "KAFKA"; }

    @Override
    public Node createHeaderPane(MQMessage message) {
        TabPane tabs = new TabPane();
        tabs.setTabMinWidth(80);

        // ── 消息元数据 ──
        tabs.getTabs().add(new Tab("元数据", buildGrid(message,
                "topic", "partition", "offset", "kafkaKey",
                "timestampType", "serializedKeySize",
                "serializedValueSize", "leaderEpoch"
        )));

        // ── 时间戳 ──
        tabs.getTabs().add(new Tab("时间", timeGrid(message)));

        // ── Headers ──
        tabs.getTabs().add(new Tab("Headers", headersGrid(message)));

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
        return List.of(BodyEncoding.TEXT, BodyEncoding.JSON, BodyEncoding.RAW);
    }

    @Override
    public List<javafx.scene.control.TableColumn<MQMessage, ?>> getMessageTableColumns() {
        return List.of();
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
            grid.addRow(row++, label(key), value(val != null ? String.valueOf(val) : ""));
        }
        return grid;
    }

    private Node timeGrid(MQMessage msg) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(5);
        grid.setPadding(new Insets(12));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        grid.addRow(0, label("timestamp"), value(sdf.format(new Date(msg.getTimestamp()))));
        return grid;
    }

    private Node headersGrid(MQMessage msg) {
        VBox box = new VBox(2);
        box.setPadding(new Insets(8));
        Map<String, String> headers = msg.getHeaders();
        if (headers.isEmpty()) {
            box.getChildren().add(new Label("(无 header)"));
            return box;
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            HBox row = new HBox(8);
            Label keyLbl = new Label(h.getKey());
            keyLbl.setStyle("-fx-font-weight: bold; -fx-min-width: 120;");
            row.getChildren().addAll(keyLbl, new Label(h.getValue()));
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
