package com.mqclient.olap.base.connection

/**
 * 一条 OLAP 连接的配置快照（对标 mq-base 的连接上下文配置模型）。
 *
 * 只承载**连接建立所需的最小字段**；协议特有配置（JDBC URL、HTTP 端点、凭据等）
 * 由各协议模块的实现类补充，或放进 [options]（与 app-core `ConnectionInfo.options`
 * 同形，敏感项由 `@Sensitive` 序列化器在落盘层加密，本接口不感知）。
 *
 * 实现必须是不可变数据类型：连接建立后配置即快照，运行期不改写
 * （改配置 = 断开重连，与 MQ 系的「connection.updated 触发断开」同一口径）。
 */
interface AnalyticsConnectionConfig {

    /** 连接唯一 id（与 app-core `ConnectionInfo.id` 同源）。 */
    val connectionId: String

    /** 人类可读名称（日志与 UI 展示用）。 */
    val displayName: String

    /** 连接选项（协议特有键值对；敏感项在落盘层已加密）。 */
    val options: Map<String, String>
}
