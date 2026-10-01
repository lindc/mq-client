package com.mqclient.core

import com.mqclient.core.spi.ServiceContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pure (non-UI) descriptor of a business module, discovered automatically via
 * Java SPI ([java.util.ServiceLoader]). Each `*-service` module provides one
 * implementation and registers it under `META-INF/services/com.mqclient.core.ServiceAdapter`.
 */
interface ServiceAdapter {
    /** Stable identifier, e.g. "ibm-mq". */
    val id: String

    /** Human readable name shown in the UI. */
    val name: String

    /** Short description of what the module does. */
    val description: String

    /**
     * Wire protocol the module speaks, e.g. "AMQP 0.9.1" or "Kafka Protocol".
     * Surfaced in the About dialog so users know which transports are bundled
     * without the UI needing to know about individual service modules.
     */
    val protocol: String

    /**
     * 类目名，新建连接对话框按它把各模块分到不同的 tab（如 `"MQ"`、`"AI"`）。
     *
     * 各模块**自己持有字符串常量** —— app-core 不集中定义取值，新增类目不需要改
     * 这里；tab 的生成完全来自 SPI 扫描（见 [ModuleRegistry] 与对话框侧的
     * `groupByCategory`）。
     */
    val category: String

    /**
     * 模块初始化钩子：由 [ModuleRegistry.initializeServices] 在启动时调用，模块在这里
     * 注册自己的事件订阅（见 [ServiceContext]）。
     *
     * 默认空实现 —— 纯描述性的模块什么都不用写，新增模块也不需要修改启动代码。
     */
    fun initialize(context: ServiceContext) {}

    /**
     * 提供一条连接的右键菜单项。默认实现返回 [DefaultConnectionMenu] 的通用菜单
     * （连接/断开合并项、复制、测试、查看队列管理器属性、删除）；模块有特有动作时
     * 覆盖它 —— 可以调 [DefaultConnectionMenu.build] 拿通用菜单再追加 / 替换。
     *
     * 返回的是**纯数据描述**（见 [ContextMenuItem]），渲染统一由宿主完成；
     * 宿主在渲染前会过滤连续 / 首尾的分隔线。
     *
     * 「查看队列管理器属性」这个通用项只对注册了 [ConnectionPropertiesUi] 的模块
     * 生效：宿主按 [DefaultConnectionMenu.ITEM_QMGR_PROPERTIES] 过滤，模块侧无需关心。
     */
    fun contextMenuItems(
        connection: ConnectionInfo,
        actions: ConnectionActions,
    ): List<ContextMenuItem> = DefaultConnectionMenu.build(connection, actions)

    /**
     * 提供一条连接在导航树里展开后的**二级菜单项**。默认空 —— 不贡献导航的模块
     * （纯描述性 / 还没做面板的）展开后没有子项。
     *
     * 返回的是**纯数据描述**（见 [NavigationItem]），渲染与点击行为统一由宿主完成
     * （点击 = 打开对应 Tab，同一 id 已打开则切换）；工具类与对象类条目由模块用
     * 分隔线分组（`NavigationItem.separator(id)`）。
     *
     * [connection] 目前只用于模块按连接定制（将来做 per-connection 徽章等）；
     * 静态菜单直接忽略它。宿主会缓存返回值（按连接 id），不要在这里做昂贵计算。
     */
    fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = emptyList()

    /**
     * 一条连接的**二级菜单数量徽章**（导航项 id → 对象数量）：全面扫描完成后由模块
     * 写入并发布，宿主 `collectAsState(initial = emptyMap())` 消费，经
     * `NavigationItem.copy(badge = …)` 填充渲染。默认空流 —— 不支持徽章的模块什么都不
     * 用写，宿主侧徽章区自然留空。
     *
     * 数量口径 = 对象列表页**默认视图可见的用户对象数**（IBM MQ 模块排除 `SYSTEM.*`
     * 系统对象、队列另排除 `AMQ.*` 临时队列），保证「徽章数字 = 点进去看到的行数」。
     *
     * 冷流即可（实现方通常从模块内部 StateFlow 派生），键是 [navigationItems] 里的
     * [NavigationItem.id]；扫描前 / 该类未扫到时键缺位（badge = null 不显示），
     * 数量为 0 也照常显示（与对象列表的空语义一致）。
     */
    fun badgeCounts(connectionId: String): Flow<Map<String, Int>> = emptyFlow()

    /**
     * 模块销毁钩子：由 [ModuleRegistry.disposeServices] 在应用退出时**按初始化逆序**调用，
     * 模块在这里释放自己持有的资源：真实连接 / 会话 / 消费者 / 订阅 / 定时任务。
     *
     * 调用点在 `CoreModule.shutdown()` 与 `DatabaseManager.close()` **之前**（见 app-ui 的
     * `Main.kt` closeApp）：这时总线与调度器还活着，dispose 里发事件、用模块 scope 都还成立。
     *
     * 契约：
     *  - 默认空实现 —— 纯描述性的模块不用写；
     *  - 必须**幂等**，且能容忍「[initialize] 没跑过或跑了一半」：失败过的模块正是需要
     *    收尾的那个，销毁路径照样会调它；
     *  - 抛出的异常只影响它自己（[ModuleRegistry.disposeServices] 逐个兜住），
     *    但不要依赖这一点 —— 没关掉的资源就是泄漏。
     */
    fun dispose() {}
}
