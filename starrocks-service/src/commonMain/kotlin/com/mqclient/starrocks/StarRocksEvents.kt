package com.mqclient.starrocks

/**
 * StarRocks 与宿主之间的**连接动作事件**名（约定见 app-core 的 `ConnectionActions`）：
 *
 *  - 宿主右键菜单发 `<serviceId>.connection.connect / disconnect / test`，payload
 *    `connectionId`；模块订阅后执行自己的连接逻辑。
 *  - `test` 的结果由模块发 `starrocks.connection.test.completed`，payload
 *    `connectionId` / `success`(Boolean) / `message`(String) / `durationMs`(Long)，
 *    宿主的后缀订阅（`.connection.test.completed`）据此弹结果对话框。
 *
 * 名字由 [StarRocksConstants.PROTOCOL_ID] 派生，不硬编码 `starrocks` 字面量 ——
 * 模块 id 改了事件名会一起改，不会出现「菜单发了、没人收」的静默断链。
 *
 * 这些事件是**请求 / 结果**，不是连接状态：状态仍只经
 * `ConnectionRegistry.reportStatus` 回传（项目口径见 `EventNames` 的文档）。
 */
object StarRocksEvents {

    /** 请求建立连接。payload: `connectionId` */
    const val CONNECT_REQUESTED: String = StarRocksConstants.PROTOCOL_ID + ".connection.connect"

    /** 请求断开连接。payload: `connectionId` */
    const val DISCONNECT_REQUESTED: String = StarRocksConstants.PROTOCOL_ID + ".connection.disconnect"

    /** 请求测试连接（临时连接，用完即关，不改状态）。payload: `connectionId` */
    const val TEST_REQUESTED: String = StarRocksConstants.PROTOCOL_ID + ".connection.test"

    /** 测试连接的结果（成功失败都发，宿主据此弹结果对话框）。 */
    const val TEST_COMPLETED: String = StarRocksConstants.PROTOCOL_ID + ".connection.test.completed"
}
