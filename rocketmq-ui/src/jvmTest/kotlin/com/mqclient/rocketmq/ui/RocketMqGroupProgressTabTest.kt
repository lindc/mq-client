@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rocketmq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.rocketmq.RocketMqGroupDetailSnapshot
import com.mqclient.rocketmq.RocketMqProgressState
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * 「消费进度」Tab 的降级文案：**每种原因一句自己的解释**。
 *
 * 现场：所有失败都显示同一句「消费进度查询失败，可点击重试，或检查服务端连接与权限」，
 * 用户无法判断是没消费过、ACL 还是超时。根因在 service 层（CODE 17 的识别被包装异常
 * 抹掉了类型，见 `RocketMqProgressFailureTest`）；这里钉住 UI 侧的另一半：
 * 拿到分类之后必须**说得不一样**，未知失败必须把原始摘要带出来。
 */
class RocketMqGroupProgressTabTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RocketMqUI().i18nTables)
    }

    private fun detail(
        state: RocketMqProgressState,
        error: String? = null,
    ) = RocketMqGroupDetailSnapshot(
        name = "CG_Normal",
        configFound = true,
        consumeEnable = true,
        orderly = false,
        consumeMode = "CLUSTERING",
        subscriptions = listOf("ChillbearNormal"),
        memberCount = 0,
        progressState = state,
        progressError = error,
        rows = emptyList(),
        totalLag = 0,
    )

    @Test
    fun `code 17 explains the missing retry topic route`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme { ProgressTab(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE)) {} }
        }
        waitForIdle()
        onNodeWithText("无法获取该消费组的详细位点信息。").assertExists()
        onNodeWithText("该消费组在 NameServer 中缺少重试 Topic 的路由信息", substring = true).assertExists()
    }

    @Test
    fun `code 206 explains that the offset table is empty`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme { ProgressTab(detail(RocketMqProgressState.NO_CONSUME_STATS)) {} }
        }
        waitForIdle()
        onNodeWithText("该消费组没有可显示的消费进度", substring = true).assertExists()
    }

    @Test
    fun `permission failure points at the acl config`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme { ProgressTab(detail(RocketMqProgressState.NO_PERMISSION)) {} }
        }
        waitForIdle()
        onNodeWithText("当前账号可能没有访问该消费组的权限，请检查 ACL 配置。").assertExists()
    }

    /**
     * 未知失败必须把**原始错误**带到界面上 —— 这是本次修复的核心诉求：
     * 「加载失败，但无法得知具体原因」。
     */
    @Test
    fun `unknown failure shows the raw error detail`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                ProgressTab(detail(RocketMqProgressState.FAILED, error = "CODE: 25 DESC: something odd")) {}
            }
        }
        waitForIdle()
        onNodeWithText("获取消费进度失败：CODE: 25 DESC: something odd").assertExists()
    }

    @Test
    fun `unknown failure without detail falls back to the generic hint`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme { ProgressTab(detail(RocketMqProgressState.FAILED, error = null)) {} }
        }
        waitForIdle()
        onNodeWithText("消费进度查询失败，可点击重试，或检查服务端连接与权限。").assertExists()
    }

    @Test
    fun `every degraded state still offers a retry button`() = runComposeUiTest {
        loadTables()
        val states = listOf(
            RocketMqProgressState.RETRY_TOPIC_NO_ROUTE,
            RocketMqProgressState.NO_CONSUME_STATS,
            RocketMqProgressState.NO_PERMISSION,
            RocketMqProgressState.FAILED,
        )
        states.forEach { state ->
            var retried = 0
            setContent { MaterialTheme { ProgressTab(detail(state), onRetry = { retried++ }) } }
            waitForIdle()
            onNodeWithText("重试").assertExists()
        }
    }

    @Test
    fun `ok state with rows renders the table instead of a banner`() = runComposeUiTest {
        loadTables()
        val withRow = detail(RocketMqProgressState.OK).copy(
            rows = listOf(
                com.mqclient.rocketmq.RocketMqOffsetRowSnapshot(
                    topic = "ChillbearNormal",
                    broker = "broker-a",
                    queueId = 0,
                    maxOffset = 100,
                    consumerOffset = 40,
                    lag = 60,
                ),
            ),
            totalLag = 60,
        )
        setContent { MaterialTheme { ProgressTab(withRow) {} } }
        waitForIdle()
        onNodeWithText("broker-a").assertExists()
        onNodeWithText("100").assertExists()
    }
}
