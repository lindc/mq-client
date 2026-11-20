package com.mqclient.starrocks.cluster

import kotlin.math.pow

/**
 * StarRocks 集群元数据结果集的**纯解析层**（无 JDBC、无协程，commonTest 直测）。
 *
 * ## 为什么要按"归一化列名"取值
 *
 * `SHOW BACKENDS` / `SHOW FRONTENDS` 的列名在 StarRocks 3.0 → 3.4 之间有漂移，且
 * Doris 系产品同名不同大小写（`LastHeartbeat` / `last_heartbeat` / `LAST_HEARTBEAT`）。
 * 因此所有取值都经过 [normalize]（小写 + 去掉非字母数字），再按候选名优先级命中
 * 第一个存在的列；**列不存在就返回 null**，绝不抛 —— 缺列只应该让某一行少一个
 * 字段，不应该把面板打成错误态。
 *
 * ## 两个数值形态的坑（实测口径，做 3.4 时确认过）
 *
 *  - 容量列（`TotalCapacity` / `AvailCapacity` / `DataUsedCapacity`）是**已格式化的
 *    字符串**（`1.234 GB`、`512 MB`），不是裸字节；`SHOW PROC '/backends'` 给的是
 *    裸字节。两种都要吃 → [parseBytes]。
 *  - 百分比列（`MemUsedPct` / `CpuUsedPct` / `UsedPct`）形如 `0.53 %`（带空格 + 百分号），
 *    部分版本给裸数字 → [parsePercent]。
 */

/** 列名归一化：小写并去掉 `_` / 空格 / 连字符，抹平版本与大小写差异。 */
internal fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

/** 按候选名优先级取列下标（第一个存在的胜出）；全都没有返回 null。 */
internal fun indexOfAny(columns: List<String>, vararg names: String): Int? {
    if (columns.isEmpty()) return null
    val normalized = columns.map(::normalize)
    return names.firstNotNullOfOrNull { candidate ->
        normalized.indexOf(normalize(candidate)).takeIf { it >= 0 }
    }
}

/** 取一行里某列的文字：缺列 / 空串 / `-` 一律 null。 */
internal fun List<Any?>.textAt(index: Int?): String? =
    index?.let { getOrNull(it) }
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "-" && it != "NULL" && it != "null" }

/** 存活 / 布尔列：`true` / `1` / `yes` / `y` 都算真（各版本写法不一）。 */
internal fun parseBool(raw: String?): Boolean =
    raw?.trim()?.lowercase() in setOf("true", "1", "yes", "y")

/**
 * 容量解析：`1.234 GB` / `512 MB` / `1024`（裸字节）三种形态统一成字节。
 * 单位按 1024 进制（StarRocks 的 PrettyPrinter 口径），兼容 `KiB` / 小写 / 无单位。
 */
