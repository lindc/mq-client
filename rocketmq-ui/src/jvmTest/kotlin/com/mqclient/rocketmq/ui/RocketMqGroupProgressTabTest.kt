@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rocketmq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.rocketmq.RocketMqGroupDetailSnapshot
import com.mqclient.rocketmq.RocketMqProgressSource
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

    /**
     * CODE 17 + 组内**有成员** → 说明是重试队列路由缺失（组本身存在且跑过）。
     *
     * 零成员的情形归 `RocketMqProgressHintTest`：那时更准确的解释是「还没有消费进度」，
     * 而不是「重试队列无路由」—— 后者对用户没有行动指引。
     */
    @Test
    fun `code 17 with members explains the missing retry topic route`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                ProgressTab(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE).copy(memberCount = 3)) {}
            }
        }
        waitForIdle()
        onNodeWithText("无法获取该消费组的详细位点信息。").assertExists()
        onNodeWithText("该消费组在 NameServer 中缺少重试 Topic 的路由信息", substring = true).assertExists()
    }

    /** CODE 17 + 零成员 → 更准确的说法是「还没有消费进度」。 */
    @Test
    fun `code 17 without members says there is no progress yet`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme { ProgressTab(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE)) {} }
        }
        waitForIdle()
        onNodeWithText("该消费组还没有消费进度。").assertExists()
        onNodeWithText("组配置存在，但没有任何在线消费者", substring = true).assertExists()
    }

    /** 组在 broker 上不存在 → 直接说名字/删除，不再绕「重试队列」。 */
    @Test
    fun `missing group is named explicitly`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                ProgressTab(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE).copy(groupMissing = true)) {}
            }
        }
        waitForIdle()
        onNodeWithText("该消费组不存在。").assertExists()
        onNodeWithText("名称可能拼错，或该组已被删除", substring = true).assertExists()
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

    /**
     * 兜底取到的数据必须**标注来源**。
     *
     * 同一张表，之前说「查不到」、现在又列出位点 —— 不说清楚，用户会以为组级查询
     * 其实成功了，或者怀疑数据是假的。
     */
    @Test
    fun `broker fallback data is annotated with its source`() = runComposeUiTest {
        loadTables()
        val fallback = detail(RocketMqProgressState.OK).copy(
            progressSource = RocketMqProgressSource.BROKER_FALLBACK,
            rows = listOf(
                com.mqclient.rocketmq.RocketMqOffsetRowSnapshot(
                    topic = "ChillbearNormal",
                    broker = "broker-b",
                    queueId = 0,
                    maxOffset = 1,
                    consumerOffset = 1,
                    lag = 0,
                ),
            ),
        )
        setContent { MaterialTheme { ProgressTab(fallback) {} } }
        waitForIdle()

        onNodeWithText("位点由 Broker 直读取得", substring = true).assertExists()
        onNodeWithText("broker-b").assertExists()
    }

    @Test
    fun `group level data has no fallback annotation`() = runComposeUiTest {
        loadTables()
        val normal = detail(RocketMqProgressState.OK).copy(
            progressSource = RocketMqProgressSource.GROUP,
            rows = listOf(
                com.mqclient.rocketmq.RocketMqOffsetRowSnapshot(
                    topic = "ChillbearNormal",
                    broker = "broker-a",
                    queueId = 0,
                    maxOffset = 2,
                    consumerOffset = 1,
                    lag = 1,
                ),
            ),
        )
        setContent { MaterialTheme { ProgressTab(normal) {} } }
        waitForIdle()

        onNodeWithText("位点由 Broker 直读取得", substring = true).assertDoesNotExist()
        onNodeWithText("broker-a").assertExists()
    }
}
