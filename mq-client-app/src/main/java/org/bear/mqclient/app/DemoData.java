package org.bear.mqclient.app;

import java.util.List;

/**
 * 演示用假数据。UI 骨架尚未接入 SPI 与真实连接，只为确认观感。
 */
final class DemoData {

    record Conn(String type, String name, String host, boolean connected, List<Dest> dests) {
    }

    record Dest(String type, String name) {
    }

    record Msg(String id, String time, String size, String preview) {
    }

    static final List<Conn> CONNECTIONS = List.of(
            new Conn("IBM MQ", "prod-qm", "ibm-prod:1414", true, List.of(
                    new Dest("队列", "Q.ORDER.PAID"),
                    new Dest("队列", "Q.ORDER.CREATED"),
                    new Dest("队列", "Q.ORDER.SHIPPED"),
                    new Dest("主题", "T.PRICE.CHANGED")
            )),
            new Conn("IBM MQ", "test-qm", "ibm-test:1414", false, List.of(
                    new Dest("队列", "Q.TEST.SEND"),
                    new Dest("队列", "Q.TEST.REPLY")
            )),
            new Conn("Kafka", "prod-cluster", "kafka-01:9092", true, List.of(
                    new Dest("主题", "order.paid"),
                    new Dest("主题", "order.created"),
                    new Dest("主题", "stock.changed")
            )),
            new Conn("Kafka", "staging", "kafka-stg:9092", false, List.of(
                    new Dest("主题", "order.paid")
            )),
            new Conn("RabbitMQ", "local", "localhost:5672", false, List.of(
                    new Dest("队列", "order.paid.q"),
                    new Dest("交换机", "amq.direct"),
                    new Dest("交换机", "amq.topic")
            )),
            new Conn("Artemis", "edge", "artemis-01:61616", false, List.of(
                    new Dest("队列", "DLQ"),
                    new Dest("队列", "ExpiryQueue")
            )),
            new Conn("RocketMQ", "namesrv", "rocket-01:9876", false, List.of(
                    new Dest("主题", "OrderTopic"),
                    new Dest("主题", "PayTopic")
            ))
    );

    static final List<Msg> MESSAGES = List.of(
            new Msg("msg-0001", "10:21:03", "1.2 KB", "{\"orderId\":10021,\"status\":\"PAID\"}"),
            new Msg("msg-0002", "10:21:07", "864 B", "{\"orderId\":10022,\"status\":\"CREATED\"}"),
            new Msg("msg-0003", "10:21:12", "2.4 KB", "{\"orderId\":10023,\"status\":\"SHIPPED\"}")
    );

    private DemoData() {
    }
}
