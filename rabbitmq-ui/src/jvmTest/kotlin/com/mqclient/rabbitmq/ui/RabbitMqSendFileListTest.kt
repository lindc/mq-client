@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.CompactControlSpec
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 发送文件列表的样式守卫（队列发送 Tab 与交换机发送对话框共用 [FileListItem]）。
 *
 * 现场问题：交换机发送对话框里「等待」行看着比「成功」行大一号 —— 等待态渲染了
 * 一个未指定 style 的 `OutlinedButton`（落到 M3 默认 labelLarge），成功态没有按钮，
 * 只有 bodySmall 的状态文字。两个对话框又各有一份渲染实现，于是分叉越走越远。
 *
 * 这里断言的是**渲染结果**（行高、文本节点高度），不是源码里写了什么：字号回归
 * 不会让编译失败，只会让界面安静地又变小一号。
 */
class RabbitMqSendFileListTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RabbitMqUI().i18nTables)
    }

    @Test
    fun `waiting and success rows share the same height and type scale`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                Column(modifier = Modifier.width(600.dp)) {
                    FileListItem(
                        fileName = "waiting.bin",
                        fileSize = 1024,
                        status = SendFileStatus.WAITING,
                        errorMessage = null,
                        busy = false,
                        onRemove = {},
                        onRetry = {},
                        modifier = Modifier.testTag(WAITING_ROW_TAG),
                    )
                    FileListItem(
                        fileName = "success.bin",
                        fileSize = 1024,
                        status = SendFileStatus.SUCCESS,
                        errorMessage = null,
                        busy = false,
                        onRemove = {},
                        onRetry = {},
                        modifier = Modifier.testTag(SUCCESS_ROW_TAG),
                    )
                }
            }
        }
        waitForIdle()

        val waitingRow = onNodeWithTag(WAITING_ROW_TAG)
        val successRow = onNodeWithTag(SUCCESS_ROW_TAG)
        waitingRow.assertHeightIsAtLeast(CompactControlSpec.Height)
        successRow.assertHeightIsAtLeast(CompactControlSpec.Height)

        val waitingRowHeight = waitingRow.fetchSemanticsNode().size.height
        val successRowHeight = successRow.fetchSemanticsNode().size.height
        assertEquals(
            waitingRowHeight,
            successRowHeight,
            "等待行（有「移除」按钮）与成功行（无按钮）必须同高，否则发送前后列表会跳动",
        )

        // 文件名：两态都必须走 bodyMedium。
        val waitingName = onNodeWithText("waiting.bin").fetchSemanticsNode().size.height
        val successName = onNodeWithText("success.bin").fetchSemanticsNode().size.height
        assertEquals(waitingName, successName, "文件名在等待态与成功态必须同字号（bodyMedium）")

        // 状态文字：两态都必须走 bodySmall。
        val waitingStatus = onNodeWithText("等待").fetchSemanticsNode().size.height
        val successStatus = onNodeWithText("成功").fetchSemanticsNode().size.height
        assertEquals(waitingStatus, successStatus, "状态文字在等待态与成功态必须同字号（bodySmall）")

        // 字号层级：bodyMedium 的行高必须大于 bodySmall —— 钉住「文件名比状态文字大」
        // 这条规范，防止有人把文件名也降成 bodySmall。
        assertTrue(
            waitingName > waitingStatus,
            "文件名（bodyMedium, h=$waitingName）必须高于状态文字（bodySmall, h=$waitingStatus）",
        )
    }

    private companion object {
        const val WAITING_ROW_TAG = "send-file-row-waiting"
        const val SUCCESS_ROW_TAG = "send-file-row-success"
    }
}
