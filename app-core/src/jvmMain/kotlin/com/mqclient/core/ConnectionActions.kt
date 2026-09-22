package com.mqclient.core

/**
 * 右键菜单里「通用动作」的回调集，由宿主（app-ui）实现并传给
 * `ServiceAdapter.contextMenuItems` —— 模块只管描述菜单，动作的落点（对话框 /
 * 事件 / 状态更新）归宿主。
 *
 * 跨模块的动作走**事件约定**（事件总线零注册，见 app-core `EventNames` 的命名
 * 约定 `<模块名>.<对象>.<动作>`）：
 *
 *  - `connect` / `disconnect` / `test`：宿主发 `<serviceId>.connection.connect`
 *    （disconnect / test 同理），payload `connectionId`。模块订阅这三个名字执行
 *    自己的连接逻辑（参考 ibm-mq 模块的 `IbmMQEvents.CONNECT_REQUESTED` 等常量）；
 *    不订阅的模块等于不支持这些动作，点击后安静无副作用。
 *  - `test` 的结果由模块发 `<serviceId>.connection.test.completed`，payload
 *    `connectionId` / `success`(Boolean) / `message`(String) / `durationMs`(Long)，
 *    宿主监听它弹结果对话框。
 *
 * `copy`（直接落盘一条副本）与 `delete`（弹二次确认）是纯核心动作，不依赖模块。
 */
interface ConnectionActions {

    /** 建立连接（幂等：已连接是空操作，连接中重复触发由模块忽略）。 */
    suspend fun connect(connectionId: String)

    /** 断开连接；未连接时是空操作。 */
    suspend fun disconnect(connectionId: String)

    /**
     * 复制连接：直接落盘一条副本（新 id，名称加「（副本）」后缀，状态未连接），
     * 不打开对话框。落盘失败经错误通道上报，菜单侧无需关心结果。
     */
    fun copy(connectionId: String)

    /** 删除连接：只负责弹出二次确认对话框，真正的删除由确认回调走 `ConnectionStore.delete`。 */
    fun delete(connectionId: String)

    /** 测试连接：发起一次真实连接验证可达性；结果经 `.connection.test.completed` 事件由宿主展示。 */
    suspend fun test(connectionId: String)

    /**
     * 查看队列管理器属性：
     *  - 已连接：直接打开属性对话框；
     *  - 未连接 / 失败：先建立连接（显示「正在连接」），成功后打开，失败弹错误对话框；
     *  - 连接中：忽略重复触发。
     *
     * 属性内容是队列管理器本身的属性（PCF 查询），由模块的
     * [ConnectionPropertiesUi] 贡献 —— 没有注册该 SPI 的模块，宿主不显示这个菜单项。
     */
    suspend fun showQmgrProperties(connectionId: String)
}
