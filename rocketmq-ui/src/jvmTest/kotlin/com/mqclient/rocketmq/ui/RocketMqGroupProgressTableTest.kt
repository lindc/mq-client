@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rocketmq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.rocketmq.RocketMqGroupDetailSnapshot
import com.mqclient.rocketmq.RocketMqOffsetRowSnapshot
import com.mqclient.rocketmq.RocketMqProgressSource
import com.mqclient.rocketmq.RocketMqProgressState
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「消费进度」表与概览的 `PropertyTable` 视觉对齐。
 *
 * 现场：这一页原来用 `bodySmall` + `labelMedium`（都是 12sp）、无最小行高、
 * 每行统一 15% 底色（不是斑马纹）、行间还有 `HorizontalDivider` —— 与同对话框的
 * 概览差了一整个字号档。断言落在**渲染结果**上：字号回归不会让编译失败。
 */
class RocketMqGroupProgressTableTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RocketMqUI().i18nTables)
    }

    private fun rows(count: Int = 3) = List(count) { i ->
        RocketMqOffsetRowSnapshot(
            topic = "ChillbearProgressDemo",
            broker = "broker-${'a' + i % 3}",
            queueId = i,
            maxOffset = 100L + i,
            consumerOffset = 40L + i,
            lag = 60L,
        )
    }

    private fun detail(rowList: List<RocketMqOffsetRowSnapshot>) = RocketMqGroupDetailSnapshot(
        name = "CG_PROGRESS_DEMO",
        configFound = true,
        consumeEnable = true,
        orderly = false,
        consumeMode = "CLUSTERING",
        subscriptions = listOf("ChillbearProgressDemo"),
        memberCount = 1,
        progressState = RocketMqProgressState.OK,
        progressSource = RocketMqProgressSource.GROUP,
        rows = rowList,
        totalLag = rowList.sumOf { it.lag },
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
    fun `headers and data render`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { ProgressTab(detail(rows()), {}) } }
        waitForIdle()

        onNodeWithText("Broker").assertExists()
        onNodeWithText("Topic").assertExists()
        onNodeWithText("Queue ID").assertExists()
        onNodeWithText("最大位点").assertExists()
        onNodeWithText("消费位点").assertExists()
        onNodeWithText("积压量").assertExists()

        onNodeWithText("broker-a").assertExists()
        onNodeWithText("100").assertExists()
        onNodeWithText("40").assertExists()
    }

    /**
     * 表头必须是 bodyMedium 档（行高 20dp），不是 labelMedium（16dp）。
     *
     * 只断字号没法直接测，用渲染高度间接量：阈值 19dp 能干净分开两者。
     */
    @Test
    fun `header cells are bodyMedium not labelMedium`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { ProgressTab(detail(rows()), {}) } }
        waitForIdle()

        val h = onNodeWithText("Broker").fetchSemanticsNode().boundsInRoot.height
        assertTrue(h >= 19f, "表头必须是 bodyMedium 档（行高 20dp），实测 ${h}dp")
    }

    @Test
    fun `data rows keep the 40dp row height of the property table`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { ProgressTab(detail(rows()), {}) } }
        waitForIdle()

        onAllNodesWithTag(PROGRESS_PLAIN_ROW_TAG)[0].assertHeightIsAtLeast(PROGRESS_ROW_MIN_HEIGHT)
        onAllNodesWithTag(PROGRESS_ZEBRA_ROW_TAG)[0].assertHeightIsAtLeast(PROGRESS_ROW_MIN_HEIGHT)
    }

    /**
     * 斑马纹：奇数行自己画背景（alpha > 0），偶数行透明（alpha == 0）。
     *
     * 原来每行都是 15% 底色 —— 均匀着色不是斑马纹，行与行之间没有区分。
     */
    @Test
    fun `odd rows are striped, even rows are transparent`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { ProgressTab(detail(rows()), {}) } }
        waitForIdle()

        fun edgeAlpha(tag: String): Float {
            val map = onAllNodesWithTag(tag)[0].captureToImage().toPixelMap()
            return map[map.width - 6, map.height / 2].alpha
        }

        val plain = edgeAlpha(PROGRESS_PLAIN_ROW_TAG)
        val zebra = edgeAlpha(PROGRESS_ZEBRA_ROW_TAG)
        assertTrue(zebra > 0f, "斑马行必须自己画背景（实测 alpha=$zebra）")
        assertTrue(plain == 0f, "普通行必须保持透明（实测 alpha=$plain）—— 原来每行 15% 底色就是错在这里")
        assertNotEquals(plain, zebra)
    }

    @Test
    fun `rows are painted`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { ProgressTab(detail(rows()), {}) } }
        waitForIdle()

        val ink = inkPixels(onAllNodesWithTag(PROGRESS_PLAIN_ROW_TAG)[0].captureToImage().toPixelMap())
        assertTrue(ink > 0, "数据行必须有内容，实测 ink=$ink")
    }

    @Test
    fun `empty rows show the placeholder`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { ProgressTab(detail(emptyList()), {}) } }
        waitForIdle()

        onNodeWithText("该消费组暂无消费位点数据（可能从未消费）").assertExists()
    }

    /** 兜底来源的说明行仍在（数据来自 broker 直读时必须标注）。 */
    @Test
    fun `broker fallback annotation still renders above the table`() = runComposeUiTest {
        loadTables()
        val fallback = detail(rows()).copy(progressSource = RocketMqProgressSource.BROKER_FALLBACK)
        setContent { MaterialTheme { ProgressTab(fallback, {}) } }
        waitForIdle()

        onNodeWithText("位点由 Broker 直读取得", substring = true).assertExists()
        onNodeWithText("broker-a").assertExists()
    }
}
