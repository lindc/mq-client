package com.mqclient.starrocks.jdbc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「SQL 报错后连接是否该存活」的分类守卫：
 * 只有连接级故障（08xxx / 2006 / 2013）才允许 `raw.close()`；
 * 普通语法错误 / 表不存在 / 权限错误一律只关 Statement。
 */
class JdbcFailureClassificationTest {

    @Test
    fun `ordinary SQL errors keep the connection alive`() {
        assertFalse(isConnectionLevelJdbcFailure(JdbcFailure("syntax error", "42000", 1064)))
        assertFalse(isConnectionLevelJdbcFailure(JdbcFailure("table not found", "42S02", 1146)))
        assertFalse(isConnectionLevelJdbcFailure(JdbcFailure("access denied", "28000", 1045)))
        assertFalse(isConnectionLevelJdbcFailure(IllegalStateException("unknown")))
        assertFalse(isConnectionLevelJdbcFailure(null))
    }

    @Test
    fun `only network level failures allow closing the connection`() {
        assertTrue(isConnectionLevelJdbcFailure(JdbcFailure("communications link failure", "08S01", 0)))
        assertTrue(isConnectionLevelJdbcFailure(JdbcFailure("server has gone away", "HY000", 2006)))
        assertTrue(isConnectionLevelJdbcFailure(JdbcFailure("lost connection during query", "HY000", 2013)))
    }
}
