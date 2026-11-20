package com.mqclient.starrocks.system

import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.starrocks.cluster.indexOfAny
import com.mqclient.starrocks.cluster.textAt

/**
 * 系统信息（变量 / FE 配置）的**纯解析层**：无 JDBC、无协程，commonTest 直测。
 *
 * 与 [com.mqclient.starrocks.cluster] 的解析层同一套口径：列名先归一化再按候选名
 * 命中，缺列返回 null 而不是抛 —— 不同版本（3.0 → 3.4）与 Doris 系的列名漂移
 * 只应该让某一格显示 `-`，不应该把面板打成错误态。
 *
 * ## 为什么变量来自 SQL 而不是 FE Web 页面
 *
 * FE Web 的 `/variable` 页「Variable Info」段落就是 `name=value` 的纯文本
 * （3.4.0 实测，与 `SHOW VARIABLES` 完全同源）；「Configure Info」段落是
 * `ADMIN SHOW FRONTEND CONFIG` 的表格。两者都能用 SQL 拿到，于是不必引入
 * HTML 抓取 —— SQL 走的是同一条已认证的 MySQL 协议连接，也不需要额外暴露 8030。
 */

/** 一个会话 / 全局变量（`SHOW [GLOBAL] VARIABLES` 的一行）。 */
data class SessionVariable(
    val name: String,
    val value: String,
)

/**
 * 一个 FE 配置项（`ADMIN SHOW FRONTEND CONFIG` 的一行）。
 *
 * [isMutable] 三态：`true` / `false` / null（老版本没有该列）。null 与 false 不能
 * 合并 —— 前者是「不知道」，后者是「明确不可在线改」，运维据此决定要不要重启 FE。
 */
data class FeConfigEntry(
    val key: String,
    val value: String,
    val type: String,
    val isMutable: Boolean?,
    val comment: String,
)

/**
 * `SHOW VARIABLES` / `SHOW GLOBAL VARIABLES` → 变量列表。
 *
 * 3.4.0 实测只有两列（`Variable_name` / `Value`）；个别版本带
 * `Default Value` / `IsMutable` / `Comment`，多出来的列这里不建模 ——
 * 变量页只需要「当前值」，而「哪些被改过」需要拿它跟默认值比，属于另一个功能。
 *
 * 空名行丢弃（`SHOW` 的末行偶尔是空行）；值保留原始空白（变量的值可能是有意义的空格）。
 */
fun parseVariables(columns: List<String>, rows: List<List<Any?>>): List<SessionVariable> {
    val nameIdx = indexOfAny(columns, "variablename", "name", "variable")
    val valueIdx = indexOfAny(columns, "value", "variablevalue")

    return rows.mapNotNull { row ->
        val name = row.textAt(nameIdx) ?: return@mapNotNull null
        SessionVariable(name = name, value = row.textAt(valueIdx).orEmpty())
    }
}

/**
 * `ADMIN SHOW FRONTEND CONFIG` → FE 配置项列表。
 *
 * 列：`Key` / `AliasNames` / `Value` / `Type` / `IsMutable` / `Comment`（3.4.0 实测）。
 * `AliasNames` 是历史别名的 JSON 数组，界面上没地方放，这里不建模（日志里能查到原始值）。
 */
fun parseFeConfig(columns: List<String>, rows: List<List<Any?>>): List<FeConfigEntry> {
    val keyIdx = indexOfAny(columns, "key", "configname")
    val valueIdx = indexOfAny(columns, "value", "configvalue")
    val typeIdx = indexOfAny(columns, "type")
    val mutableIdx = indexOfAny(columns, "ismutable", "mutable")
    val commentIdx = indexOfAny(columns, "comment", "description")

    return rows.mapNotNull { row ->
        val key = row.textAt(keyIdx) ?: return@mapNotNull null
        FeConfigEntry(
            key = key,
            value = row.textAt(valueIdx).orEmpty(),
            type = row.textAt(typeIdx).orEmpty(),
            // 缺列 → null（不知道），有值但解析不出布尔 → null，两者都不是 false。
            isMutable = row.textAt(mutableIdx)?.let { raw ->
                when (raw.trim().lowercase()) {
                    "true", "1", "yes", "y" -> true
                    "false", "0", "no", "n" -> false
                    else -> null
                }
            },
            comment = row.textAt(commentIdx).orEmpty(),
        )
    }
}

/**
 * HA 概览：从 FE 列表导出的「选主是否健康」。
 *
 * StarRocks FE 的 HA 用 BDB JE 的**多数派**语义：可选举节点 = LEADER + FOLLOWER
 * （OBSERVER 不参与投票）。存活可选举节点数 > 总数的一半才可能选出 leader ——
 * 这条判断来自 BDB 的 quorum 规则，不是自创阈值。
 *
 * @param leaderName 当前 LEADER 的节点名（没有则为 null：集群正在选主 / 不可用）。
 * @param electableTotal LEADER + FOLLOWER 总数。
 * @param electableAlive 其中存活的个数。
 * @param observerTotal OBSERVER 总数（只读副本，不计入 quorum）。
 */
data class HaSummary(
    val leaderName: String?,
    val electableTotal: Int,
    val electableAlive: Int,
    val observerTotal: Int,
) {
    /** 存活可选举节点是否过半（BDB quorum）。 */
    val quorumHealthy: Boolean
        get() = electableTotal > 0 && electableAlive * 2 > electableTotal

    /** 可容忍的进一步宕机台数：再挂这么多台就丢 quorum（最少 0，不会报负数）。 */
    val toleratedFailures: Int
        get() = if (electableTotal == 0) 0 else ((electableAlive - 1) - (electableTotal / 2)).coerceAtLeast(0)
}

/**
 * 由 FE 节点列表算 HA 摘要（[HaSummary]）。
 *
 * 只消费 [ClusterNodeInfo] 里已有的角色与存活位，
 * 不重新解析 SQL —— HA 页与拓扑 / 概览共享同一份 `SHOW FRONTENDS` 结果，
 * 避免同一个集群在两处显示出不同的角色。
 */
fun haSummary(nodes: List<com.mqclient.starrocks.cluster.ClusterNodeInfo>): HaSummary {
    val frontends = nodes.filter { it.role.isFrontend }
    val electable = frontends.filter { it.role.isElectable }
    return HaSummary(
        // LEADER 必须是**存活**的那台：Role 列在节点已经宕机时仍可能残留 LEADER，
        // 当成「当前主节点」会让 HA 页在真正丢主时显示一个绿色的假象。
        leaderName = frontends.firstOrNull { it.role == ClusterNodeRole.FE_LEADER && it.alive }?.name,
        electableTotal = electable.size,
        electableAlive = electable.count { it.alive },
        observerTotal = frontends.count { it.role == ClusterNodeRole.FE_OBSERVER },
    )
}
