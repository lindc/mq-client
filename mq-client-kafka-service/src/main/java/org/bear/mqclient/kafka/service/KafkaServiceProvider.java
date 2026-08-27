package org.bear.mqclient.kafka.service;

import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQServiceProvider;

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
