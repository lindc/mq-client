package org.bear.mqclient.kafka.service;

import org.bear.mqclient.core.util.LogAdapter;

/**
 * Kafka service 模块统一日志器。
 * <p>
 * source 固定为 "KAFKA"，包内所有类复用此常量，避免各处手写 source 字符串。
 */
public final class KafkaLogs {

    public static final LogAdapter.Log LOG = LogAdapter.forSource("KAFKA");

    private KafkaLogs() {
    }
}
