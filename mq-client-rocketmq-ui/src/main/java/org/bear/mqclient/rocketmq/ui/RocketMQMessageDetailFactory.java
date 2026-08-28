package org.bear.mqclient.rocketmq.ui;

import org.bear.mqclient.core.model.BodyEncoding;
import org.bear.mqclient.core.model.MQMessage;
import org.bear.mqclient.core.spi.MessageDetailViewFactory;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * RocketMQ 消息详情展示工厂。
 */
public class RocketMQMessageDetailFactory implements MessageDetailViewFactory {

    @Override
    public String getType() { return "ROCKETMQ"; }

    @Override
    public Node createHeaderPane(MQMessage message) {
        TabPane tabs = new TabPane();
        tabs.setTabMinWidth(80);

        tabs.getTabs().add(new Tab(I18n.get("message.tab.metadata"), buildGrid(message,
                "RocketMQ.msgId", "RocketMQ.queueId",
                "RocketMQ.queueOffset", "RocketMQ.commitLogOffset",
                "RocketMQ.bornHost", "RocketMQ.storeHost",
                "RocketMQ.reconsumeTimes", "RocketMQ.tags",
                "RocketMQ.keys", "RocketMQ.sysFlag"
        )));

        tabs.getTabs().add(new Tab(I18n.get("message.tab.time"), timeGrid(message)));

        tabs.getTabs().add(new Tab("Properties", propsGrid(message)));

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
        return List.of(BodyEncoding.TEXT, BodyEncoding.JSON, BodyEncoding.HEX);
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
            grid.addRow(row++, label(key.replace("RocketMQ.", "")),
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
        Object born = msg.getNativeField("RocketMQ.bornTimestamp");
        Object store = msg.getNativeField("RocketMQ.storeTimestamp");
        if (born instanceof Number n)
            grid.addRow(0, label("Born"), value(sdf.format(new Date(n.longValue()))));
        if (store instanceof Number n)
            grid.addRow(1, label("Store"), value(sdf.format(new Date(n.longValue()))));
        return grid;
    }

    private Node propsGrid(MQMessage msg) {
        VBox box = new VBox(2);
        box.setPadding(new Insets(8));
        Map<String, Object> props = msg.getUserProperties();
        if (props.isEmpty()) {
            box.getChildren().add(new Label(I18n.get("message.noProps")));
            return box;
        }
        for (var e : props.entrySet()) {
            HBox row = new HBox(8);
            Label k = new Label(e.getKey());
            k.setStyle("-fx-font-weight: bold; -fx-min-width: 150;");
            row.getChildren().addAll(k, new Label(String.valueOf(e.getValue())));
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
