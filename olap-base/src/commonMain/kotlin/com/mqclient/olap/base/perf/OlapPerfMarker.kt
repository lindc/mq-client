package com.mqclient.olap.base.perf

import com.mqclient.core.log.Log
import com.mqclient.olap.base.OlapCategory.PERF_TAG_PREFIX

/**
 * `[OLAP-Perf]` 性能打点的分段常量与工具（对齐 MQ 系 `[MQ-Perf]` 的口径）。
 *
 * 分段语义：
 *  - `connect`：建会话（[com.mqclient.olap.base.connection.AbstractAnalyticsConnectionManager.connect]）；
 *  - `catalog`：目录扫描（[com.mqclient.olap.base.catalog.AbstractCatalogScanService.scanCatalog]）；
 *  - `plan`：EXPLAIN（`AbstractQueryExecutor.explain`）；
 *  - `execute`：查询提交（`AbstractQueryExecutor.execute`）；
 *  - `fetch`：结果拉流（`AbstractQueryExecutor.stream`）；
 *  - `total`：跨段总耗时（上层服务按需打）。
 *
 * 输出格式与 `[MQ-Perf]` 一致：`[OLAP-Perf][<section>] ... 耗时=<X>ms`。
 */
object OlapPerfMarker {

    /** 打点前缀（取 [com.mqclient.olap.base.OlapCategory.PERF_TAG_PREFIX]，单一来源）。 */
    const val TAG: String = PERF_TAG_PREFIX

    /** 全部分段名（打点段的合法取值；新增分段必须先加到这里）。 */
    val SECTIONS: Set<String> = setOf("connect", "catalog", "plan", "execute", "fetch", "total")

    /** 一条打点的段标签：`[OLAP-Perf][<section>]`。 */
    fun sectionLabel(section: String): String = "$TAG[$section]"
}

/**
 * 打一个 [section] 分段的耗时（`try/finally`：异常路径也打点，异常本身照常上抛）。
 *
 * ```
 * val plan = olapPerf(logTag, "plan") { dialect.explainPrefix(query.sql) }
 * ```
 *
 * @param logTag 日志 tag（模块侧自己的 `[OLAP]<moduleId>` tag）。
 * @param section 分段名（应取自 [OlapPerfMarker.SECTIONS]；未注册的段也能打，
 *   但会是对齐审查的信号）。
 */
inline fun <T> olapPerf(logTag: String, section: String, block: () -> T): T {
    val start = olapTimeMillis()
    try {
        return block()
    } finally {
        Log.info(logTag, "${OlapPerfMarker.sectionLabel(section)} 耗时=${olapTimeMillis() - start}ms")
    }
}
