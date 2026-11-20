package com.mqclient.starrocks.catalog

/** 一张表 / 物化视图 / 视图（名称 + 注释 + 类型 + 是否物化视图）。 */
data class CatalogTable(
    val name: String,
    val comment: String,
    val type: String,
    val materializedView: Boolean = false,
)

/** 一个字段：information_schema.columns 的完整属性。 */
data class CatalogColumn(
    val name: String,
    val type: String,
    val nullable: String,
    val key: String,
    val default: String,
    val extra: String,
    val comment: String,
)

/** 一个分区：SHOW PARTITIONS 关键列的人话摘要数据。 */
data class CatalogPartition(
    val name: String,
    val key: String,
    val range: String,
    val state: String,
    val rowCount: String,
    val dataSize: String,
    val distributionKey: String,
    val summary: String,
)
