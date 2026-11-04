@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.rocketmq.RocketMqBrokerSnapshot
import com.mqclient.rocketmq.RocketMqTopicRouteSnapshot
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 路由信息 Tab 的表格样式守卫（与概览的 `PropertyTable` 对齐）。
 *
 * 现场问题只有一处：表头用 `labelMedium`（12sp），比数据行的 `bodyMedium`（14sp）
 * 小一整档；数据行的行高 / 斑马纹本来就已是 PropertyTable 同一套参数。
 *
 * 断言落在**渲染结果**上而不是源码：字号回归不会让编译失败，只会让表头安静地
 * 又小一号。表头字号用「渲染高度」间接验证 —— bodyMedium 行高 20dp、labelMedium
 * 只有 16dp，阈值 19dp 能干净地分开两者。
 */
class RocketMqRoutesTabTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RocketMqUI().i18nTables)
    }

    private fun routes() = listOf(
        RocketMqTopicRouteSnapshot(brokerName = "broker-a", readQueueNums = 8, writeQueueNums = 8, perm = 6),
        RocketMqTopicRouteSnapshot(brokerName = "broker-b", readQueueNums = 4, writeQueueNums = 4, perm = 4),
        RocketMqTopicRouteSnapshot(brokerName = "broker-c", readQueueNums = 4, writeQueueNums = 2, perm = 2),
    )

    private fun brokers() = listOf(
        RocketMqBrokerSnapshot(
            cluster = "DefaultCluster",
            brokerName = "broker-a",
            brokerId = 0,
            address = "10.0.0.1:10911",
            role = "Master",
        ),
        RocketMqBrokerSnapshot(
            cluster = "DefaultCluster",
            brokerName = "broker-b",
            brokerId = 1,
            address = "10.0.0.2:10911",
            role = "Slave",
        ),
    )

    private fun inkPixels(map: PixelMap): Int {
        val bg = map[0, 0]
        var ink = 0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                if (map[x, y] != bg) ink++
            }
        }
        return ink
    }

    @Test
    fun `headers and route rows render`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { RoutesTab(routes(), brokers()) } }
        waitForIdle()

        // 五个表头
        onNodeWithText("Broker 名称").assertExists()
        onNodeWithText("读队列数").assertExists()
        onNodeWithText("写队列数").assertExists()
        onNodeWithText("权限").assertExists()
        onNodeWithText("角色").assertExists()

        // 数据：broker 名 + 队列数 + role（Master 来自 clusterBrokers 联表，未命中回落 "-"）
        onAllNodesWithText("broker-a")[0].assertExists()
        onAllNodesWithText("broker-c")[0].assertExists()
        onAllNodesWithText("Master")[0].assertExists()
        onAllNodesWithText("Slave")[0].assertExists()
    }

    /**
     * 表头字号：渲染高度必须够 bodyMedium 的行高（20dp）。
     *
     * labelMedium 的行高是 16dp，退回它这条就会红 —— 这是本轮唯一真正的改动点。
     */
    @Test
    fun `header cells are rendered at bodyMedium size not labelMedium`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { RoutesTab(routes(), brokers()) } }
        waitForIdle()

        val headerHeight = onNodeWithText("Broker 名称").fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            headerHeight >= 19f,
            "表头必须是 bodyMedium 档（行高 20dp）而不是 labelMedium（16dp），实测 ${headerHeight}dp",
        )
    }

    @Test
    fun `route rows keep the 40dp row height of the property table`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { RoutesTab(routes(), brokers()) } }
        waitForIdle()

        onAllNodesWithTag(ROUTE_PLAIN_ROW_TAG)[0].assertHeightIsAtLeast(ROUTE_ROW_MIN_HEIGHT)
        onAllNodesWithTag(ROUTE_ZEBRA_ROW_TAG)[0].assertHeightIsAtLeast(ROUTE_ROW_MIN_HEIGHT)
    }

    /**
     * 斑马纹：奇数行自己画背景（alpha > 0），偶数行透明（alpha == 0，交给父背景）。
     *
     * 数据行原本就有斑马纹 —— 这条是防回归，不是新功能。
     */
    @Test
    fun `odd route rows are striped like the property table`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { RoutesTab(routes(), brokers()) } }
        waitForIdle()

        fun edgeAlpha(tag: String): Float {
            val map = onAllNodesWithTag(tag)[0].captureToImage().toPixelMap()
            return map[map.width - 6, map.height / 2].alpha
        }

        val plain = edgeAlpha(ROUTE_PLAIN_ROW_TAG)
        val zebra = edgeAlpha(ROUTE_ZEBRA_ROW_TAG)
        assertTrue(zebra > 0f, "斑马行必须自己画背景（实测 alpha=$zebra）")
        assertTrue(plain == 0f, "普通行必须保持透明（实测 alpha=$plain）")
        assertNotEquals(plain, zebra)
    }

    @Test
    fun `rows are painted - no blank band`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { RoutesTab(routes(), brokers()) } }
        waitForIdle()

        val ink = inkPixels(onAllNodesWithTag(ROUTE_PLAIN_ROW_TAG)[0].captureToImage().toPixelMap())
        assertTrue(ink > 0, "数据行必须有内容，实测 ink=$ink")
    }

    @Test
    fun `empty routes show the placeholder`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { RoutesTab(emptyList(), emptyList()) } }
        waitForIdle()

        onNodeWithText("暂无路由信息", substring = true).assertExists()
    }

    /**
     * [responsiveTabWidth] 曲线：`min(W, max(0.8W, 900dp))`。
     *
     * 用纯函数单测而不是渲染测宽度：Compose 测试根视口只有约 1024dp，
     * `Modifier.size(1600.dp)` 会被父约束钳掉，造不出超宽窗口。纯函数不受此限。
     */
    @Test
    fun `responsive width is full width then 900dp floor then 80 percent`() {
        // 小窗口：全宽，不被 0.8W 压窄
        assertEquals(800f, responsiveTabWidth(800.dp).value, 0.01f)
        assertEquals(900f, responsiveTabWidth(900.dp).value, 0.01f)
        // 中窗口：900dp 保底
        assertEquals(900f, responsiveTabWidth(1000.dp).value, 0.01f)
        // 1125dp 是连续点：0.8 × 1125 = 900，两侧无跳变
        assertEquals(900f, responsiveTabWidth(1125.dp).value, 0.01f)
        assertTrue(
            kotlin.math.abs(responsiveTabWidth(1126.dp).value - 900.8f) < 0.01f,
            "1126dp 应为 900.8dp（80% 分支起点），实测 ${responsiveTabWidth(1126.dp).value}dp",
        )
        // 大窗口：80% 跟随，且随对话框线性变化
        assertEquals(1280f, responsiveTabWidth(1600.dp).value, 0.01f)
        assertEquals(1520f, responsiveTabWidth(1900.dp).value, 0.01f)
        assertTrue(responsiveTabWidth(1900.dp) > responsiveTabWidth(1600.dp))
    }

    /**
     * 渲染守卫：[ResponsiveTabContainer] 内容居中、不再贴边（1000dp 容器 →
     * 900dp 内容、左右各 50dp）。两 Tab 共用该容器，因此也钉住了内容左边缘对齐。
     */
    @Test
    fun `tab content is centered and not flush with the edge`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 400.dp)) {
                    ResponsiveTabContainer { RoutesTab(routes(), brokers()) }
                }
            }
        }
        waitForIdle()

        val row = onAllNodesWithTag(ROUTE_PLAIN_ROW_TAG)[0].fetchSemanticsNode().boundsInRoot
        assertTrue(
            row.width > 890f && row.width < 910f,
            "1000dp 容器内容应保底 900dp，实测 ${row.width}dp",
        )
        assertTrue(row.left >= 40f, "内容应居中留白约 50dp，实测 left=${row.left}dp")
    }
}
