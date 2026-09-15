package com.mqclient.core

import com.mqclient.core.event.EventNames
import com.mqclient.core.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 连接数据的日志 tag；与上报错误的 source（`core.connection`）同一命名。 */
private const val LOG_TAG = "core.connection"

/**
 * 连接列表：**所有模块共同依赖的唯一入口**，背后是单文件配置 `~/.mqclient/config.json`
 * 的 `connections` 段（见 [ConfigManager]）。
 *
 * 三类调用者各走各的门：
 *  - **写入**（新建 / 编辑 / 删除）只经过这里：[create] / [update] / [delete] 落盘成功后
 *    才换快照，并按新旧差异广播 `core.connection.added/updated/deleted`（见 [saveAll]）；
 *  - **读取**走 [connections]（[StateFlow]）。快照拿到手就当归一化数据看待 ——
 *    改它不会写盘、也不会通知任何人；
 *  - **模块 → 核心**的连接状态上报走 [reportStatus]：模块连上 / 连不上之后，把
 *    [ConnectionInfo.status] 的事实报回来，界面（`collectAsState()`）自动重绘状态点。
 *
 * 快照用 coroutines 的 [StateFlow] 而不是 Compose 的 State —— app-core 不引 UI 框架，
 * `collectAsState()` 由调用方自己做。
 *
 * 与事件总线的分工：事件是「刚发生了什么」（[EventNames.CONNECTION_ADDED] …），这里是
 * 「现在有哪些」（状态）。启动时已经在配置里的连接不会补发事件，所以模块补注册任务时
 * 读的是这里的快照（见 `IbmMQModule.initialize`）。
 */
object ConnectionStore {

    /**
     * 从磁盘装载连接列表，状态一律重置为 [ConnectionStatus.Disconnected]。
     *
     * 状态是**运行时事实**，进程退出就没了：不做这一步的话，磁盘上上次运行时写下的
     * 「已连接」会在下次启动显示成一个谁也没连上的假绿点。连接有哪些、怎么连，才是
     * 磁盘该记住的事。
     */
    private fun loadFromDisk(): List<ConnectionInfo> =
        ConfigStore.loadConnections()
            .getOrElse { emptyList() }
            .map { it.copy(status = ConnectionStatus.Disconnected) }

    private val _connections = MutableStateFlow(loadFromDisk())

    /** 可观测快照；UI 用 `collectAsState()` 订阅。直接改它无效 —— 新建 / 编辑 / 删除走 [create] / [update] / [delete]。 */
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

    /**
     * 重新读盘（状态同样重置为未连接）。**读失败时保留当前列表**：把失败当成空列表会让
     * 用户的连接在界面上凭空消失（他可能以为被删了，接着重新添加一遍）。失败原因由
     * [ConfigStore] 上报。
     */
    @Synchronized
    fun reloadFromDisk() {
        ConfigStore.loadConnections().onSuccess { loaded ->
            _connections.value = loaded.map { it.copy(status = ConnectionStatus.Disconnected) }
        }
    }

    /**
     * 新建一条连接：追加到列表末尾 → 落盘 → **落盘成功才**广播 `core.connection.added`。
     *
     * [ConnectionInfo.id] 由调用方给（界面按 UUID 生成），**必须唯一**：编辑 / 删除
     * 都按 id 定位，重复 id 直接以失败返回、不写盘。
     */
    @Synchronized
    fun create(connection: ConnectionInfo): Result<Unit> {
        val current = _connections.value
        if (current.any { it.id == connection.id }) {
            return Result.failure(IllegalArgumentException("连接 id '${connection.id}' 已存在，改用 update()"))
        }
        return saveAll(current + connection)
    }

    /**
     * 编辑一条连接：按 id 原位替换（列表顺序不变）→ 落盘 → 广播 `core.connection.updated`。
     *
     * 两条不变式：id 必须存在；**不允许改 [ConnectionInfo.serviceId]** —— 那等于换一套
     * 协议，旧模块的订阅与定时任务要先收掉，语义上就是「删除 + 新建」，所以失败返回，
     * 让调用方走 [delete] + [create]。
     */
    @Synchronized
    fun update(connection: ConnectionInfo): Result<Unit> {
        val current = _connections.value
        val index = current.indexOfFirst { it.id == connection.id }
        if (index < 0) {
            return Result.failure(NoSuchElementException("连接 '${connection.id}' 不存在，改用 create()"))
        }
        val previous = current[index]
        if (previous.serviceId != connection.serviceId) {
            return Result.failure(
                IllegalArgumentException(
                    "不允许修改连接 '${connection.id}' 的 serviceId（${previous.serviceId} → ${connection.serviceId}）；请先删除再新建",
                ),
            )
        }
        return saveAll(current.toMutableList().also { it[index] = connection })
    }

