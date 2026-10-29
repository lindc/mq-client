@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rocketmq.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import com.mqclient.rocketmq.RocketMqSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 发送状态机：**`busy` 不能卡在 true**（否则对话框的 ✕ / ESC 永久失效）。
 *
 * 现场：「消息详情对话框打开几次之后整个卡死，点关闭毫无反应」。这条链路是：
 *
 * ```
 * RocketMqMessagesDialog.onCloseRequest = { if (sendState.busy) 弹确认 else onDismiss() }
 * sendState.sendSingle / sendAll：busy = true … busy = false   ← 没有 try/finally
 * doSend 抛异常（连接半路断掉时 connectedContext 直接抛）→ busy 永远为 true
 * ```
 *
 * 于是 ✕ 只会反复弹「发送中确认」，看起来就是「关闭没反应」。
 *
 * 更严重的是第二条：`RocketMqSendMessagesState` 挂在对话框的
 * `rememberCoroutineScope()` 上，未捕获异常会取消**组合的 Job** —— 整个对话框
 * 停止重组，之后即使收到点击也不再重绘。`safeSend` 把异常挡在协程外就是为了这个。
 */
class RocketMqSendStateBusyTest {

    private fun state(
        scope: CoroutineScope,
        sender: suspend (RocketMqSendTask) -> RocketMqSendResult,
    ) = RocketMqSendMessagesState(
        connectionId = "c1",
        topicName = "ChillbearProgressDemo",
        scope = scope,
        sendOverride = sender,
    )

    private fun task(id: String = "t1") = RocketMqSendTask(
        id = id,
        fileName = "a.txt",
        fileSize = 3,
        content = "abc".toByteArray(),
    )

    /** 用真实临时文件走 [loadFiles]（tasks 是 private set，只能这样灌数据）。 */
    private suspend fun seed(s: RocketMqSendMessagesState, count: Int) {
        val dir = kotlin.io.path.createTempDirectory("send-busy")
        val paths = (1..count).map { i ->
            dir.resolve("f$i.txt").also { it.toFile().writeText("abc") }.toString()
        }
        s.loadFiles(paths)
    }

    /**
     * 轮询到条件成立。
     *
     * ⚠️ 等待条件必须是**终态**（如「任务已落成 FAILED」），不能是「busy 已复位」：
     * `busy = true` 是在 `scope.launch` **内部**才写的，`launch` 返回后协程可能还没被
     * 调度 —— 此时 `busy` 仍是 false，「等它变 false」会立刻通过，随后断言的仍是初始
     * 状态。这正是本测试第一版偶发失败（`expected:<FAILED> but was:<WAITING>`）的原因。
     */
    private suspend fun awaitUntil(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("等待超时：$what")
    }

    @Test
    fun `sendAll that throws still clears busy and marks the task failed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val s = state(scope) { throw IllegalStateException("连接已断开，无法发送") }
        seed(s, 1)

        s.sendAll()
        // 等终态，不等 busy（见 awaitUntil 的说明）。
        awaitUntil("任务落成失败态") { s.tasks.single().status == SendStatus.FAILED }

        assertFalse(s.busy, "发送抛异常后 busy 必须复位，否则 ✕ / ESC 永久失效")
        assertTrue(
            s.tasks.single().errorMessage?.contains("连接已断开") == true,
            "真实原因要带给用户，实测：${s.tasks.single().errorMessage}",
        )
        scope.cancel()
    }

    @Test
    fun `sendSingle that throws clears busy too`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val s = state(scope) { throw RuntimeException("boom") }
        seed(s, 1)

        s.sendSingle(onEmptyBodyWarning = {})
        awaitUntil("任务落成失败态") { s.tasks.single().status == SendStatus.FAILED }

        assertFalse(s.busy, "发送抛异常后 busy 必须复位")
        scope.cancel()
    }

    /**
     * 异常绝不能穿出协程：它所在的 scope 若被取消，整个对话框就停止重组了。
     * 用一个**普通 Job**（不是 SupervisorJob）模拟组合作用域 —— 子协程失败会
     * 取消父 Job，正如 Compose 的组合 Job。
     */
    @Test
    fun `a send failure does not kill the owning scope`() = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(parent + kotlinx.coroutines.Dispatchers.Default)
        val s = state(scope) { throw IllegalStateException("boom") }
        seed(s, 1)

        s.sendAll()
        awaitUntil("任务落成失败态") { s.tasks.single().status == SendStatus.FAILED }
        yield()

        assertTrue(parent.isActive, "发送失败不能让对话框所在的协程作用域被取消（否则整窗卡死）")
        scope.cancel()
        parent.cancel()
    }

    @Test
    fun `a cancelled batch returns unfinished tasks to waiting and clears busy`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        // 第一条发送挂起，模拟发送中被关掉对话框。
        val s = state(scope) { delay(10_000); RocketMqSendResult.success("m", "b", 0, 0) }
        seed(s, 2)

        s.sendAll()
        awaitUntil("进入发送") { s.busy }
        scope.cancel()
        awaitUntil("发送结束") { !s.busy }

        assertFalse(s.busy)
        assertTrue(
            s.tasks.none { it.status == SendStatus.SENDING },
            "取消后不能有任务卡在 SENDING（那会让「发送全部」再也发不动）",
        )
    }
}
