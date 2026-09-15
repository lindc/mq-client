package com.mqclient.core

import androidx.compose.runtime.Composable

/**
 * 「新建连接」第二步的创建表单，由各 `*-ui` 模块提供（SPI 注册，与
 * [BusinessUi] / [ModuleIcon] 并列）。
 *
 * 分工：**面板内的一切归模块** —— 字段、布局、校验、错误展示、落盘（模块自己调
 * 对应的 repository）；宿主（app-ui 的对话框）只提供容器、导航和「完成」按钮。
 *
 * 通信是**双向的纯函数式回调/返回值**，不走事件总线：对话框是一次性 UI，事件需要
 * 寻址与防重放，杀鸡用牛刀。
 *
 *  - 宿主 footer 的「完成」调 [ConnectionFormState.submit]；
 *  - 返回 `true` = 已落盘，宿主关窗（并从磁盘重载内存态）；
 *  - 返回 `false` = 失败（校验不过 / 落盘失败），错误已由面板在 [ConnectionFormState.Content]
 *    里展示，**宿主什么都不做 —— 对话框保持打开**。错误从不出面板边界，宿主只认
 *    布尔值。
 *
 * 还没有实现本 SPI 的 service 类型（如表单未做的 MQ），宿主显示占位提示、不渲染
 * 「完成」。
 */
interface ConnectionFormUi {

    /** Must match the related [ServiceAdapter.id]. */
    val serviceId: String

    /**
     * 创建并记住本面板的表单状态。由宿主在组合里调用（key 到选中的类型上，
     * 返回类型选择时重置）。
     *
     * @param defaultName 连接名为空时的默认值（宿主按「连接 N」计算）。
     */
    @Composable
    fun rememberFormState(defaultName: String): ConnectionFormState
}

/**
 * 一个已激活的表单实例：持有字段草稿与错误状态，见 [ConnectionFormUi] 的分工说明。
 */
interface ConnectionFormState {

    /**
     * 校验 + 落盘。成功返回 `true`（宿主随后关窗）；失败返回 `false`
     * （错误已由面板展示，对话框保持打开）。
     */
    fun submit(): Boolean

    /**
     * 面板内容（字段 + 错误行）。尺寸与滚动由面板自己负责（`heightIn` 上限 +
     * `verticalScroll`，参考 openai-ui 的实现）—— app-core 只有 compose runtime +
     * ui-graphics，签名刻意不引 `Modifier`（那要多拖一个 compose.ui 构件进来）。
     */
    @Composable
    fun Content()
}
