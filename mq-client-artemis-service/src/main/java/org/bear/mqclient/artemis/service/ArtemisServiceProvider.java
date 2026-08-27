package org.bear.mqclient.artemis.service;

import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQServiceProvider;

/**
 * Artemis 服务提供者 SPI 实现。
 */
public class ArtemisServiceProvider implements MQServiceProvider {

    @Override
    public String getType() { return "ARTEMIS"; }

    @Override
    public String getDisplayName() { return "ActiveMQ Artemis"; }

    @Override
    public MQService createService() { return new ArtemisService(); }
}
