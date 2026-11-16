package com.mqclient.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 连接注册表的**模块侧视图**（HostFacade 搬家的落点）：模块经 `ServiceContext`
 * 注入它，而不是直取 `ConnectionStore` 单例 —— 注入让模块可以在测试里喂一个
 * 受控实现（见 [Empty]），也让「连接数据从哪来」有一个可替换的缝。
 *
 * 只读 + 状态上报：**写入**（新建 / 编辑 / 删除连接）是宿主 UI 的职责，不经过
 * 这里 —— 模块没有改连接清单的业务。宿主唯一实现是 jvmMain 的
 * `ConnectionStore`（背后是 config.toml，见其 KDoc 的三类调用者分工）。
 *
 * 与事件总线的分工：事件是「刚发生了什么」（`core.connection.added` …），
 * 这里的 [connections] 是「现在有哪些」—— 启动时已经在配置里的连接不会补发
 * 事件，模块补登记任务时读的就是这份快照。
 */
interface ConnectionRegistry {

    /** 可观测的连接快照；UI 侧 `collectAsState()`，模块侧按 id 查找或过滤。 */
    val connections: StateFlow<List<ConnectionInfo>>

    /** 按 id 查一条连接（不存在返回 null）。模块还原协议配置的标准入口。 */
    fun findConnection(connectionId: String): ConnectionInfo?

    /**
     * 连接状态上报：模块连上 / 连不上之后把事实报回来，界面（collectAsState）
     * 自动重绘状态点。状态没变时实现方直接忽略（重复上报无副作用）。
     *
     * 约定沿用项目口径：**连接状态不走事件总线**（状态用 StateFlow，事件是
     * 「刚发生」，见 EventNames）—— 这是模块 → 核心的唯一状态回传通道。
     */
    fun reportStatus(connectionId: String, status: ConnectionStatus, source: String): Boolean

    companion object {

        /**
         * 空实现（无连接、查找恒 null、上报恒 false）—— 测试与无连接场景用，
         * 让 `ServiceContext.of` 的调用方永远有一个安全的无状态选择。
         */
        val Empty: ConnectionRegistry = object : ConnectionRegistry {
            override val connections: StateFlow<List<ConnectionInfo>> =
                MutableStateFlow<List<ConnectionInfo>>(emptyList()).asStateFlow()

            override fun findConnection(connectionId: String): ConnectionInfo? = null

            override fun reportStatus(
                connectionId: String,
                status: ConnectionStatus,
                source: String,
            ): Boolean = false
        }
    }
}
