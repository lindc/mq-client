package com.mqclient.app.connection

import com.mqclient.core.ServiceAdapter

/**
 * 「添加连接」第一步类型选择卡片的 UI 模型。
 *
 * 数据**不硬编码**：由各业务模块的 SPI（[ServiceAdapter]）在运行时扫描得出
 * （见 [toMiddlewareType]）—— 新模块注册即出现在卡片网格里。图标也不进数据：
 * 归 [com.mqclient.app.middlewareIcon] 按 id 取真实的 drawable 资源（与侧边栏同一套）。
 */
data class MiddlewareType(
    /** 稳定标识，同 [ServiceAdapter.id]，如 "ibm-mq"。 */
    val id: String,
    /** 展示名，如 "IBM MQ"。 */
    val name: String,
    /** 一句话说明。 */
    val description: String,
)

/** SPI 描述模块 → 卡片 UI 模型。 */
fun ServiceAdapter.toMiddlewareType(): MiddlewareType =
    MiddlewareType(id = id, name = name, description = description)
