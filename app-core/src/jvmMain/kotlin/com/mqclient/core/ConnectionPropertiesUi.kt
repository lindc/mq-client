package com.mqclient.core

import androidx.compose.runtime.Composable

/**
 * 「队列管理器属性」等连接级属性对话框的内容贡献接口，由各 `*-ui` 模块提供
 * （SPI 注册，与 [AdapterUI] / [ConnectionFormUi] / [ModuleIcon] 并列）。
 *
 * 之所以不放进 [ServiceAdapter]：service 模块（如 ibm-mq-service）没有 Compose
 * UI 依赖，属性面板是纯 UI 代码，归 `*-ui` 模块 —— 两个 Gradle 模块互不依赖，
 * 靠 [serviceId] == `ServiceAdapter.id` 的字符串约定对上号（同 [AdapterUI]）。
 *
 * 分工与 [ConnectionFormUi] 一致：**面板内的一切归模块** —— 数据查询（PCF 等）、
 * 加载 / 错误 / 重试状态、布局与滚动。宿主只提供对话框容器与标题。
 *
 * 宿主只对注册了本 SPI 的模块显示「查看队列管理器属性」菜单项（见
 * [DefaultConnectionMenu.ITEM_QMGR_PROPERTIES]），所以实现方可以假设打开时
 * 一定有合法的 [connection]。
 */
interface ConnectionPropertiesUi {

    /** Must match the related [ServiceAdapter.id]. */
    val serviceId: String

    /**
     * 属性面板内容。尺寸与滚动由面板自己负责（`fillMaxSize` + `verticalScroll`，
     * 参考 [ConnectionFormUi] 的约定）—— app-core 只有 compose runtime +
     * ui-graphics，签名刻意不引 `Modifier`（那要多拖一个 compose.ui 构件进来）。
     */
    @Composable
    fun Content(connection: ConnectionInfo)
}
