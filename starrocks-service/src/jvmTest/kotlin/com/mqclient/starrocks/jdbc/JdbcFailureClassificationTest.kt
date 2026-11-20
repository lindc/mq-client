package com.mqclient.starrocks.jdbc

import com.mqclient.olap.base.error.OlapErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * 错误码 → [OlapErrorCode] 的分类。用例里的码取自本地 3.4.0 的**实测返回**。
     *
     * 重点是「拼错对象名」不再落进 UNKNOWN：这是最常见的操作失误，用户需要看到
     * 「库/表不存在」而不是「未知的引擎错误」。
     */
    @Test
    fun `unknown database and table map to object not found`() {
        // ERROR 5501 (3F000): Unknown database 'nonexistent_db'
        assertEquals(
            OlapErrorCode.OBJECT_NOT_FOUND,
            StarRocksSqlDialect.classify(JdbcFailure("Unknown database 'x'", "3F000", 5501)),
        )
        // ERROR 5502 (42602): Unknown table 'test_db.nonexistent_table'
        assertEquals(
            OlapErrorCode.OBJECT_NOT_FOUND,
            StarRocksSqlDialect.classify(
                JdbcFailure("Getting analyzing error. Detail message: Unknown table 'x'.", "42602", 5502),
            ),
        )
    }

    @Test
    fun `syntax and permission errors keep their own classification`() {
        // 加 5501/5502 分支不能吃掉既有分类。
        assertEquals(
            OlapErrorCode.SYNTAX_ERROR,
            StarRocksSqlDialect.classify(JdbcFailure("Column 'x' cannot be resolved.", "HY000", 1064)),
        )
        assertEquals(
            OlapErrorCode.PERMISSION_DENIED,
            StarRocksSqlDialect.classify(JdbcFailure("Access denied", "28000", 1045)),
        )
        assertEquals(
            OlapErrorCode.CONNECTION_FAILED,
            StarRocksSqlDialect.classify(JdbcFailure("Communications link failure", "08S01", 0)),
        )
    }
}
