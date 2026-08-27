package io.mqclient.core.spi;

import io.mqclient.core.service.MQService;

/**
 * MQ 服务提供者 SPI。
 * <p>
 * 每个 MQ 类型的 service 模块实现此接口，并在
 * {@code META-INF/services/io.mqclient.core.spi.MQServiceProvider} 中注册。
 */
public interface MQServiceProvider {

    /** 类型标识，唯一，如 "IBM_MQ", "KAFKA", "RABBITMQ" */
    String getType();

    /** 显示名称，如 "IBM MQ", "Apache Kafka" */
    String getDisplayName();

    /** 创建服务实例 */
    MQService createService();
}
