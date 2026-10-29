@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mqclient.kafka.KafkaMessageSummary
import com.mqclient.message.interpreter.previewTextOf
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 消息列表 Value 单元格的「绝不整格空白」守卫。
 *
 * 现场 bug：`<wpf:ResourceDictionary…` 这样内容相近的两条消息，一条正常显示前缀，
 * 另一条整格空白。原因是单元格 `Text(maxLines = 1)` 只画第一行，而预览原样返回
 * 解码结果 —— 正文以换行 / BOM 开头时首行没有字形，**一个像素都不画**。
 *
 * 这条测试不看语义树（语义树里文本一直是完整的，空白是**渲染**问题），而是对
 * 单元格截图数「非背景像素」：小于阈值即视为空白。四档兜底各来一发：
 * 普通文本 / 带前导换行的 XML / 全不可见字符 / 零字节 / 疑似二进制。
 */
class KafkaMessageValueCellTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, KafkaUI().i18nTables)
    }

    private fun summary(
        valuePreview: String,
        valueHex: String = "3c7770663a",
        valueProbablyBinary: Boolean = false,
        valueSize: Int = 128,
    ) = KafkaMessageSummary(
        topic = "orders",
        partition = 0,
        offset = 5,
        timestamp = 1_700_000_000_000,
        timestampType = "CREATE_TIME",
        keyPreview = null,
        keyHex = null,
        valuePreview = valuePreview,
        valueHex = valueHex,
        valueProbablyBinary = valueProbablyBinary,
        valueSize = valueSize,
    )

    /** 与左上角底色不同的像素数：0 = 整格空白。 */
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

    private fun inkOf(message: KafkaMessageSummary): Int {
        var ink = 0
        runComposeUiTest {
            loadTables()
            setContent {
                MaterialTheme {
                    ValueCell(
                        message = message,
                        modifier = Modifier.size(320.dp, 24.dp).testTag(VALUE_CELL_PROBE_TAG),
                        onCopy = {},
                    )
                }
            }
            waitForIdle()
            ink = inkPixels(onAllNodesWithTag(VALUE_CELL_PROBE_TAG)[0].captureToImage().toPixelMap())
        }
        return ink
    }

    @Test
    fun `xml payload that starts with newlines is visible`() {
        // 复现路径：payload 以两个换行开头。修复前这里 ink == 0（实测）。
        val raw = "\n\n<wpf:ResourceDictionary xmlns:wpf=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\"/>"
        val message = summary(valuePreview = previewTextOf(raw.toByteArray(Charsets.UTF_8)))
        assertTrue(inkOf(message) > 0, "以换行开头的 XML 预览必须是可见的（修复前整格空白）")
    }

    @Test
    fun `bom prefixed payload is visible`() {
        val raw = "\uFEFF<wpf:ResourceDictionary/>"
        val message = summary(valuePreview = previewTextOf(raw.toByteArray(Charsets.UTF_8)))
        assertTrue(inkOf(message) > 0, "BOM 开头的 XML 预览必须是可见的")
    }

    @Test
    fun `invisible-only payload falls back to hex instead of a blank cell`() {
        // 字节非空、但规范化后预览为空（纯空白 / 全零宽字符）→ 「[不可见内容] + hex」。
        val message = summary(valuePreview = previewTextOf("   \n\t  ".toByteArray()), valueHex = "20200a09")
        assertTrue(inkOf(message) > 0, "全不可见内容必须回落为 hex 文案，不能留白")
    }

    @Test
    fun `zero length value shows the empty placeholder`() {
        val message = summary(valuePreview = "", valueHex = "", valueSize = 0)
        assertTrue(inkOf(message) > 0, "0 字节消息必须显示「[空消息]」，不能留白")
    }

    @Test
    fun `binary payload shows hex`() {
        val message = summary(valuePreview = "\u0000\u0001", valueHex = "00010203", valueProbablyBinary = true)
        assertTrue(inkOf(message) > 0, "疑似二进制必须显示 hex 前段")
    }

    @Test
    fun `plain text payload stays visible`() {
        val message = summary(valuePreview = "order-42 created")
        assertTrue(inkOf(message) > 0, "普通文本预览必须可见")
    }
}

/** Value 单元格探针的测试标签（只在测试里用，不进生产语义）。 */
private const val VALUE_CELL_PROBE_TAG = "value_cell_probe"
