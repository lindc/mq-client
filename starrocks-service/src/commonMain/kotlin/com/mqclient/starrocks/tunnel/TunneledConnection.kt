package com.mqclient.starrocks.tunnel

import com.mqclient.starrocks.jdbc.JdbcConnection
import com.mqclient.starrocks.jdbc.JdbcQueryBatch

/**
 * 把 [ConnectionTunnel] 的生命周期绑到 [JdbcConnection] 上：**连接关 = 隧道关**。
 *
 * 为什么用装饰器而不是在 manager 里手动配对关闭：`JdbcConnection.close()` 是所有
 * 断开路径的收敛点（用户点断开、会话半开自愈、测试连接的 finally、模块 dispose）。
 * 挂在这里，上面每条路径都不需要知道隧道的存在，也就不可能漏关。
 *
 * 其余方法全部透传 —— 隧道只改变"连到哪里"，不改变 JDBC 语义。
 */
internal class TunneledConnection(
    private val delegate: JdbcConnection,
    private val tunnel: ConnectionTunnel,
) : JdbcConnection {

    override suspend fun probe(): Boolean = delegate.probe()

    override suspend fun executeQuery(
        sql: String,
        batchSize: Int,
        onBatch: suspend (JdbcQueryBatch) -> Unit,
    ) = delegate.executeQuery(sql, batchSize, onBatch)

    override fun isUsable(): Boolean = delegate.isUsable()

    /**
     * 先关连接再关隧道。
     *
     * 顺序不能反：先断 SSH 会让 JDBC 侧的 close 拿到"连接被重置"，
     * 虽然两边都吞异常，但日志里会多一条无意义的错误。
     */
    override suspend fun close() {
        try {
            delegate.close()
        } finally {
            tunnel.close()
        }
    }
}
