package com.mqclient.starrocks.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JDBC URL 改写的守卫。
 *
 * 隧道只该改「连到哪里」，不该碰其余字节 —— 一旦重排了查询参数，用户配的
 * `sessionVariables=` / `connectTimeout=` 就可能失效（顺序敏感的驱动参数确实存在）。
 */
class ConnectionTunnelTest {

    @Test
    fun `authority is replaced and everything else is byte identical`() {
        assertEquals(
            "jdbc:mysql://127.0.0.1:41234/sales?connectTimeout=3000&sessionVariables=enable_profile%3Dtrue",
            rewriteJdbcAuthority(
                "jdbc:mysql://db.example:9030/sales?connectTimeout=3000&sessionVariables=enable_profile%3Dtrue",
                port = 41234,
            ),
        )
    }

    @Test
    fun `url without path keeps its shape`() {
        assertEquals("jdbc:mysql://127.0.0.1:9999", rewriteJdbcAuthority("jdbc:mysql://h:9030", port = 9999))
        assertEquals("jdbc:mysql://127.0.0.1:9999?x=1", rewriteJdbcAuthority("jdbc:mysql://h:9030?x=1", port = 9999))
    }

    @Test
    fun `userinfo is dropped because credentials go through jdbc parameters`() {
        assertEquals(
            "jdbc:mysql://127.0.0.1:5000/db",
            rewriteJdbcAuthority("jdbc:mysql://user:pass@db.example:9030/db", port = 5000),
        )
    }

    @Test
    fun `ipv6 hosts are bracketed`() {
        assertEquals(
            "jdbc:mysql://[::1]:5000/db",
            rewriteJdbcAuthority("jdbc:mysql://[fe80::1]:9030/db", host = "::1", port = 5000),
        )
    }

    @Test
    fun `unrecognised shapes return null instead of silently connecting elsewhere`() {
        assertNull(rewriteJdbcAuthority("db.example:9030/db", port = 5000), "没有 scheme 应返回 null")
        assertNull(rewriteJdbcAuthority("jdbc:mysql://", port = 5000), "空 authority 应返回 null")
    }

    @Test
    fun `loopback constant is not a wildcard address`() {
        // 绑 0.0.0.0 会把数据库端口暴露给同网段 —— 必须是回环。
        assertEquals("127.0.0.1", LOOPBACK)
        assertTrue(LOOPBACK != "0.0.0.0")
    }
}
