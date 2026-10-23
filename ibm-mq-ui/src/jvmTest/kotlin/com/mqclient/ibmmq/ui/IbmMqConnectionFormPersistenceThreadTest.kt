package com.mqclient.ibmmq.ui

import com.mqclient.uitestkit.RecordedPersist
import com.mqclient.uitestkit.RecordingConnectionPersistence
import com.mqclient.uitestkit.UiThreadHarness
import com.mqclient.uitestkit.assertOffUiThread
import com.mqclient.uitestkit.runOnUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 表单落盘**线程归属**的契约测试：写 `config.toml` 必须离开 UI 线程。
 *
 * 修的是「保存时卡界面」——`ConnectionStore.create/update` 要读整份配置、对敏感字段做
 * AES 加密、再原子换文件，压在主线程上会随连接数增长肉眼可见地卡。
 *
 * 这里的「UI 线程」是 [UiThreadHarness] 的单线程调度器：与真实界面线程在语义上同构
 * （同一时刻只跑一个任务、协程恢复都回这条线程），而它可被断言。
 *
 * 走的是 `submit()` 在「模块未初始化」时的直存分支（无头环境：跳过连接测试直接落盘），
 * 因此不需要真队列管理器。
 */
class IbmMqConnectionFormPersistenceThreadTest {

    @Test
    fun `submit persists on an IO thread, not on the form dispatcher`() {
        UiThreadHarness("ibm-mq-form-ui").use { ui ->
            val persisted = RecordingConnectionPersistence()
            val state = IbmMqConnectionFormState("persist-thread-test", ui.scope, persisted)
            // 只填必填项，其余用默认值 —— validate() 因此可以通过，落盘链路才会被执行。
            state.queueManager = "QM1"
            state.host = "localhost"

            ui.runOnUi { state.submit() }

            val call = persisted.awaitFirst()
            assertNotNull(call, "submit() 之后应该发生一次落盘")
            assertEquals(RecordedPersist.Operation.CREATE, call.operation, "新建走 create")
            assertEquals("persist-thread-test", call.connection.name)
            assertEquals("QM1", call.connection.mqName)
            assertOffUiThread(ui, call)
        }
    }

    @Test
    fun `editing an existing connection persists via update`() {
        UiThreadHarness("ibm-mq-form-ui-edit").use { ui ->
            val persisted = RecordingConnectionPersistence()
            val state = IbmMqConnectionFormState("persist-thread-test", ui.scope, persisted)
            // 编辑模式：loadFrom 会带入 id 并令 doSave 走 update 分支；
            // 它同时会把字段重置为 options 里的值，所以必填项要在它之后再填。
            state.loadFrom(
                com.mqclient.core.ConnectionInfo(
                    id = "c-existing",
                    name = "existing",
                    serviceId = "ibm-mq",
                    mqName = "QM1",
                    host = "localhost",
                    port = 1414,
                ),
            )
            state.queueManager = "QM1"
            state.host = "localhost"

            ui.runOnUi { state.submit() }

            val call = persisted.awaitFirst()
            assertNotNull(call, "编辑模式也应该落盘")
            assertEquals(RecordedPersist.Operation.UPDATE, call.operation, "编辑走 update")
            assertOffUiThread(ui, call)
        }
    }
}
