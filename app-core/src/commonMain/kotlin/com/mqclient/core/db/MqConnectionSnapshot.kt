package com.mqclient.core.db

/**
 * 一条 MQ 连接的历史快照（对应 `mq_connection` 表，见 mq_connection.sq）。
 *
 * config.toml 是权威数据源；这里只是「保存那一刻」的只读倒影，供
 * operation_history 按 [connectionId] 关联展示。敏感信息（密码等）不进快照。
 *
 * [id] 是表的自增主键（插入时无意义，由数据库分配）；[connectionId] 是这条快照
 * 所属连接的 UUID —— **每次保存连接都换发新 id**（编辑 = 旧 id 废弃 + 新 id 接任，
 * 见 ConnectionStore.update），所以 connection_id 与快照行一一对应，operation_history
 * 按 connection_id 关联不会重复放大；历史链是跨 id 的，表内不做追溯。
 */
data class MqConnectionSnapshot(
    val id: Long,
    val connectionId: String,
    /** MQ 类型 = serviceId（如 "ibm-mq"），预留多 MQ 类型。 */
    val type: String,
    val name: String?,
    val host: String?,
    val port: Int?,
    /** 逻辑容器：IBM MQ 存 qmgr_name。 */
    val target: String?,
    val userName: String?,
    /** 快照创建时间（Unix 毫秒）。 */
    val createdTime: Long,
)
