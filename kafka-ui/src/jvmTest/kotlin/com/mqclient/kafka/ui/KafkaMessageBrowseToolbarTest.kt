@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.kafka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 消息浏览页「起点选择区」的尺寸守卫。
 *
 * 现场问题：拉取条数是 m3 `OutlinedTextField`（默认 56dp），紧挨着 40dp 的「读取」
 * 按钮 —— 一行里两个控件差 16dp，视觉上像贴歪了。现在换成 `ui-common` 的
 * `CompactTextField`（无 label 时 40dp）。
 *
 * 这条测试不查「用了哪个组件」（源码看一眼就知道），而是量**渲染后的实际高度**：
 * 条数框与「读取」按钮的高度差必须 < 1dp。降级回 `OutlinedTextField` 会立刻红。
 *
 * 量的是框体（`CompactTextField` 的 Column）而不是框内文字节点：`hasSetTextAction()`
 * 命中的是 20dp 的行内节点，量不到 40dp 的边框 —— 所以两处调用点带了 testTag。
 */
class KafkaMessageBrowseToolbarTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, KafkaUI().i18nTables)
    }

    @Test
    fun `count field matches the read button height`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                StartSelectorRow(
                    partitionCount = 3,
                    selectedPartition = -1,
                    onPartitionChange = {},
                    startMode = StartMode.LATEST,
                    onStartModeChange = {},
                    offsetText = "",
                    onOffsetChange = {},
                    pickedTime = null,
                    onPickTime = {},
                    onClearTime = {},
                    countText = "50",
                    onCountChange = {},
                    onRead = {},
                    reading = false,
                )
            }
        }
        waitForIdle()

        val fieldHeight = onAllNodesWithTag(BROWSE_COUNT_FIELD_TAG)[0].fetchSemanticsNode().boundsInRoot.height
        val buttonHeight = onNodeWithText("读取").fetchSemanticsNode().boundsInRoot.height

        assertTrue(
            abs(fieldHeight - 40f) < 1f,
            "条数输入框必须是 40dp 紧凑档（实测 ${fieldHeight}dp）—— " +
                "回退到 OutlinedTextField 会是 56dp。",
        )
        assertTrue(
            abs(fieldHeight - buttonHeight) < 1f,
            "条数框与「读取」按钮必须等高：输入框 ${fieldHeight}dp vs 按钮 ${buttonHeight}dp",
        )
    }

    /**
     * OFFSET 模式多出来的位移框也必须是紧凑档 —— 它和条数框原来是同一个
     * `OutlinedSmallField`，只改一处会留下第二个 56dp 的错位。
     */
    @Test
    fun `offset field is compact as well`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                StartSelectorRow(
                    partitionCount = 3,
                    selectedPartition = 0,
                    onPartitionChange = {},
                    startMode = StartMode.OFFSET,
                    onStartModeChange = {},
                    offsetText = "1234",
                    onOffsetChange = {},
                    pickedTime = null,
                    onPickTime = {},
                    onClearTime = {},
                    countText = "50",
                    onCountChange = {},
                    onRead = {},
                    reading = false,
                )
            }
        }
        waitForIdle()

        val heights = onAllNodesWithTag(BROWSE_OFFSET_FIELD_TAG).fetchSemanticsNodes()
            .plus(onAllNodesWithTag(BROWSE_COUNT_FIELD_TAG).fetchSemanticsNodes())
            .map { it.boundsInRoot.height }
        assertTrue(heights.size == 2, "OFFSET 模式应有位移 + 条数两个输入框，实测 ${heights.size}")
        assertTrue(
            heights.all { abs(it - heights[0]) < 1f },
            "位移框与条数框必须等高（实测 $heights）",
        )
    }
}
