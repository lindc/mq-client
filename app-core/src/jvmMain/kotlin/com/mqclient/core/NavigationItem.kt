package com.mqclient.core

/**
 * 导航树里一条连接展开后的一个二级菜单项：**纯数据描述，不含任何 UI**。
 *
 * 各业务模块经 `ServiceAdapter.navigationItems` 提供菜单项，宿主（app-ui 的
 * `ModuleTree` / `ModuleRail`）负责统一渲染并处理点击 —— 与 [ContextMenuItem]
 * 同一套分工：模块只管描述「有哪些」，宿主管「长什么样、点出去是什么」。
 *
 * 点击行为是宿主的通用逻辑：`NavSelection(connectionId, itemId)` 进现有的
 * Tab 流（已打开即切换，未打开即新建占位 Tab）；未连接 / 失败时宿主顺带发
 * `<serviceId>.connection.connect` 隐式连接（见 app-ui `ModuleTree`）。所以
 * 本类**没有 onClick** —— 打开 Tab 是宿主就能完成的事，不需要模块参与；等
 * 各 Tab 有了真实内容，再由模块经 SPI 贡献内容渲染。
 *
 * [titleKey] 是**国际化 key**（如 `nav_queues`），由宿主经 `stringResource` 解析；
 * 模块不持有译文，菜单文案跟着应用语言走。
 */
data class NavigationItem(
    /**
     * 唯一标识（模块内唯一，如 `queues` / `topology`）；宿主把它当作 Tab 的
     * object type（`NavSelection.objectType`），同一连接下同 id 只有一个 Tab。
     */
    val id: String,
    /** 国际化 key；分隔线（[isSeparator] = true）时为空串。 */
    val titleKey: String,
    /** 图标（emoji 字符，如 `"📥"`）；null / 空串不显示。 */
    val icon: String? = null,
    /** 工具类条目（拓扑图 / 命令控制台等），与对象类条目用分隔线分组；渲染上暂不区分。 */
    val isTool: Boolean = false,
    /** 数量徽章（对象扫描完成后的条数）；null = 未扫描，不显示。 */
    val badge: Int? = null,
    /** 分隔线：宿主渲染成 `HorizontalDivider`，其余字段无意义。 */
    val isSeparator: Boolean = false,
) {
    companion object {
        /** 分隔线的便捷构造：id 只需要在同一份菜单里唯一（用于区分相邻的分隔线）。 */
        fun separator(id: String): NavigationItem =
            NavigationItem(id = id, titleKey = "", isSeparator = true)
    }
}
