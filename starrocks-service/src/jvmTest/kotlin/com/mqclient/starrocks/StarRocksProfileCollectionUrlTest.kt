package com.mqclient.starrocks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 连接 URL 自动追加 Profile 采集会话变量（`sessionVariables=enable_profile=true`，
 * Connector/J 握手后自动对会话执行 SET）——「查看 Profile」闭环的采集侧兜底。
 */
class StarRocksProfileCollectionUrlTest {

    @Test
    fun `plain url gets session variable appended`() {
        assertEquals(
            "jdbc:mysql://localhost:9030/test_db?sessionVariables=enable_profile=true",
            withProfileCollection("jdbc:mysql://localhost:9030/test_db"),
        )
    }

    @Test
    fun `url with existing params uses ampersand`() {
        assertEquals(
            "jdbc:mysql://localhost:9030/test_db?connectTimeout=5000&sessionVariables=enable_profile=true",
            withProfileCollection("jdbc:mysql://localhost:9030/test_db?connectTimeout=5000"),
        )
    }

    @Test
    fun `user configured session variables are not overridden`() {
        val url = "jdbc:mysql://localhost:9030/test_db?sessionVariables=sql_mode=STRICT_TRANS_TABLES"
        assertEquals(url, withProfileCollection(url))
    }

    @Test
    fun `default config jdbc url carries the collection variable`() {
        val config = StarRocksConnectionConfig(connectionId = "c1", displayName = "local")
        assertTrue(
            config.jdbcUrl.contains("sessionVariables=enable_profile=true"),
            "默认连接必须开启 Profile 采集：${config.jdbcUrl}",
        )
    }

    @Test
    fun `override url also gets the collection variable`() {
        val config = StarRocksConnectionConfig(
            connectionId = "c1",
            displayName = "local",
            jdbcUrlOverride = "jdbc:mysql://localhost:9030/test_db?socketTimeout=30000",
        )
        assertTrue(
            config.jdbcUrl.endsWith("&sessionVariables=enable_profile=true"),
            "覆盖 URL 也要开启采集：${config.jdbcUrl}",
        )
    }
}
