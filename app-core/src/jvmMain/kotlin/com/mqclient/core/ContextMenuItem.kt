package com.mqclient.core

/**
 * 右键菜单的一个条目：**纯数据描述，不含任何 UI**。
 *
 * 各业务模块经 `ServiceAdapter.contextMenuItems` 提供菜单项，宿主（app-ui 的
 * `ConnectionContextMenu`）负责统一渲染成 Material 3 的 `DropdownMenu`。
 *
 * [title] 是**国际化 key**（如 `menu_connect`），由宿主经 `stringResource` 解析；
 * 模块不持有译文，菜单文案跟着应用语言走。
 *
 * 子菜单刻意没有：Material 3 的 `DropdownMenu` 没有原生嵌套支持，当前也没有
 * 菜单需要它 —— 等真有需求时再引入（嵌套对话框或展开式二级面板）。
 */
data class ContextMenuItem(
    /** 唯一标识，如 `connect` / `copy` / `qmgr.properties`；宿主按它做特殊处理（见 [DefaultConnectionMenu.ITEM_QMGR_PROPERTIES]）。 */
    val id: String,
    /** 国际化 key；分隔线（[isSeparator] = true）时为空串。 */
    val title: String,
    /** 是否启用；连接中（Connecting）等状态下模块可以把动作暂时关掉。 */
    val enabled: Boolean = true,
    /** 危险操作（如删除）：宿主用 `colorScheme.error` 显示。 */
    val isDangerous: Boolean = false,
    /** 分隔线：宿主渲染成 `HorizontalDivider`，其余字段无意义。 */
    val isSeparator: Boolean = false,
    /**
     * 点击回调，挂起执行：连接 / 查询这类慢操作直接写在这里，宿主在协程里跑。
     * 失败由宿主兜住并上报（`CoreModule.reportError`），模块不必自己 try/catch。
     */
    val onClick: suspend () -> Unit = {},
) {
    companion object {
        /** 分隔线的便捷构造：id 只需要在同一份菜单里唯一（用于区分相邻的分隔线）。 */
        fun separator(id: String): ContextMenuItem =
            ContextMenuItem(id = id, title = "", isSeparator = true)
    }
}
