package com.mqclient.starrocks

import com.mqclient.olap.base.OlapCategory

/**
 * StarRocks 协议族常量（**空骨架**阶段）。
 *
 * 分类、日志、性能打点前缀全部引用 olap-base 的 [OlapCategory] —— 本模块
 * **不硬编码**任何分类前缀字面量，保证 OLAP 协议族的口径只有一个来源
 * （日志、事件、i18n 键、打点全部由 [OlapCategory] 派生）。
 *
 * Skeleton only, protocol logic pending：本文件只有常量，不含连接 / 扫描 /
 * 查询逻辑，也不引任何 StarRocks / MySQL JDBC / HTTP 客户端依赖。
 */
object StarRocksConstants {

    /** 协议展示名（专有名词，与 KafkaServiceAdapter.name 同口径）。 */
    const val PROTOCOL_NAME: String = "StarRocks"

    /** 模块 id：与 [com.mqclient.core.ServiceAdapter.id] / AdapterUI.serviceId 同源。 */
    const val PROTOCOL_ID: String = "starrocks"

    /** 新建连接对话框的类目（取 olap-base 的单一来源，不硬编码字面量）。 */
    const val CATEGORY: String = OlapCategory.CATEGORY

    /** 日志 tag 前缀（取 olap-base 的单一来源）。 */
    const val LOG_TAG_PREFIX: String = OlapCategory.LOG_TAG_PREFIX

    /** 性能打点前缀（取 olap-base 的单一来源）。 */
    const val PERF_TAG_PREFIX: String = OlapCategory.PERF_TAG_PREFIX

    /** 本模块 i18n 键前缀：`<olap 前缀>starrocks.`（如 `olap.starrocks.placeholder_title`）。 */
    const val I18N_KEY_PREFIX: String = OlapCategory.I18N_KEY_PREFIX + "starrocks."
}
