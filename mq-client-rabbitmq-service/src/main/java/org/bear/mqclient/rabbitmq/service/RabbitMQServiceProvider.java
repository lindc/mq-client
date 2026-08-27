package org.bear.mqclient.rabbitmq.service;

import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQServiceProvider;

/**
 * RabbitMQ 服务提供者 SPI 实现。
 */
public class RabbitMQServiceProvider implements MQServiceProvider {

    @Override
    public String getType() { return "RABBITMQ"; }

    @Override
    public String getDisplayName() { return "RabbitMQ"; }

    @Override
    public MQService createService() { return new RabbitMQService(); }
}
