package com.mqclient.core.db

/**
 * 一条对象状态采集记录（对应 `mq_status` 表，见 mq_status.sq）。
 *
 * 由扫描任务（IBM MQ = 消息心跳 / 对象慢扫）打包进 `core.mq.status.snapshot.created`
 * 事件，MqStatusHistoryHandler 批量落库。表只追加：一条记录即某时刻某对象某属性的
 * 一个采样点，不更新不删除（过期数据由保留天数清理负责）。
 */
data class MqStatusRecord(
    /** 记录 UUIDv7（发事件前由发出方生成）。 */
    val id: String,
    /** 采集所用连接（UUIDv7，= ConnectionInfo.id）。 */
    val connectionId: String,
    /** 对象类型：QUEUE / CHANNEL / LISTENER / TOPIC / SUBSCRIPTION。 */
    val objectType: String,
    /** 对象名，如 ORDER.LOCAL.Q、QM1.TO.QM2。 */
    val objectName: String,
    /** 属性名，如 depth、status、messages。 */
    val propertyName: String,
    /** 文本值：状态类属性（RUNNING / STOPPED …）；数值类属性存其字符串形式。 */
    val propertyValue: String?,
    /** 数值值：数值类属性（深度 / 消息数 / 字节数）；状态类属性为 null。 */
    val numericValue: Double?,
    /** 采集时刻（Unix 毫秒）。 */
    val createTime: Long,
)
