package com.mqclient.core

import androidx.compose.runtime.Composable

/**
 * 「新建连接」第二步的创建表单，由各 `*-ui` 模块提供（SPI 注册，与
 * [AdapterUI] / [ModuleIcon] 并列）。
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
     * 清空草稿回到初始值（字段重置为对应 Provider 的默认值、错误行清掉）。
     * 宿主在**每次进入创建页**时调用 —— 「新建连接」的语义就是从空白开始，
     * 上一次的输入不该残留。
     *
     * @param defaultName 连接名为空时的默认值（宿主按「连接 N」实时计算）。
     */
    fun reset(defaultName: String)

    /**
     * 校验 + 落盘。成功返回 `true`（宿主随后关窗）；失败返回 `false`
     * （错误已由面板展示，对话框保持打开）。
     *
     * 需要等待慢操作（如「保存前测试连接」）的表单无法同步给出最终结果：这类
     * 表单返回 `false`（对话框保持打开）并在后台继续流程，落盘成功后回调
     * [onSaved]，宿主据此重载 + 关窗。所以「返回 false」不再必然意味着失败 ——
     * 宿主对它只有一种正确反应：什么都不做。
     */
    fun submit(): Boolean

    /**
     * 编辑模式：从已有连接载入草稿，预填表单。新建流程不调它（见 [reset]）。
     *
     * 默认空实现 —— 只用于新建、暂不支持「修改连接」的模块继承即可，不影响新建；
     * 支持编辑的模块应 override 本方法并按各自配置回填字段。敏感字段（密码等）按
     * 模块安全策略处理：通常留空 + 占位符，用户不动就沿用原值（参考 IBM MQ 的
     * [com.mqclient.ibmmq.ui.IbmMqConnectionFormState.loadFrom]）。
     */
    fun loadFrom(connection: ConnectionInfo) {}

    /**
     * 表单是否正忙于后台流程（测试连接 / 保存中）。宿主据此禁用「完成」按钮，
     * 防止流程进行中被重复触发；实现方把它实现成 Compose 状态，宿主组合期读取
     * 会自动随变化重组。同步表单永远是 false（默认值），无需关心。
     */
    val busy: Boolean get() = false

    /**
     * 异步保存成功后的回调（见 [submit] 的说明）：面板在后台完成落盘后调用，
     * 宿主应执行「重载内存态 + 关窗」。默认实现是空操作 —— 同步表单不用它。
     * 宿主在每次触发 submit 前重新赋值即可（表单状态对象随重组重建，靠当次
     * 赋值保证回调捕获的是当前宿主环境）。
     */
    var onSaved: (() -> Unit)?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /**
     * 面板内容（字段 + 错误行）。尺寸与滚动由面板自己负责（`heightIn` 上限 +
     * `verticalScroll`，参考 openai-ui 的实现）—— app-core 只有 compose runtime +
     * ui-graphics，签名刻意不引 `Modifier`（那要多拖一个 compose.ui 构件进来）。
     */
    @Composable
    fun Content()
}
