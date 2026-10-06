package com.mqclient.core.db

/**
 * 一条 MQ 连接的历史快照（对应 `mq_connection` 表，见 mq_connection.sq）。
 *
 * config.toml 是权威数据源；这里只是「保存那一刻」的只读倒影，供
 * operation_history 按 [id] 关联展示。敏感信息（密码等）不进快照。
 *
 * [id] 就是这条快照所属连接的 UUIDv7 —— **每次保存连接都换发新 id**（编辑 = 旧 id
 * 废弃 + 新 id 接任，见 ConnectionStore.update），所以一个 id 只对应一份记录；
 * 旧 id 的记录留在表里，按 [createdAt] 排序即连接的变更轨迹（表内不做版本链追溯）。
 */
data class MqConnectionSnapshot(
    /** 连接 UUIDv7（表主键，插入时由事件 payload 带入，与 ConnectionInfo.id 同源）。 */
    val id: String,
    /** MQ 类型 = serviceId（如 "ibm-mq"），预留多 MQ 类型。 */
    val type: String,
    val name: String,
    val host: String,
    val port: Int,
    /** 逻辑容器：IBM MQ 存 qmgr_name。 */
    val target: String?,
    val userName: String?,
    /** 快照创建时间（Unix 毫秒）。 */
    val createdAt: Long,
)
