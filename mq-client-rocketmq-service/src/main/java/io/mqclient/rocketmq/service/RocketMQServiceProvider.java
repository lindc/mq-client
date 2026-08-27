package io.mqclient.rocketmq.service;

import io.mqclient.core.service.MQService;
import io.mqclient.core.spi.MQServiceProvider;

/**
 * RocketMQ 服务提供者 SPI 实现。
 */
public class RocketMQServiceProvider implements MQServiceProvider {

    @Override
    public String getType() { return "ROCKETMQ"; }

    @Override
    public String getDisplayName() { return "Apache RocketMQ"; }

    @Override
    public MQService createService() { return new RocketMQService(); }
}
