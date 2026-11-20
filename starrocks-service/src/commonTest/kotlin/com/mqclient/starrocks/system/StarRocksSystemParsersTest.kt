package com.mqclient.starrocks.system

import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.ClusterNodeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 变量 / FE 配置 / HA 摘要的解析守卫。
 *
 * 用例里的列名与数据形状都是 **3.4.0 实测输出**（`SHOW VARIABLES`、
 * `ADMIN SHOW FRONTEND CONFIG`、`SHOW FRONTENDS`）—— 与 web 页
 * `/variable`、`/ha` 同源，解析口径对齐才有意义。
 */
class StarRocksSystemParsersTest {

    // ---------------- variables ----------------

    @Test
    fun `variables parse the two column shape of 3_4`() {
        val parsed = parseVariables(
            columns = listOf("Variable_name", "Value"),
            rows = listOf(
                listOf("SQL_AUTO_IS_NULL", "false"),
                listOf("autocommit", "true"),
                listOf("big_query_profile_threshold", "0s"),
            ),
        )

        assertEquals(3, parsed.size)
        assertEquals(SessionVariable("autocommit", "true"), parsed[1])
    }

    @Test
    fun `variables tolerate extra columns and a missing value`() {
        // 部分版本带 Default Value / IsMutable / Comment：多出来的列不影响取 name/value。
        val parsed = parseVariables(
            columns = listOf("Variable_name", "Value", "Default Value", "IsMutable", "Comment"),
            rows = listOf(
                listOf("query_timeout", "300", "300", "true", "query timeout in seconds"),
                listOf("dirty", null),
            ),
        )

        assertEquals("300", parsed[0].value)
        assertEquals("", parsed[1].value, "缺值应为空串而不是 \"null\"")
    }

    @Test
    fun `variables drop rows without a name`() {
        val parsed = parseVariables(
            columns = listOf("Variable_name", "Value"),
            rows = listOf(listOf(null, "x"), listOf("", "y"), listOf("ok", "z")),
        )

        assertEquals(listOf("ok"), parsed.map { it.name })
    }

    @Test
    fun `variable values keep meaningful surrounding content`() {
        // 值与列名不同，不能用 textAt 的 trim 语义把内部空白吃掉。
        val parsed = parseVariables(
            columns = listOf("Variable_name", "Value"),
            rows = listOf(listOf("lower_case_table_names", "0")),
        )
        assertEquals("0", parsed.single().value)
    }

    // ---------------- FE config ----------------

    @Test
    fun `fe config parses the mutable flag as tri state`() {
        val parsed = parseFeConfig(
            columns = listOf("Key", "AliasNames", "Value", "Type", "IsMutable", "Comment"),
            rows = listOf(
                listOf("access_control", "[]", "native", "String", "true", ""),
                listOf("qe_max_connection", "[]", "1024", "int", "false", "max connections"),
                listOf("odd", "[]", "x", "String", "maybe", ""),
            ),
        )

        assertEquals(true, parsed[0].isMutable)
        assertEquals(false, parsed[1].isMutable)
        assertNull(parsed[2].isMutable, "解析不出的取值必须是 null（不知道），不能当 false")
        assertEquals("max connections", parsed[1].comment)
    }

    @Test
    fun `fe config survives a missing IsMutable column`() {
        val parsed = parseFeConfig(
            columns = listOf("Key", "Value", "Type"),
            rows = listOf(listOf("access_control", "native", "String")),
        )

        assertEquals(1, parsed.size)
        assertNull(parsed.single().isMutable)
        assertEquals("", parsed.single().comment)
    }

    @Test
    fun `fe config drops rows without a key`() {
        val parsed = parseFeConfig(
            columns = listOf("Key", "Value"),
            rows = listOf(listOf(null, "x"), listOf("k", "v")),
        )
        assertEquals(listOf("k"), parsed.map { it.key })
    }

    // ---------------- HA summary ----------------

    private fun fe(
        name: String,
        role: ClusterNodeRole,
        alive: Boolean = true,
    ): ClusterNodeInfo = ClusterNodeInfo(
        nodeId = "fe:$name",
        role = role,
        name = name,
        ip = "127.0.0.1",
        ports = emptyList(),
        alive = alive,
        version = "3.4.0",
    )

    @Test
    fun `three node cluster with one down keeps quorum`() {
        val summary = haSummary(
            listOf(
                fe("fe1", ClusterNodeRole.FE_LEADER, alive = true),
                fe("fe2", ClusterNodeRole.FE_FOLLOWER, alive = true),
                fe("fe3", ClusterNodeRole.FE_FOLLOWER, alive = false),
            ),
        )

        assertEquals("fe1", summary.leaderName)
        assertEquals(3, summary.electableTotal)
        assertEquals(2, summary.electableAlive)
        assertTrue(summary.quorumHealthy, "2/3 存活 = 多数派仍在")
        assertEquals(0, summary.toleratedFailures, "再挂一台就只剩 1/3")
    }

    @Test
    fun `two of three down loses quorum`() {
        val summary = haSummary(
            listOf(
                fe("fe1", ClusterNodeRole.FE_LEADER, alive = false),
                fe("fe2", ClusterNodeRole.FE_FOLLOWER, alive = false),
                fe("fe3", ClusterNodeRole.FE_FOLLOWER, alive = true),
            ),
        )

        assertFalse(summary.quorumHealthy, "1/3 存活选不出 leader")
        assertNull(summary.leaderName, "没有 LEADER 节点")
    }

    @Test
    fun `observers never count toward quorum`() {
        val summary = haSummary(
            listOf(
                fe("fe1", ClusterNodeRole.FE_LEADER, alive = true),
                fe("obs1", ClusterNodeRole.FE_OBSERVER, alive = true),
                fe("obs2", ClusterNodeRole.FE_OBSERVER, alive = true),
            ),
        )

        assertEquals(1, summary.electableTotal, "只有 LEADER 是可选举节点")
        assertEquals(2, summary.observerTotal)
        assertTrue(summary.quorumHealthy)
    }

    @Test
    fun `single node cluster is healthy with no tolerance`() {
        val summary = haSummary(listOf(fe("fe1", ClusterNodeRole.FE_LEADER, alive = true)))

        assertTrue(summary.quorumHealthy)
        assertEquals(0, summary.toleratedFailures)
    }

    @Test
    fun `empty cluster is not healthy`() {
        val summary = haSummary(emptyList())

        assertFalse(summary.quorumHealthy, "没有节点谈不上 quorum 健康")
        assertEquals(0, summary.toleratedFailures)
    }

    @Test
    fun `five node cluster tolerates two failures`() {
        val summary = haSummary(
            listOf(
                fe("fe1", ClusterNodeRole.FE_LEADER, alive = true),
                fe("fe2", ClusterNodeRole.FE_FOLLOWER, alive = true),
                fe("fe3", ClusterNodeRole.FE_FOLLOWER, alive = true),
                fe("fe4", ClusterNodeRole.FE_FOLLOWER, alive = true),
                fe("fe5", ClusterNodeRole.FE_FOLLOWER, alive = true),
            ),
        )

        assertEquals(5, summary.electableTotal)
        assertEquals(5, summary.electableAlive)
        assertTrue(summary.quorumHealthy)
        assertEquals(2, summary.toleratedFailures, "5 台全活时还能挂 2 台")
    }
}
