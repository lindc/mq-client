package com.mqclient.app.connection

import com.mqclient.core.ServiceAdapter
import com.mqclient.uicommon.i18n.stringResource

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

/**
 * SPI 描述模块 → 卡片 UI 模型。
 *
 * [ServiceAdapter.description] 允许是 i18n 键（新模块如 StarRocks 用
 * `olap.starrocks.description`）；`stringResource` 缺键时**原样返回 key**，
 * 所以现有 MQ / AI 模块直接返回英文句子的适配器显示完全不变 —— 一次改动
 * 同时兼容「字面量」与「翻译键」两种形态，不需要改任何现有模块。
 */
fun ServiceAdapter.toMiddlewareType(): MiddlewareType =
    MiddlewareType(id = id, name = name, description = stringResource(description))
