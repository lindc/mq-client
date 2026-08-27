package io.mqclient.ibm.service;

import io.mqclient.core.service.MQService;
import io.mqclient.core.spi.MQServiceProvider;

/**
 * IBM MQ 服务提供者 SPI 实现。
 */
public class IBMMQServiceProvider implements MQServiceProvider {

    @Override
    public String getType() { return "IBM_MQ"; }

    @Override
    public String getDisplayName() { return "IBM MQ"; }

    @Override
    public MQService createService() { return new IBMMQService(); }
}
