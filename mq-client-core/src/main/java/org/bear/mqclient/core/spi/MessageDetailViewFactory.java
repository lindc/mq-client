package org.bear.mqclient.core.spi;

import org.bear.mqclient.core.model.BodyEncoding;
import org.bear.mqclient.core.model.MQMessage;
import javafx.scene.Node;
import javafx.scene.control.TableColumn;

import java.util.List;

/**
 * 消息详情展示工厂 SPI。
 * <p>
 * core 提供统一的消息详情容器（Header Tab + Body Tab），
 * 内容由各 MQ 的实现填充。
 */
public interface MessageDetailViewFactory {

    /** 该工厂支持的 MQ 类型 */
    String getType();

    /** 创建 Header 展示面板 */
    Node createHeaderPane(MQMessage message);

    /** 创建 Body 展示面板 */
    Node createBodyPane(MQMessage message);

    /**
     * 该 MQ 支持的 Body 编码格式。
     * 如果返回多种格式，core 会在 Body tab 的右上角渲染切换按钮。
     * 默认只支持 RAW + TEXT + HEX。
     */
    default List<BodyEncoding> getSupportedEncodings() {
        return List.of(BodyEncoding.RAW, BodyEncoding.TEXT, BodyEncoding.HEX);
    }

    /** 可选：该 MQ 消息列表的自定义列（如 Kafka 的 offset/partition） */
    default List<TableColumn<MQMessage, ?>> getMessageTableColumns() {
        return List.of();
    }
}
