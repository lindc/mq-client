package com.mqclient.ibmmq.command

import com.mqclient.ibmmq.command.model.MqscHistoryItem

/**
 * MQSC 执行历史仓库。v1 为**进程内内存实现**（每连接一条列表，上限截断）——
 * 落库时换 SQLDelight 实现（模块私有 .sq，见 IbmMQService.saveObjects 的注释），
 * 接口形状不变。
 */
class MqscHistoryRepository {

    private val lock = Any()
    private val byConnection = HashMap<String, MutableList<MqscHistoryItem>>()
    private var nextId = 1L

    /** 单连接历史上限：内存实现下防无限增长（真库版本用查询 limit 取代）。 */
    private val maxPerConnection = 500

    suspend fun add(item: MqscHistoryItem) {
        synchronized(lock) {
            val stored = item.copy(id = nextId++)
            byConnection.getOrPut(stored.connectionId) { mutableListOf() }.apply {
                add(stored)
                if (size > maxPerConnection) removeAt(0)
            }
        }
    }

    /** 最近 [limit] 条（时间倒序）。 */
    suspend fun getRecent(connectionId: String, limit: Int): List<MqscHistoryItem> =
        synchronized(lock) {
            byConnection[connectionId].orEmpty().takeLast(limit).asReversed()
        }

    /** 按关键字过滤（命令文本 contains，忽略大小写），时间倒序。 */
    suspend fun search(connectionId: String, keyword: String): List<MqscHistoryItem> =
        synchronized(lock) {
            val kw = keyword.uppercase()
            byConnection[connectionId].orEmpty()
                .filter { it.command.uppercase().contains(kw) }
                .asReversed()
        }

    suspend fun clear(connectionId: String) {
        synchronized(lock) { byConnection.remove(connectionId) }
    }
}
