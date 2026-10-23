package com.mqclient.rabbitmq.ui

import com.mqclient.mqui.state.SaveState
import com.mqclient.uitestkit.RecordedPersist
import com.mqclient.uitestkit.RecordingConnectionPersistence
import com.mqclient.uitestkit.UiThreadHarness
import com.mqclient.uitestkit.assertOffUiThread
import com.mqclient.uitestkit.runOnUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RabbitMQ 表单落盘的线程归属：写 `config.toml` 必须离开 UI 线程。
 *
 * 路径与 Kafka 表单一致：`submit()` 试连（测试环境模块未初始化 → 立刻失败）→
 * `confirmSave()` 落盘。
 */
class RabbitMqConnectionFormPersistenceThreadTest {

    @Test
    fun `confirmSave persists on an IO thread, not on the form dispatcher`() {
        UiThreadHarness("rabbitmq-form-ui").use { ui ->
            val persisted = RecordingConnectionPersistence()
            val state = RabbitMqConnectionFormState("persist-thread-test", ui.scope, persisted)
            state.host = "localhost"

            ui.runOnUi { state.submit() }
            assertTrue(
                ui.awaitCondition { state.saveState is SaveState.Failed },
                "模块未初始化时 submit 应落到 Failed，实际 ${state.saveState}",
            )

            ui.runOnUi { state.confirmSave() }

            val call = persisted.awaitFirst()
            assertNotNull(call, "confirmSave() 之后应该发生一次落盘")
            assertEquals(RecordedPersist.Operation.CREATE, call.operation)
            assertEquals("persist-thread-test", call.connection.name)
            assertEquals("localhost", call.connection.host)
            assertOffUiThread(ui, call)
        }
    }
}
