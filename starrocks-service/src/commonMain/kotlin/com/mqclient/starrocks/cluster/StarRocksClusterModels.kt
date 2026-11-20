package com.mqclient.starrocks.cluster

/**
 * 集群节点角色。
 *
 * 既是**拓扑分层的唯一依据**（Catalog → FE → BE/CN），也是属性对话框标题 /
 * 图标的来源 —— UI 侧按它做穷尽 `when`，新增角色不配图标就是编译错误。
 */
enum class ClusterNodeRole {
    /** FE 主节点（`IsMaster=true` 或 `Role=LEADER`）。 */
    FE_LEADER,

    /** FE 跟随节点（元数据可写，参与选主）。 */
    FE_FOLLOWER,

    /** FE 观察节点（只读副本，不参与选主）。 */
    FE_OBSERVER,

    /** 存储计算节点（有本地盘，表数据落在它上面）。 */
    BE,

    /** 无状态计算节点（3.0+，只算不存，无本地盘）。 */
    CN,

    /** 外部数据目录（Hive / Iceberg / Paimon…），具体类型放在 [ClusterNodeInfo.extra]。 */
    CATALOG,
    ;

    val isFrontend: Boolean
        get() = this == FE_LEADER || this == FE_FOLLOWER || this == FE_OBSERVER

    val isCompute: Boolean
        get() = this == BE || this == CN
}

/**
 * 一个监听端口。
 *
 * [label] 用协议原名（`heartbeat_port` / `be_port` / `query_port`…）而**不做 i18n**：
 * 这是运维查问题时要跟文档 / 日志对上的标识，与 MQ 属性面板直接展示 `MQCA_*`
 * 原名同一取向。
 */
data class ClusterPort(val label: String, val port: String)

/**
 * 一个集群节点的完整视图：拓扑卡片、属性对话框、概览聚合都消费它。
 *
 * 所有可选字段一律可空 —— FE / BE / CN / Catalog 能查到的列各不相同，不同版本
 * 也有列名漂移，缺列就是 null，渲染层显示 `-`，**不允许因为一个字段缺失崩面板**。
 */
data class ClusterNodeInfo(
    /** 稳定 id：`fe:<name>` / `be:<ip>:<heartbeat_port>` / `cn:<…>` / `catalog:<name>`。 */
    val nodeId: String,
    val role: ClusterNodeRole,
    /** 显示名：FE 用 Name、BE/CN 用 `ip:heartbeat_port`、Catalog 用目录名。 */
    val name: String,
    val ip: String,
    /** 有序端口表（属性对话框逐行展示；顺序即文档顺序）。 */
    val ports: List<ClusterPort>,
    val alive: Boolean,
    val version: String,
    /* ---- 计算资源（BE / CN）---- */
    val cpuCores: Int? = null,
    val cpuUsedPercent: Double? = null,
    val memUsedPercent: Double? = null,
    /** 内存上限：只有 `SHOW PROC '/backends'` 才给，拿不到就是 null。 */
    val memLimitBytes: Long? = null,
    val memUsedBytes: Long? = null,
    /* ---- 存储（BE；CN 通常为 0 / 缺列）---- */
    val diskTotalBytes: Long? = null,
    val diskUsedBytes: Long? = null,
    val diskAvailBytes: Long? = null,
    val diskUsedPercent: Double? = null,
    val tabletCount: Long? = null,
    val runningQueries: Int? = null,
    /* ---- 状态 / 时间 ---- */
    val status: String? = null,
    val lastStartTime: String? = null,
    val lastHeartbeat: String? = null,
    val joinTime: String? = null,
    val errorMessage: String? = null,
    /** 角色附加信息：Catalog 的 `type=hive`、FE 的 `role=FOLLOWER` 原文等。 */
    val extra: Map<String, String> = emptyMap(),
)

/** 一个 Routine Load 任务（只保留展示 / 计数需要的字段）。 */
data class RoutineLoadInfo(
    val id: String,
    val name: String,
    val dbName: String,
    val tableName: String,
    val state: String,
    val lag: String? = null,
) {
    val isRunning: Boolean
        get() = state.equals("RUNNING", ignoreCase = true)
}

/**
 * 哪一块子查询没拿到。UI 据此出一条**灰色提示**（"部分数据不可用：…"），
 * 而不是红框报错 —— 普通用户看不到 BE / PROC 是常态，不该把整页打成错误态。
 */
enum class ClusterDataPart {
    FRONTENDS,
    BACKENDS,
    COMPUTE_NODES,
    CATALOGS,
    ROUTINE_LOADS,

    /** `SHOW PROC '/backends'`（内存上限 / 已用）—— 权限不够时整块缺失。 */
    NODE_MEMORY,
}

data class ClusterDegradation(val part: ClusterDataPart, val message: String)

/**
 * 一次「集群快照」：节点 + 导入任务 + 降级记录。
 *
 * 聚合指标做成计算属性（纯函数、可单测），UI 只负责排版与 i18n —— 概览面板的
 * 内存 / 存储卡片全部由这里算，避免"每个面板各算一遍求和"。
 */
data class ClusterSnapshot(
    val nodes: List<ClusterNodeInfo>,
    val routineLoads: List<RoutineLoadInfo> = emptyList(),
    val degradations: List<ClusterDegradation> = emptyList(),
) {
    val aliveCount: Int get() = nodes.count { it.alive }

    val frontends: List<ClusterNodeInfo> get() = nodes.filter { it.role.isFrontend }
    val computeNodes: List<ClusterNodeInfo> get() = nodes.filter { it.role.isCompute }
    val catalogs: List<ClusterNodeInfo> get() = nodes.filter { it.role == ClusterNodeRole.CATALOG }

    /** FE 主节点（没有 FRONTENDS 数据时为 null，拓扑层自行合成占位节点）。 */
    val leaderFrontend: ClusterNodeInfo? get() = nodes.firstOrNull { it.role == ClusterNodeRole.FE_LEADER }

    /** 集群版本取第一个非占位的版本号（FE / BE 通常一致）。 */
    val version: String
        get() = nodes.map { it.version }
            .firstOrNull { it.isNotBlank() && it != "-" }
            ?: "-"

    val hasComputeData: Boolean get() = computeNodes.isNotEmpty()

    /**
     * 存储总量 / 已用：**只累加存活的 BE** —— 死节点的容量不该算进集群可用容量。
     * （CN 没有本地盘，容量列即使有也是 0，不影响求和。）
     */
    val diskTotalBytes: Long? get() = sumOrNull { node -> node.takeIf { it.alive }?.diskTotalBytes }
    val diskUsedBytes: Long? get() = sumOrNull { node -> node.takeIf { it.alive }?.diskUsedBytes }

    /** 内存总量 / 已用：只有 `SHOW PROC '/backends'` 成功时才有值。 */
    val memLimitBytes: Long? get() = sumOrNull { it.memLimitBytes }
    val memUsedBytes: Long? get() = sumOrNull { it.memUsedBytes }

    /** 平均内存使用率 —— 内存总量拿不到时的降级指标。 */
    val memUsedPercentAvg: Double?
        get() = nodes.mapNotNull { it.memUsedPercent }
            .takeIf { it.isNotEmpty() }
            ?.average()

    /** 运行中的 Routine Load 数（概览卡片的数字）。 */
    val runningRoutineLoadCount: Int get() = routineLoads.count { it.isRunning }

    private fun sumOrNull(selector: (ClusterNodeInfo) -> Long?): Long? {
        val values = nodes.mapNotNull(selector)
        return if (values.isEmpty()) null else values.sum()
    }
}