internal fun parseBytes(raw: String?): Long? {
    val text = raw?.trim()?.replace(",", "")?.takeIf { it.isNotEmpty() && it != "-" } ?: return null
    val match = Regex("""^([0-9]*\.?[0-9]+)\s*([kKmMgGtTpP])?[iI]?[bB]?$""").find(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val exponent = when (match.groupValues[2].uppercase()) {
        "K" -> 1
        "M" -> 2
        "G" -> 3
        "T" -> 4
        "P" -> 5
        else -> 0
    }
    return (value * 1024.0.pow(exponent.toDouble())).toLong()
}

/** 百分比解析：`0.53 %` / `0.53%` / `0.53` 都归一成 0.53；`NaN` / 空 → null。 */
internal fun parsePercent(raw: String?): Double? =
    raw?.replace("%", "")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** 整数解析（`18` / `18.0` 都吃）。 */
internal fun parseInt(raw: String?): Int? = raw?.trim()?.toDoubleOrNull()?.toInt()

/** 长整数解析。 */
internal fun parseLong(raw: String?): Long? = raw?.trim()?.toDoubleOrNull()?.toLong()

/** 端口列表：按"有值才加"的顺序拼（与文档字段顺序一致）。 */
private fun portsOf(
    row: List<Any?>,
    vararg fields: Pair<String, Int?>,
): List<ClusterPort> = fields.mapNotNull { (label, index) ->
    row.textAt(index)?.let { ClusterPort(label, it) }
}

/**
 * `SHOW FRONTENDS` → FE 节点。
 *
 * 角色判定两级：`IsMaster=true` 或 `Role=LEADER` → 主节点；`Role=OBSERVER` → 观察者；
 * 其余按 follower 处理（老版本没有 `Role` 列，全落 follower 也不会错）。
 */
internal fun parseFrontends(
    columns: List<String>,
    rows: List<List<Any?>>,
): List<ClusterNodeInfo> {
    val nameIdx = indexOfAny(columns, "name", "hostname")
    val ipIdx = indexOfAny(columns, "ip", "host")
    val roleIdx = indexOfAny(columns, "role")
    val masterIdx = indexOfAny(columns, "ismaster")
    val aliveIdx = indexOfAny(columns, "alive", "isalive")
    val versionIdx = indexOfAny(columns, "version")
    val joinIdx = indexOfAny(columns, "jointime")
    val heartbeatIdx = indexOfAny(columns, "lastheartbeat")

    return rows.mapNotNull { row ->
        val ip = row.textAt(ipIdx)
        val name = row.textAt(nameIdx) ?: ip ?: return@mapNotNull null
        val rawRole = row.textAt(roleIdx)?.uppercase().orEmpty()
        val isMaster = parseBool(row.textAt(masterIdx))
        val role = when {
            isMaster || rawRole == "LEADER" -> ClusterNodeRole.FE_LEADER
            rawRole == "OBSERVER" -> ClusterNodeRole.FE_OBSERVER
            else -> ClusterNodeRole.FE_FOLLOWER
        }
        ClusterNodeInfo(
            nodeId = "fe:$name",
            role = role,
            name = name,
            ip = ip ?: name,
            ports = portsOf(
                row,
                "edit_log_port" to indexOfAny(columns, "editlogport"),
                "http_port" to indexOfAny(columns, "httpport"),
                "query_port" to indexOfAny(columns, "queryport"),
                "rpc_port" to indexOfAny(columns, "rpcport"),
            ),
            alive = parseBool(row.textAt(aliveIdx)),
            version = row.textAt(versionIdx) ?: "-",
            joinTime = row.textAt(joinIdx),
            lastHeartbeat = row.textAt(heartbeatIdx),
            extra = if (rawRole.isNotEmpty()) mapOf("role" to rawRole) else emptyMap(),
        )
    }
}

/**
 * `SHOW BACKENDS` / `SHOW COMPUTE NODES` → BE / CN 节点。
 *
 * 节点 id 用 `ip:heartbeat_port`（BackendId 在 CN 与 BE 之间可能为 0 / 负数，
 * 不可靠）；[role] 决定前缀与图标，CAPACITY 列在 CN 上通常为 0 或缺列。
 */
internal fun parseBackends(
    columns: List<String>,
    rows: List<List<Any?>>,
    role: ClusterNodeRole,
): List<ClusterNodeInfo> {
    val ipIdx = indexOfAny(columns, "ip", "host")
    val hbIdx = indexOfAny(columns, "heartbeatport")
    val beIdx = indexOfAny(columns, "beport")
    val httpIdx = indexOfAny(columns, "httpport")
    val brpcIdx = indexOfAny(columns, "brpcport")
    val aliveIdx = indexOfAny(columns, "alive", "isalive")
    val versionIdx = indexOfAny(columns, "version")
    val statusIdx = indexOfAny(columns, "status")
    val errIdx = indexOfAny(columns, "errmsg")
    val coresIdx = indexOfAny(columns, "cpucores")
    val cpuPctIdx = indexOfAny(columns, "cpuusedpct")
    val memPctIdx = indexOfAny(columns, "memusedpct")
    val totalIdx = indexOfAny(columns, "totalcapacity", "datatotalcapacity")
    val availIdx = indexOfAny(columns, "availcapacity", "dataavailcapacity")
    val usedIdx = indexOfAny(columns, "datausedcapacity")
    val usedPctIdx = indexOfAny(columns, "usedpct", "datausedpct")
    val tabletIdx = indexOfAny(columns, "tabletnum")
    val queryIdx = indexOfAny(columns, "numrunningqueries")
    val startIdx = indexOfAny(columns, "laststarttime")
    val heartbeatIdx = indexOfAny(columns, "lastheartbeat")

    val prefix = if (role == ClusterNodeRole.CN) "cn" else "be"

    return rows.mapNotNull { row ->
        val ip = row.textAt(ipIdx) ?: return@mapNotNull null
        val heartbeatPort = row.textAt(hbIdx) ?: row.textAt(beIdx) ?: "0"
        val total = parseBytes(row.textAt(totalIdx))
        val avail = parseBytes(row.textAt(availIdx))
        val used = parseBytes(row.textAt(usedIdx)) ?: if (total != null && avail != null) total - avail else null
        ClusterNodeInfo(
            nodeId = "$prefix:$ip:$heartbeatPort",
            role = role,
            name = "$ip:$heartbeatPort",
            ip = ip,
            ports = portsOf(
                row,
                "heartbeat_port" to hbIdx,
                "be_port" to beIdx,
                "http_port" to httpIdx,
                "brpc_port" to brpcIdx,
            ),
            alive = parseBool(row.textAt(aliveIdx)),
            version = row.textAt(versionIdx) ?: "-",
            cpuCores = parseInt(row.textAt(coresIdx)),
            cpuUsedPercent = parsePercent(row.textAt(cpuPctIdx)),
            memUsedPercent = parsePercent(row.textAt(memPctIdx)),
            diskTotalBytes = total,
            diskUsedBytes = used,
            diskAvailBytes = avail,
            diskUsedPercent = parsePercent(row.textAt(usedPctIdx)),
            tabletCount = parseLong(row.textAt(tabletIdx)),
            runningQueries = parseInt(row.textAt(queryIdx)),
            status = row.textAt(statusIdx),
            lastStartTime = row.textAt(startIdx),
            lastHeartbeat = row.textAt(heartbeatIdx),
            errorMessage = row.textAt(errIdx),
        )
    }
}

/**
 * `SHOW CATALOGS` → 外部 Catalog 节点。
 *
 * `default_catalog`（内建）也照常进图：它就是"内部目录"，隐去反而让用户以为
 * 第三层缺东西。类型（`hive` / `iceberg` / …）放进 [ClusterNodeInfo.extra]，
 * 拓扑卡片拿它当 badge。
 */
internal fun parseCatalogs(
    columns: List<String>,
    rows: List<List<Any?>>,
): List<ClusterNodeInfo> {
    val nameIdx = indexOfAny(columns, "name", "catalogname")
    val typeIdx = indexOfAny(columns, "catalogtype", "type")
    val commentIdx = indexOfAny(columns, "comment")

    return rows.mapNotNull { row ->
        val name = row.textAt(nameIdx) ?: return@mapNotNull null
        val type = row.textAt(typeIdx).orEmpty()
        ClusterNodeInfo(
            nodeId = "catalog:$name",
            role = ClusterNodeRole.CATALOG,
            name = name,
            // Catalog 没有 IP / 端口 —— 空串让属性面板显示 `-` 而不是假的 0.0.0.0。
            ip = "",
            ports = emptyList(),
            alive = true,
            version = "-",
            extra = buildMap {
                if (type.isNotEmpty()) put("type", type)
                row.textAt(commentIdx)?.let { put("comment", it) }
            },
        )
    }
}

/** `SHOW ROUTINE LOADS` → 导入任务（列表为空是正常态：集群上没建导入）。 */
internal fun parseRoutineLoads(
    columns: List<String>,
    rows: List<List<Any?>>,
): List<RoutineLoadInfo> {
    val idIdx = indexOfAny(columns, "id", "jobid")
    val nameIdx = indexOfAny(columns, "name", "jobname")
    val dbIdx = indexOfAny(columns, "dbname", "database")
    val tableIdx = indexOfAny(columns, "tablename", "table")
    val stateIdx = indexOfAny(columns, "state", "jobstate")
    val lagIdx = indexOfAny(columns, "lag", "maxerrorlag")

    return rows.mapNotNull { row ->
        val name = row.textAt(nameIdx) ?: return@mapNotNull null
        RoutineLoadInfo(
            id = row.textAt(idIdx).orEmpty(),
            name = name,
            dbName = row.textAt(dbIdx).orEmpty(),
            tableName = row.textAt(tableIdx).orEmpty(),
            state = row.textAt(stateIdx) ?: "UNKNOWN",
            lag = row.textAt(lagIdx),
        )
    }
}

/**
 * 把 `SHOW PROC '/backends'` 的内存列（`MemLimit` / `MemUsed`，**裸字节**）
 * 按 `ip:heartbeat_port` 合进已有 BE / CN 节点。
 *
 * 匹配口径：优先 `(ip, heartbeat_port)`；PROC 结果缺心跳端口时退化为按 IP 匹配，
 * 但**仅当该 IP 在两侧都唯一**才合并 —— 同机多实例时宁可缺内存数据，也不能把
 * A 实例的内存算到 B 头上（那会让概览的内存卡变成假数字）。
 */
internal fun applyProcBackendMemory(
    nodes: List<ClusterNodeInfo>,
    columns: List<String>,
    rows: List<List<Any?>>,
): List<ClusterNodeInfo> {
    if (nodes.isEmpty() || rows.isEmpty()) return nodes
    val ipIdx = indexOfAny(columns, "ip", "host")
    val hbIdx = indexOfAny(columns, "heartbeatport")
    val limitIdx = indexOfAny(columns, "memlimit")
    val usedIdx = indexOfAny(columns, "memused")

    data class Memory(val limit: Long?, val used: Long?)
    val byKey = mutableMapOf<String, Memory>()
    val byIp = mutableMapOf<String, MutableList<Memory>>()
    rows.forEach { row ->
        val ip = row.textAt(ipIdx) ?: return@forEach
        val port = row.textAt(hbIdx)
        val memory = Memory(parseBytes(row.textAt(limitIdx)), parseBytes(row.textAt(usedIdx)))
        if (port != null) byKey["$ip:$port"] = memory
        byIp.getOrPut(ip) { mutableListOf() }.add(memory)
    }

    // 节点侧同一 IP 的实例数：同机多实例时**不能**按 IP 合并 —— 那会把 A 的内存算给 B。
    val nodesPerIp = nodes.filter { it.role.isCompute }.groupingBy { it.ip }.eachCount()

    return nodes.map { node ->
        if (!node.role.isCompute) return@map node
        val port = node.ports.firstOrNull { it.label == "heartbeat_port" }?.port
        val direct = port?.let { byKey["${node.ip}:$it"] }
        val fallback = direct ?: byIp[node.ip]
            ?.takeIf { it.size == 1 && nodesPerIp[node.ip] == 1 }
            ?.first()
        if (fallback == null) node else node.copy(
            memLimitBytes = fallback.limit,
            memUsedBytes = fallback.used,
        )
    }}
