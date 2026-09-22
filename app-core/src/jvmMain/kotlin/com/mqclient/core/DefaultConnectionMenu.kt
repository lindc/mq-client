package com.mqclient.core

/**
 * 通用连接右键菜单的默认构建器：`ServiceAdapter.contextMenuItems` 的默认实现
 * 委托到这里，各模块不覆盖就是这份通用菜单，覆盖时可调 [build] 再追加 / 替换。
 *
 * 菜单结构（自上而下）：
 *
 *  1. 连接 / 断开 / 重新连接（**合并为一项**，按 [ConnectionInfo.status] 切换文案；
 *     Connecting 时禁用）；
 *  2. ─ 分隔线 ─
 *  3. 修改连接（编辑已有连接；**当前为占位**，见 [ConnectionActions.edit] 的 TODO）；
 *  4. 复制连接（直接落盘副本，不打开对话框）；
 *  5. 测试连接（Connecting 时禁用，其余状态都可发起）；
 *  6. ─ 分隔线 ─
 *  7. 查看队列管理器属性（**始终启用**，未连接时点击会先连接；宿主只对注册了
 *     [ConnectionPropertiesUi] 的模块显示它 —— 判别依据是 [ITEM_QMGR_PROPERTIES]）；
 *  8. ─ 分隔线 ─
 *  9. 删除连接（危险操作，宿主用 error 色显示并二次确认）。
 *
 * 连续分隔线、首尾分隔线由宿主渲染前统一过滤（见 app-ui 的
 * `filterNotConsecutiveSeparators`），这里不必小心翼翼地拼。
 */
object DefaultConnectionMenu {

    /**
     * 「查看队列管理器属性」条目的固定 id：宿主按它判断要不要渲染（模块注册了
     * [ConnectionPropertiesUi] 才显示）。自定义菜单想复用同一个开关，用这个 id。
     */
    const val ITEM_QMGR_PROPERTIES = "qmgr.properties"

    fun build(
        connection: ConnectionInfo,
        actions: ConnectionActions,
    ): List<ContextMenuItem> = listOf(
        buildConnectMenuItem(connection, actions),
        ContextMenuItem.separator("sep.after-connect"),
        ContextMenuItem(
            id = "edit",
            title = "menu_edit_connection",
            onClick = { actions.edit(connection.id) },
        ),
        ContextMenuItem(
            id = "copy",
            title = "menu_copy_connection",
            onClick = { actions.copy(connection.id) },
        ),
        ContextMenuItem(
            id = "test",
            title = "menu_test_connection",
            enabled = connection.status != ConnectionStatus.Connecting,
            onClick = { actions.test(connection.id) },
        ),
        ContextMenuItem.separator("sep.after-extra"),
        ContextMenuItem(
            id = ITEM_QMGR_PROPERTIES,
            title = "menu_qmgr_properties",
            onClick = { actions.showQmgrProperties(connection.id) },
        ),
        ContextMenuItem.separator("sep.before-delete"),
        ContextMenuItem(
            id = "delete",
            title = "menu_delete_connection",
            isDangerous = true,
            onClick = { actions.delete(connection.id) },
        ),
    )

    /**
     * 连接 / 断开 / 重新连接的合并项：同一个位置按 [ConnectionInfo.status] 切换
     * 文案与动作，右键菜单里永远只有一个「主动作」。
     */
    private fun buildConnectMenuItem(
        connection: ConnectionInfo,
        actions: ConnectionActions,
    ): ContextMenuItem = when (connection.status) {
        ConnectionStatus.Disconnected -> ContextMenuItem(
            id = "connect",
            title = "menu_connect",
            onClick = { actions.connect(connection.id) },
        )
        // 连接中：禁用占位，重复触发没有意义（模块自己也会忽略并发连接）。
        ConnectionStatus.Connecting -> ContextMenuItem(
            id = "connecting",
            title = "menu_connecting",
            enabled = false,
        )
        ConnectionStatus.Connected -> ContextMenuItem(
            id = "disconnect",
            title = "menu_disconnect",
            onClick = { actions.disconnect(connection.id) },
        )
        ConnectionStatus.Failed -> ContextMenuItem(
            id = "reconnect",
            title = "menu_reconnect",
            onClick = { actions.connect(connection.id) },
        )
    }
}
