package com.mqclient.starrocks

import com.mqclient.olap.base.connection.AnalyticsSession
import com.mqclient.starrocks.jdbc.JdbcConnection

/**
 * StarRocks 会话（重量级共享句柄，对齐 olap-base 的 `AnalyticsSession`）。
 *
 * 由 `StarRocksConnectionManager.openSession` 建立，管理器持有到
 * `close()` / `closeSession()`；[JdbcConnection] 是底层物理连接，
 * 关闭必须幂等且自行兜异常（断开路径不能被一个关不掉的连接中断）。
 */
class StarRocksSession(
    private val connection: JdbcConnection,
    /** 日志展示用的端点（不含凭据）。 */
    val endpoint: String,
) : AnalyticsSession {

    /** 关闭底层连接；幂等，异常只吞掉（由调用方日志记）。 */
    override suspend fun close() {
        runCatching { connection.close() }
    }

    /** 物理连接是否仍可用（半开 / 已关闭 → false，触发管理器自愈）。 */
    override fun isUsable(): Boolean = connection.isUsable()

    /** 探针语句 `SELECT 1`（`healthCheck` / `testConnection` 共用）。 */
    suspend fun probe(): Boolean = connection.probe()
}
