package org.bear.mqclient.rocketmq.service;

import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQServiceProvider;

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
