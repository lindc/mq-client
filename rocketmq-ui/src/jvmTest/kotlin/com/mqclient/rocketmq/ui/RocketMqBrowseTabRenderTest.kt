@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mqclient.rocketmq.RocketMqMessage
import com.mqclient.rocketmq.RocketMqMessagePage
import com.mqclient.rocketmq.RocketMqMessageService
import com.mqclient.rocketmq.RocketMqReadStart
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 全 Tab 渲染回归：用反射把 RocketMqModule 的私有 messageService 换成内存假实现，
 * 验证「读取成功 -> 列表实际渲染出数据行」与右上角计数一致，不复现
 * 「已加载 N 条但列表空白」的回归。
 */
private fun fakeMsg(queueId: Int, offset: Long, msgId: String) = RocketMqMessage(
    topic = "orders",
    queueId = queueId,
    offset = offset,
    msgId = msgId,
    tags = "tagA",
    keys = "order-42",
    bornTimestamp = 1700000000000L,
    storeTimestamp = 1700000000000L,
    bornHost = "10.0.0.1:1234",
    valuePreview = "order payload " + msgId,
    valueHex = "3c7770663a",
    valueProbablyBinary = false,
    valueSize = 16,
    body = ("order payload " + msgId).toByteArray(Charsets.UTF_8),
    userProperties = emptyMap(),
)

private class FakeService : RocketMqMessageService() {
    override suspend fun fetchQueueIds(connectionId: String, topic: String): List<Int> = listOf(0, 1)

    override suspend fun readMessages(
        connectionId: String,
        topic: String,
        queueId: Int?,
        start: RocketMqReadStart,
        count: Int,
        cursor: Map<Int, Long>?,
    ): RocketMqMessagePage {
        return RocketMqMessagePage(
            messages = listOf(
                fakeMsg(0, 3, "MSG-FAKE-ONE"),
                fakeMsg(1, 7, "MSG-FAKE-TWO"),
            ),
            nextBeginOffset = null,
        )
    }
}

class RocketMqBrowseTabRenderTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RocketMqUI().i18nTables)
    }

    @Test
    fun loadedMessagesActuallyRenderAsListRows() = runComposeUiTest {
        loadTables()
        val moduleClass = Class.forName("com.mqclient.rocketmq.RocketMqModule")
        val modInstance = moduleClass.getField("INSTANCE").get(null)
        val svcField = moduleClass.getDeclaredField("messageService")
        svcField.isAccessible = true
        val previous = svcField.get(modInstance)
        svcField.set(modInstance, FakeService())
        try {
            setContent {
                MaterialTheme {
                    RocketMqMessageBrowseTab(
                        connectionId = "c1",
                        topicName = "orders",
                        modifier = Modifier.size(800.dp, 600.dp),
                    )
                }
            }
            // Idle 门控复现验证：打开对话框只渲染引导空态，**不自动拉取**。
            // 先确认引导文案在场、消息行不存在（readMessages 一次都没被调），
            // 再模拟用户点「读取」触发首页加载。
            var idleHintShown = false
            repeat(20) {
                try {
                    onNodeWithText("点击「读取」按钮加载消息").assertExists()
                    idleHintShown = true
                    return@repeat
                } catch (_: Throwable) {
                    awaitIdle()
                }
            }
            assertTrue(idleHintShown, "打开对话框应先渲染 Idle 引导空态，而不是自动拉取")
            onAllNodesWithText("MSG-FAKE-ONE").assertCountEquals(0)
            onNodeWithText("读取").performClick()
            // 轮询等 LaunchedEffect 跑完（fetchQueueIds + readMessages -> Ready(2)）。
            var oneFound = false
            repeat(100) {
                try {
                    onAllNodesWithText("MSG-FAKE-ONE").assertCountEquals(1)
                    oneFound = true
                    return@repeat
                } catch (_: Throwable) {
                    awaitIdle()
                }
            }
            assertTrue(oneFound, "消息行 MSG-FAKE-ONE 应当被渲染出来")
            onAllNodesWithText("MSG-FAKE-ONE").assertCountEquals(1)
            onAllNodesWithText("MSG-FAKE-TWO").assertCountEquals(1)
        } finally {
            svcField.set(modInstance, previous)
        }
    }
}
