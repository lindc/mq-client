package com.mqclient.kafka.ui

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
 * Kafka 表单落盘的线程归属与防重入契约。
 *
 * 与 IBM MQ 表单的差别：它的 `submit()` 没有「模块未初始化 → 直接落盘」的短路，所以
 * 落盘走「测试连接失败 → 用户选『仍要保存』」那条 `confirmSave()` 路径 —— 正是异步化
 * 改动最集中的地方。
 */
class KafkaConnectionFormPersistenceThreadTest {

    @Test
    fun `confirmSave persists on an IO thread, not on the form dispatcher`() {
        UiThreadHarness("kafka-form-ui").use { ui ->
            val persisted = RecordingConnectionPersistence()
            val state = KafkaConnectionFormState("persist-thread-test", ui.scope, persisted)
            state.bootstrapServers = "localhost:9092"

            // submit() 会去试连（测试环境里模块未初始化 → 立刻失败），状态落到 Failed。
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
            assertEquals(9092, call.connection.port)
            assertOffUiThread(ui, call)
        }
    }

    @Test
    fun `double clicking save writes exactly once`() {
        UiThreadHarness("kafka-form-ui-double").use { ui ->
            val persisted = RecordingConnectionPersistence()
            val state = KafkaConnectionFormState("persist-thread-test", ui.scope, persisted)
            state.bootstrapServers = "localhost:9092"

            ui.runOnUi { state.submit() }
            assertTrue(ui.awaitCondition { state.saveState is SaveState.Failed })

            // 落盘已改为异步：这段窗口里「保存」按钮仍在，第二次点击必须被挡住
            //（ConnectionStore.update 每次换发新 id，重复执行不会报「已存在」）。
            ui.onUi {
                state.confirmSave()
                state.confirmSave()
            }

            assertNotNull(persisted.awaitFirst(), "至少要有一次落盘")
            // 给可能发生的第二次落盘留出时间，再断言只有一次。
            ui.drain()
            Thread.sleep(50)
            assertEquals(1, persisted.calls.size, "重复点击『保存』不该写出两条连接")
        }
    }
}
