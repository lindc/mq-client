package com.mqclient.mq.base

import com.mqclient.core.ServiceAdapter

/**
 * 所有 MQ 类型模块的抽象基类。
 *
 * 统一声明 `category = "MQ"`（新建连接对话框的 tab 归组，见
 * [com.mqclient.core.ServiceAdapter.category]）；各 MQ 模块继承本类后
 * 不再各自硬编码 category，只实现 id / name / description / protocol。
 *
 * 注意：本类放 **jvmMain** 而非 commonMain —— 父接口 [ServiceAdapter] 定义在
 * app-core 的 jvmMain 源集，commonMain 看不到它。
 */
abstract class MQServiceAdapter : ServiceAdapter {

    /** MQ 类目常量（与 [MQConstants.CATEGORY] 同值；取 [MQConstants] 以单一来源）。 */
    override val category: String = MQConstants.CATEGORY

    // 子类继续实现 ServiceAdapter 的其余抽象成员
    // （id、name、description、protocol；initialize / dispose / 菜单项按需覆盖）。
}
