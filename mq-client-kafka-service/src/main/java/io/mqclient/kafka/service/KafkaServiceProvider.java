package io.mqclient.kafka.service;

import io.mqclient.core.service.MQService;
import io.mqclient.core.spi.MQServiceProvider;

/**
 * Kafka 服务提供者 SPI 实现。
 */
public class KafkaServiceProvider implements MQServiceProvider {

    @Override
    public String getType() { return "KAFKA"; }

    @Override
    public String getDisplayName() { return "Apache Kafka"; }

    @Override
    public MQService createService() { return new KafkaService(); }
}
