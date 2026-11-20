package com.mqclient.starrocks.catalog

import com.mqclient.starrocks.query.QueryRows

/**
 * 数据目录结果集的纯解析层（无 JDBC、无协程，commonTest 直测）。
 *
 * 与 [com.mqclient.starrocks.cluster] / [com.mqclient.starrocks.system] 两个包的解析器
 * 同一取向：**单行坏数据只丢弃该行，绝不把面板打成错误态**。`SHOW PARTITIONS` 的
 * 列名随引擎与版本漂移，因此优先按列名绑定，对不上时回落为全列拼接。
 */

private const val TABLES_TABLE_TYPE_MV = "MATERIALIZED VIEW"

/** 解析 SHOW MATERIALIZED VIEWS 的 name 列 → 物化视图名集合（列名按 lowercase 匹配，坏行跳过）。 */
fun parseMaterializedViewNames(result: QueryRows): Set<String> {
    val nameIdx = result.columns.map { it.lowercase() }.indexOf("name")
    if (nameIdx < 0) return emptySet()   // MySQL / 意外列形状 → 无物化视图
    return result.rows.mapNotNull { row ->
        runCatching { row.getOrNull(nameIdx)?.toString()?.trim()?.takeIf { it.isNotBlank() } }.getOrNull()
    }.toSet()
}

/** 表清单解析：名称 / 注释 / 类型；物化视图由 [SHOW MATERIALIZED VIEWS 名单][mvNames] 或 table_type 识别。 */
fun parseTables(
    result: QueryRows,
    mvNames: Set<String> = emptySet(),
): List<CatalogTable> = result.rows.mapNotNull { row ->
    // 单行坏数据（越界 / 异常 toString）不得崩掉整棵树 —— runCatching 静默丢弃该行。
    runCatching {
        val name = row.getOrNull(0)?.toString().orEmpty().trim()
        name.takeIf { it.isNotBlank() }?.let {
            val type = row.getOrNull(2)?.toString().orEmpty()
            CatalogTable(
                name = name,
                comment = row.getOrNull(1)?.toString().orEmpty(),
                type = type,
                materializedView = name in mvNames || type == TABLES_TABLE_TYPE_MV,
            )
        }
    }.getOrNull()
}

/** 字段解析：information_schema.columns 的 7 列（缺失列空串兜底）。 */
fun parseColumns(result: QueryRows): List<CatalogColumn> = result.rows.mapNotNull { row ->
    runCatching {
        val name = row.getOrNull(0)?.toString().orEmpty().trim()
        name.takeIf { it.isNotBlank() }?.let {
        CatalogColumn(
            name = name,
            type = row.getOrNull(1)?.toString().orEmpty(),
            nullable = row.getOrNull(2)?.toString().orEmpty(),
            key = row.getOrNull(3)?.toString().orEmpty(),
            default = row.getOrNull(4)?.toString().orEmpty(),
            extra = row.getOrNull(5)?.toString().orEmpty(),
            comment = row.getOrNull(6)?.toString().orEmpty(),
        )
    }
    }.getOrNull()
}

/**
 * `SHOW PARTITIONS` 结果列名随引擎 / 版本变化，这里**优先按列名绑定**有意义的列
 * （PartitionName / PartitionKey / Range / State / RowCount / DataSize /
 * DistributionKey），拼出人话摘要（如 `sales · NORMAL · 10008 行 · 33KB · 分布: region`、
 * `p202401 · [2024-01-01, 2024-02-01) · NORMAL`）；列名对不上时回落为全列拼接。
 */
fun parsePartitions(result: QueryRows): List<CatalogPartition> {
    val cols = result.columns.map { it.lowercase() }
    fun idx(name: String): Int = cols.indexOf(name.lowercase())
    val nameIdx = idx("partitionname")
    val keyIdx = idx("partitionkey")
    val rangeIdx = idx("range")
    val stateIdx = idx("state")
    val rowCountIdx = idx("rowcount")
    val dataSizeIdx = idx("datasize")
    val distIdx = idx("distributionkey")

    val namedBindingAvailable = nameIdx >= 0
    return result.rows.mapNotNull { row ->
        runCatching {
        fun cell(i: Int?): String = i?.takeIf { it >= 0 }?.let { row.getOrNull(it)?.toString().orEmpty() }?.trim().orEmpty()

        val name = cell(nameIdx)
        if (namedBindingAvailable && name.isBlank()) return@mapNotNull null   // 全空行丢弃

        val key = cell(keyIdx)
        val range = cell(rangeIdx)
        val state = cell(stateIdx)
        val rowCount = cell(rowCountIdx)
        val dataSize = cell(dataSizeIdx)
        val distributionKey = cell(distIdx)

        val summary = if (namedBindingAvailable) {
            buildList {
                add(name)
                if (range.isNotBlank()) add(range)
                if (state.isNotBlank()) add(state)
                if (rowCount.isNotBlank()) add("$rowCount 行")
                if (dataSize.isNotBlank()) add(dataSize)
                if (distributionKey.isNotBlank()) add("分布: $distributionKey")
            }.joinToString(" · ")
        } else {
            row.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.joinToString(" · ")
        }
        CatalogPartition(name, key, range, state, rowCount, dataSize, distributionKey, summary)
        }.getOrNull()
    }
}
