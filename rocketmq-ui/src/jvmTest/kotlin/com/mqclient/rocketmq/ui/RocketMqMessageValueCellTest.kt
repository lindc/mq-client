@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rocketmq.ui

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
import com.mqclient.message.interpreter.previewTextOf
import com.mqclient.rocketmq.RocketMqMessage
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RocketMQ 消息列表 Value 单元格的「绝不整格空白」守卫。
 *
 * 与 `kafka-ui` 的 `KafkaMessageValueCellTest` 同构：这两个模块的 `previewOf` 曾是
 * 逐字相同的两份副本，同一个盲区（`maxLines = 1` + 首行无字形 → 一个像素都不画）
 * 在两边都要挡住。RocketMQ 的 `RocketMqMessage.body` 是必填的 ByteArray，0 字节即
 * 「空消息」—— 与 Kafka 的 `valueSize == 0` 判定同义。
 */
class RocketMqMessageValueCellTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RocketMqUI().i18nTables)
    }

    private fun message(
        body: ByteArray,
        valueHex: String = "3c7770663a",
        valueProbablyBinary: Boolean = false,
        valueSize: Int = body.size,
    ) = RocketMqMessage(
        topic = "orders",
        queueId = 0,
        offset = 5,
        msgId = "AC1400",
        tags = "tagA",
        keys = "order-42",
        bornTimestamp = 1_700_000_000_000,
        storeTimestamp = 1_700_000_000_000,
        bornHost = "10.0.0.1:1234",
        valuePreview = previewTextOf(body),
        valueHex = valueHex,
        valueProbablyBinary = valueProbablyBinary,
        valueSize = valueSize,
        body = body,
        userProperties = emptyMap(),
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

    private fun inkOf(message: RocketMqMessage): Int {
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
    fun `xml body that starts with newlines is visible`() {
        val body = "\n\n<wpf:ResourceDictionary xmlns:wpf=\"x\"/>".toByteArray(Charsets.UTF_8)
        assertTrue(inkOf(message(body)) > 0, "以换行开头的 XML 预览必须是可见的")
    }

    @Test
    fun `bom prefixed body is visible`() {
        val body = "\uFEFF<wpf:ResourceDictionary/>".toByteArray(Charsets.UTF_8)
        assertTrue(inkOf(message(body)) > 0, "BOM 开头的 XML 预览必须是可见的")
    }

    @Test
    fun `invisible-only body falls back to hex instead of a blank cell`() {
        val body = "   \n\t  ".toByteArray(Charsets.UTF_8)
        assertTrue(inkOf(message(body, valueHex = "20200a09")) > 0, "全不可见内容必须回落为 hex，不能留白")
    }

    @Test
    fun `empty body shows the empty placeholder`() {
        assertTrue(inkOf(message(ByteArray(0), valueHex = "", valueSize = 0)) > 0, "0 字节消息必须显示「[空消息]」")
    }

    @Test
    fun `binary body shows hex`() {
        assertTrue(
            inkOf(message(byteArrayOf(0, 1, 2, 3), valueHex = "00010203", valueProbablyBinary = true)) > 0,
            "疑似二进制必须显示 hex 前段",
        )
    }

    @Test
    fun `plain text body stays visible`() {
        assertTrue(inkOf(message("order-42 created".toByteArray())) > 0, "普通文本预览必须可见")
    }
}

private const val VALUE_CELL_PROBE_TAG = "rocketmq_value_cell_probe"
