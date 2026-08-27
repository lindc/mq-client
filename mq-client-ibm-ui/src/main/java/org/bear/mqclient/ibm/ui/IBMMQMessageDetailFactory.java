package org.bear.mqclient.ibm.ui;

import org.bear.mqclient.core.model.BodyEncoding;
import org.bear.mqclient.core.model.MQMessage;
import org.bear.mqclient.core.spi.MessageDetailViewFactory;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * IBM MQ 消息详情展示工厂。
 * <p>
 * Header Tab：按 MQMD 字段分组展示（MsgId / CorrelId / PutApp / Expiry / Priority / ...）
 * Body Tab：支持 TEXT / HEX / JSON 格式切换
 */
public class IBMMQMessageDetailFactory implements MessageDetailViewFactory {

    @Override
    public String getType() { return "IBM_MQ"; }

    @Override
    public Node createHeaderPane(MQMessage message) {
        TabPane tabPane = new TabPane();
        tabPane.setTabMinWidth(80);

        // ── MQMD 基础字段 ──
        tabPane.getTabs().add(new Tab("MQMD 基础", buildGrid(message,
                "MQMD.MsgId", "MQMD.CorrelId", "MQMD.Format",
                "MQMD.Expiry", "MQMD.Priority", "MQMD.Persistence",
                "MQMD.MsgType", "MQMD.Feedback", "MQMD.Report",
                "MQMD.BackoutCount", "MQMD.MsgFlags"
        )));

        // ── 身份 / 来源 ──
        tabPane.getTabs().add(new Tab("来源/身份", buildGrid(message,
                "MQMD.PutApplicationName", "MQMD.UserId",
                "MQMD.PutDate", "MQMD.PutTime",
                "MQMD.ApplIdentityData", "MQMD.ApplOriginData",
                "MQMD.AccountingToken"
        )));

        // ── 回覆 / 分组 ──
        tabPane.getTabs().add(new Tab("回覆/分组", buildGrid(message,
                "MQMD.ReplyToQ", "MQMD.ReplyToQMgr",
                "MQMD.GroupId", "MQMD.MsgSeqNumber",
                "MQMD.Offset", "MQMD.OriginalLength",
                "MQMD.Encoding", "MQMD.CodedCharSetId"
        )));

        // ── 通用字段 ──
        tabPane.getTabs().add(new Tab("通用", buildGridWithoutMqmd(message,
                "MessageId", "CorrelationId", "Content Type", "Reply To", "Timestamp"
        )));

        return tabPane;
    }

    @Override
    public Node createBodyPane(MQMessage message) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setFont(Font.font("Consolas", 13));
        area.setWrapText(true);

        if (message.getPayload() != null && message.getPayload().length > 0) {
            area.setText(message.getPayloadText());
        }

        VBox box = new VBox(area);
        VBox.setVgrow(area, Priority.ALWAYS);
        return box;
    }

    @Override
    public List<BodyEncoding> getSupportedEncodings() {
        return List.of(BodyEncoding.TEXT, BodyEncoding.HEX, BodyEncoding.JSON, BodyEncoding.RAW);
    }

    @Override
    public List<javafx.scene.control.TableColumn<MQMessage, ?>> getMessageTableColumns() {
        return List.of();
    }

    // ── 辅助 ──

    /** 从 nativeFields 读取字段，key 以 "MQMD." 开头 */
    private Node buildGrid(MQMessage msg, String... keys) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(5);
        grid.setPadding(new Insets(12));

        int row = 0;
        for (String key : keys) {
            Object val = msg.getNativeField(key);
            String strVal = formatValue(val);
            grid.addRow(row++, label(key.replace("MQMD.", "")), value(strVal));
        }
        return grid;
    }

    /** 从 getter 读取通用字段 */
    private Node buildGridWithoutMqmd(MQMessage msg, String... names) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(5);
        grid.setPadding(new Insets(12));

        int row = 0;
        for (String name : names) {
            String val = switch (name) {
                case "MessageId" -> msg.getMessageId();
                case "CorrelationId" -> msg.getCorrelationId();
                case "Content Type" -> msg.getContentType();
                case "Reply To" -> msg.getReplyTo();
                case "Timestamp" -> {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                    yield sdf.format(new Date(msg.getTimestamp()));
                }
                default -> "";
            };
            grid.addRow(row++, label(name), value(val));
        }
        return grid;
    }

    private static String formatValue(Object val) {
        if (val == null) return "";
        if (val instanceof byte[] b) return bytesToHex(b);
        return String.valueOf(val);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[i * 2]     = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-min-width: 100;");
        return l;
    }

    private static Label value(String text) {
        Label l = new Label(text != null ? text : "");
        l.setWrapText(true);
        return l;
    }
}
