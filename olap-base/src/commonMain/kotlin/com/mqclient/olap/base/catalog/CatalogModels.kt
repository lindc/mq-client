package com.mqclient.olap.base.catalog

/**
 * 目录扫描的范围界定。
 *
 * @param databases 只扫这些库；空集合 = 全部库（协议实现自行排除系统库）。
 * @param includeSystem 是否包含系统库 / 系统表（`information_schema`、`system.*` 等，
 *   判定标准由协议实现持有——OLAP 系没有 MQ 那样统一的 `SYSTEM.*` 命名约定）。
 * @param maxTablesPerDatabase 单库最多扫描的表数（防止千表目录拖死一轮扫描）；
 *   超出时截断并在 [CatalogSnapshot.truncated] 打标。
 */
data class CatalogScope(
    val databases: Set<String> = emptySet(),
    val includeSystem: Boolean = false,
    val maxTablesPerDatabase: Int = 500,
) {
    companion object {
        /** 全库全表（系统对象除外）的默认范围。 */
        val ALL = CatalogScope()
    }
}

/** 扫描阶段（进度上报的粒度：阶段名 + 当前对象 + 计数）。 */
enum class CatalogPhase {
    /** 枚举数据库。 */
    DATABASES,

    /** 逐库枚举表 / 视图。 */
    TABLES,

    /** 逐表枚举列 / 分区 / 副本。 */
    COLUMNS,
}

/**
 * 一次扫描的进度快照（`onProgress` 回调的载荷）。
 *
 * @param phase 当前阶段。
 * @param current 当前正在处理的对象名（库名 / 表名）。
 * @param completed 本阶段已完成的对象数。
 * @param total 本阶段预估总数（协议侧拿不到精确总数时为 null —— 进度条退化为计数）。
 */
data class ScanProgress(
    val phase: CatalogPhase,
    val current: String,
    val completed: Int,
    val total: Int?,
)

/**
 * 目录树的节点模型：库 / 表 / 列 / 分区 / 副本的 sealed 层级。
 *
 * 全部是不可变数据类；每个节点携带 [topologyNodeType] **预留字段** —— 未来
 * topology-ui 复用时按它映射节点类型（当前 topology 引擎的节点词汇由 MQ 侧持有，
 * OLAP 接入前先以字符串形态打标，避免现在就定义跨域枚举）。
 */
sealed interface CatalogNode {

    /** 节点名（库名 / 表名 / 列名 / 分区名 / 副本名）。 */
    val name: String

    /** 拓扑节点类型标记（预留字段，供 topology-ui 映射）。 */
    val topologyNodeType: String

    /** 数据库节点。 */
    data class Database(
        override val name: String,
    ) : CatalogNode {
        override val topologyNodeType: String = TYPE_DATABASE
    }

    /** 表 / 视图节点。 */
    data class Table(
        /** 所属库名。 */
        val database: String,
        override val name: String,
        /** 表类别标签（协议侧自行解释：`table` / `view` / `materialized_view`…）。 */
        val kind: String = "table",
    ) : CatalogNode {
        override val topologyNodeType: String = TYPE_TABLE
    }

    /** 列节点。 */
    data class Column(
        val database: String,
        val table: String,
        override val name: String,
        /** 数据类型标签（协议侧原文，如 `String` / `UInt64` / `DECIMAL(18,2)`）。 */
        val dataType: String,
        val nullable: Boolean = true,
    ) : CatalogNode {
        override val topologyNodeType: String = TYPE_COLUMN
    }

    /** 分区节点（OLAP 特有 —— MQ 系无对应物）。 */
    data class Partition(
        val database: String,
        val table: String,
        override val name: String,
    ) : CatalogNode {
        override val topologyNodeType: String = TYPE_PARTITION
    }

    /** 副本节点（OLAP 特有 —— MQ 系无对应物）。 */
    data class Replica(
        val database: String,
        val table: String,
        override val name: String,
        /** 副本所在端点（host:port），拿不到时为 null。 */
        val endpoint: String? = null,
    ) : CatalogNode {
        override val topologyNodeType: String = TYPE_REPLICA
    }

    companion object {
        /** 拓扑节点类型标记值（与 [topologyNodeType] 字段对应）。 */
        const val TYPE_DATABASE = "database"
        const val TYPE_TABLE = "table"
        const val TYPE_COLUMN = "column"
        const val TYPE_PARTITION = "partition"
        const val TYPE_REPLICA = "replica"
    }
}

/**
 * 一轮目录扫描的结果。
 *
 * @param nodes 收集到的全部节点（按扫描顺序：库 → 表 → 列/分区/副本）。
 * @param failures 单对象失败记录（`"<database>.<table>" -> 错误文案`）—— 单对象
 *   失败不中断整体（与 mq-base `scanKindOr` 同语义），文案已经过统一翻译。
 * @param durationMs 整轮耗时（毫秒）。
 * @param truncated 是否发生了截断（[CatalogScope.maxTablesPerDatabase] 触顶）。
 */
data class CatalogSnapshot(
    val nodes: List<CatalogNode>,
    val failures: Map<String, String>,
    val durationMs: Long,
    val truncated: Boolean = false,
)