    /**
     * 删除一条连接：摘掉 → 落盘 → 广播 `core.connection.deleted`。
     *
     * 订阅方（各 MQ 模块）收到它来清理自己的定时任务与本地数据；id 不存在以失败返回、
     * 不写盘。
     */
    @Synchronized
    fun delete(connectionId: String): Result<Unit> {
        val current = _connections.value
        if (current.none { it.id == connectionId }) {
            return Result.failure(NoSuchElementException("连接 '$connectionId' 不存在"))
        }
        return saveAll(current.filterNot { it.id == connectionId })
    }

    /**
     * 连接数据的唯一写入路径：把 [list] 整体写进 config.json，**只在落盘成功之后**换内存
     * 快照，并按新旧差异广播连接事件（新增 / 编辑 / 删除各发各的）。
     *
     * 快照必须等落盘成功再换：否则界面会显示一份磁盘上没有的连接列表，下次启动又变回去。
     * 事件同理 —— 发出去的 `core.connection.added` 意味着「磁盘上已经有了」，模块据此
     * 建起来的任务才不会指向一条下次启动就不存在的连接。
     *
     * 按**差异**而不是「整体重发」：将来若有批量导入之类的调用方整体替换列表，订阅方也
     * 不会把每条连接都当成新建的重建一遍。
     *
     * 失败通过 [Result] 交给调用方决定是提示还是忽略（设置对话框已经能消费这个结果）。
     */
    @Synchronized
    fun saveAll(list: List<ConnectionInfo>): Result<Unit> {
        val previous = _connections.value
        val result = ConfigStore.saveConnections(list)
        if (result.isSuccess) {
            _connections.value = list
            broadcastChanges(previous, list)
        }
        return result
    }

    /**
     * **模块 → 核心**：上报一条连接的真实状态（正在连 / 已连接 / 连接失败）。
     *
     * 这是「连接状态上报」的约定，也是 [ConnectionInfo.status] 的**唯一写入点**：模块不要
     * 自己改快照（改了不会写盘，也不会通知任何人）。约定三条：
     *  - 只改内存状态、**不落盘**：状态是运行时事实，重启从 [ConnectionStatus.Disconnected]
     *    重新开始（见 [loadFromDisk]），不该为它反复写配置；
     *  - 变化立刻反映到 [connections]，界面（状态点 / 状态文字）跟着重绘，不需要额外的
     *    刷新调用；
     *  - 状态没变直接返回，[StateFlow] 不会重发 —— 定时扫描每 5 秒上报一次「还是已连接」
     *    也不会推着界面重组。
     *
     * @param source 报告方（模块 id，如 `ibm-mq`），只进日志。核心不校验连接归属，
     *   也允许 `core` 自己上报。
     * @return 状态确实变了返回 true；连接不存在或状态没变返回 false。
     */
    @Synchronized
    fun reportStatus(connectionId: String, status: ConnectionStatus, source: String = "core"): Boolean {
        val current = _connections.value
        val index = current.indexOfFirst { it.id == connectionId }
        if (index < 0) {
            Log.warn(LOG_TAG, "status report from '$source' for unknown connection '$connectionId' ignored")
            return false
        }
        if (current[index].status == status) return false

        _connections.value = current.toMutableList().also { it[index] = current[index].copy(status = status) }
        Log.debug(LOG_TAG, "connection '$connectionId' -> ${status.name} (reported by $source)")
        return true
    }

    /** 对比新旧快照，把变化逐条广播出去（新增 / 内容变化 / 删除）。 */
    private fun broadcastChanges(previous: List<ConnectionInfo>, current: List<ConnectionInfo>) {
        val before = previous.associateBy { it.id }
        val after = current.associateBy { it.id }

        current.filter { it.id !in before }.forEach { emitChange(EventNames.CONNECTION_ADDED, it) }
        current
            .filter { connection -> before[connection.id]?.let { it != connection } == true }
            .forEach { emitChange(EventNames.CONNECTION_UPDATED, it) }
        previous.filter { it.id !in after }.forEach { emitChange(EventNames.CONNECTION_DELETED, it) }
    }

    /**
     * 广播一条连接事件；payload 与 [EventNames] 里三兄弟的文档一致
     * （`connectionId` / `type`(serviceId) / `name`），模块按 `type` 认领自己的连接。
     *
     * `CoreModule` 还没初始化（无头脚本 / 纯单测，没有界面要刷新）时静默跳过：连接列表
     * 本身照样写盘、照样更新，只是此刻没人订阅；当前状态随时可以从 [connections] 读。
     */
    private fun emitChange(name: String, connection: ConnectionInfo) {
        val bus = CoreModule.eventBusOrNull ?: return
        bus.emit(
            name = name,
            payload = mapOf(
                "connectionId" to connection.id,
                "type" to connection.serviceId,
                "name" to connection.name,
            ),
            source = "core",
        )
    }
}
