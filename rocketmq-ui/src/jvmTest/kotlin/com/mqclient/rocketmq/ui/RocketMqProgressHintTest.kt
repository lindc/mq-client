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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「消费进度为什么没有数据」的分档诊断（[progressHint]）。
 *
 * 现场反馈：提示只说「无法获取该消费组的详细位点信息」，看不出是组不存在、
 * 没人消费过、还是没权限。而这些信息**本来就在手上**（订阅组配置是否查到、
 * 有多少在线成员、进度查询的归因），只是没被组合起来。
 *
 * 这组用例钉住组合规则，尤其是「同一个 CODE 17 在不同上下文下必须说不同的话」。
 */
class RocketMqProgressHintTest {

    private fun detail(
        state: RocketMqProgressState,
        groupMissing: Boolean = false,
        configFound: Boolean = true,
        memberCount: Int? = 0,
        error: String? = null,
    ) = RocketMqGroupDetailSnapshot(
        name = "CG",
        configFound = configFound,
        groupMissing = groupMissing,
        consumeEnable = true,
        orderly = false,
        consumeMode = "CLUSTERING",
        subscriptions = emptyList(),
        memberCount = memberCount,
        progressState = state,
        progressError = error,
        rows = emptyList(),
        totalLag = 0,
    )

    @Test
    fun `missing group wins over every other signal`() {
        // 组根本不存在：「没权限」「没有位点」都是噪音，先说最根本的。
        assertEquals(
            ProgressHint.GROUP_MISSING,
            progressHint(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, groupMissing = true)),
        )
        assertEquals(
            ProgressHint.GROUP_MISSING,
            progressHint(detail(RocketMqProgressState.FAILED, groupMissing = true, error = "boom")),
        )
    }

    @Test
    fun `permission failure is reported as permission, not as missing data`() {
        assertEquals(
            ProgressHint.NO_PERMISSION,
            progressHint(detail(RocketMqProgressState.NO_PERMISSION, memberCount = 3)),
        )
    }

    @Test
    fun `existing group with no online members is diagnosed as no members`() {
        // 这是最常见的真实原因：组配置在、但从来没有消费者上线过。
        assertEquals(
            ProgressHint.NO_MEMBERS,
            progressHint(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, memberCount = 0)),
        )
        // memberCount = null（连接信息查不到）也按「没有成员」处理
        assertEquals(
            ProgressHint.NO_MEMBERS,
            progressHint(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, memberCount = null)),
        )
    }

    @Test
    fun `same code 17 says different things depending on the group state`() {
        val noMembers = progressHint(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, memberCount = 0))
        val withMembers = progressHint(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, memberCount = 3))
        val missing = progressHint(detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, groupMissing = true))

        assertNotEquals(noMembers, withMembers, "有成员时不能说「没有成员」")
        assertNotEquals(withMembers, missing, "组不存在时不能说「重试队列无路由」")
        assertEquals(ProgressHint.RETRY_TOPIC_NO_ROUTE, withMembers)
    }

    @Test
    fun `206 and unknown failures keep their own buckets`() {
        assertEquals(
            ProgressHint.NO_CONSUME_STATS,
            progressHint(detail(RocketMqProgressState.NO_CONSUME_STATS, memberCount = 2)),
        )
        assertEquals(ProgressHint.FAILED, progressHint(detail(RocketMqProgressState.FAILED, error = "timeout")))
    }

    /**
     * 每一档的文案都必须真的存在。
     *
     * 存在的理由：`progressHintTitleKey` 这类「函数返回 key」的写法**绕过了 i18n
     * 静态引用检查**（脚本只认 `stringResource("字面量")`），上一版就有个
     * `rocketmq_ui_group_props_progress_denied` 从未定义、界面上直接显示裸 key。
     * 这里在渲染层把它补上：每个分档都必须能查出真实文案，而不是 key 本身。
     */
    @Test
    fun `every hint renders a real translation not a raw key`() = runComposeUiTest {
        runBlocking { LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RocketMqUI().i18nTables) }
        // 每一档对应一句必须出现在界面上的中文（= 真的查到了翻译，而不是裸 key）。
        val cases = listOf(
            detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, groupMissing = true) to "该消费组不存在。",
            detail(RocketMqProgressState.NO_PERMISSION, memberCount = 3) to "没有访问该消费组的权限。",
            detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, memberCount = 0) to "该消费组还没有消费进度。",
            detail(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, memberCount = 3) to "无法获取该消费组的详细位点信息。",
            detail(RocketMqProgressState.NO_CONSUME_STATS, memberCount = 2) to "无法获取该消费组的详细位点信息。",
            detail(RocketMqProgressState.FAILED, error = "CODE: 25 DESC: boom") to "无法获取该消费组的详细位点信息。",
        )
        cases.forEach { (snapshot, expectedTitle) ->
            setContent { MaterialTheme { ProgressTab(snapshot) {} } }
            waitForIdle()
            onNodeWithText(expectedTitle).assertExists()
        }
    }
}
