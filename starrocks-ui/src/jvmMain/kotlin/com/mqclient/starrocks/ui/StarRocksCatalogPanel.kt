package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.log.StarRocksLog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridTextCell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 一张表 / 物化视图 / 视图（名称 + 注释 + 类型 + 是否物化视图）。 */
internal data class CatalogTable(
    val name: String,
    val comment: String,
    val type: String,
    val materializedView: Boolean = false,
)

/** 一个字段：information_schema.columns 的完整属性。 */
internal data class CatalogColumn(
    val name: String,
    val type: String,
    val nullable: String,
    val key: String,
    val default: String,
    val extra: String,
    val comment: String,
)

/** 一个分区：SHOW PARTITIONS 关键列的人话摘要数据。 */
internal data class CatalogPartition(
    val name: String,
    val key: String,
    val range: String,
    val state: String,
    val rowCount: String,
    val dataSize: String,
    val distributionKey: String,
    val summary: String,
)

/** 分区加载状态：引擎不支持与「无分区」必须区分，不能都显示空。 */
private sealed interface PartitionLoadState {
    data object Loading : PartitionLoadState
    data object Unsupported : PartitionLoadState
    data class Loaded(val partitions: List<CatalogPartition>) : PartitionLoadState
}

private const val TABLES_SQL =
    "SELECT table_name, table_comment, table_type FROM information_schema.tables " +
        "WHERE table_schema = DATABASE();"

private const val TABLES_TABLE_TYPE_MV = "MATERIALIZED VIEW"

/**
 * 物化视图名单：`SHOW MATERIALIZED VIEWS`（StarRocks 专属，3.4.0 实测可靠）。
 * 不能靠 information_schema.tables.table_type == 'MATERIALIZED VIEW' ——
 * 3.4 的异步物化视图实际返回 `VIEW`（已实测），会被误判为普通视图。
 * MySQL 不支持该语句，调用方静默降级为空集。
 */
internal const val MATERIALIZED_VIEW_SQL = "SHOW MATERIALIZED VIEWS;"

/** 解析 SHOW MATERIALIZED VIEWS 的 name 列 → 物化视图名集合（列名按 lowercase 匹配，坏行跳过）。 */
internal fun parseMaterializedViewNames(result: StarRocksQueryRows): Set<String> {
    val nameIdx = result.columns.map { it.lowercase() }.indexOf("name")
    if (nameIdx < 0) return emptySet()   // MySQL / 意外列形状 → 无物化视图
    return result.rows.mapNotNull { row ->
        runCatching { row.getOrNull(nameIdx)?.toString()?.trim()?.takeIf { it.isNotBlank() } }.getOrNull()
    }.toSet()
}

/** 表名进 SQL 字符串字面量：单引号转义，防元数据里带引号的名称破坏语句。 */
internal fun columnsSql(table: String): String =
    "SELECT column_name, data_type, is_nullable, column_key, column_default, extra, column_comment " +
        "FROM information_schema.columns " +
        "WHERE table_schema = DATABASE() AND table_name = '${table.replace("'", "''")}';"

/** 分区查询：StarRocks 语法；MySQL 会报错，由调用方静默降级为「不支持」。 */
internal fun partitionsSql(table: String): String =
    "SHOW PARTITIONS FROM `${table.replace("`", "``")}`;"

/** 双击表名生成的查询 SQL：标识符用反引号包裹（内部反引号双写）。 */
internal fun previewSql(table: String): String =
    "SELECT * FROM `${table.replace("`", "``")}` LIMIT 100;"

/** DDL 查询：标识符用反引号包裹（内部反引号双写）。 */
internal fun ddlSql(table: String): String =
    "SHOW CREATE TABLE `${table.replace("`", "``")}`;"

/** 表清单解析：名称 / 注释 / 类型；物化视图由 [SHOW MATERIALIZED VIEWS 名单][mvNames] 或 table_type 识别。 */
internal fun parseTables(
    result: StarRocksQueryRows,
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
internal fun parseColumns(result: StarRocksQueryRows): List<CatalogColumn> = result.rows.mapNotNull { row ->
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
internal fun parsePartitions(result: StarRocksQueryRows): List<CatalogPartition> {
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

/**
 * ResultSet 的「形状摘要」：列名 + 前几行，供解析失败 / 丢行时判断是不是元数据列变了。
 *
 * 只取前 [maxRows] 行并交给 [StarRocksLog.truncate] 截断 —— 一个宽表一次能带回
 * 几百个字段，整段灌进日志既没用也会把上下文冲掉。
 */
private fun describeRows(result: StarRocksQueryRows, maxRows: Int = 3): String = buildString {
    append("columns=").append(result.columns)
    result.rows.take(maxRows).forEachIndexed { index, row ->
        append(" | row").append(index).append('=').append(row.map { it?.toString() ?: "NULL" })
    }
    if (result.rows.size > maxRows) append(" | (+").append(result.rows.size - maxRows).append(" more rows)")
}

/** 右侧详情面板的选中节点。字段不再是节点 —— 树只负责导航，字段在右侧表格里展示。 */
private sealed interface CatalogSelection {
    data object Database : CatalogSelection
    data class Table(val name: String) : CatalogSelection
}

/**
 * 「数据目录」面板：左侧树（库 → 表文件夹 → 表名 / 物化视图文件夹 → 视图名，带图标 /
 * 缩进引导线 / 搜索过滤）+ 右侧详情面板（选中库 = 表清单概览；选中表 = 字段表格 + DDL Tab）。
 *
 * - 左侧树只做**导航**：字段不再进树 —— 字段多的表会把树拖成无穷长廊，而且和右侧
 *   表结构完全重复；分区摘要也从树里移出，收在右侧概览底部的折叠小节里；
 * - 元数据全部走 [queryRows]（复用 `executeQuery` 流式通道）；
 * - 单击表选中并**懒加载**字段 / 分区；双击表 [openConsoleWithSql]
 *   打开 SQL 控制台并填入 `SELECT * FROM 表名 LIMIT 100;`（物化视图同样适用）；
 * - 分区查询失败（MySQL 不支持 `SHOW PARTITIONS`）**静默降级**，绝不写全局 error。
 */
@Composable
internal fun StarRocksCatalogPanel(connectionId: String, modifier: Modifier = Modifier) {
    // 全部状态按 connectionId 分键：宿主在同一个组合槽位里切换连接，不带键的
    // remember 会把 A 库的表树 / 字段缓存留在 B 的页面上（缓存里还存着 A 的结果）。
    var dbName by remember(connectionId) { mutableStateOf<String?>(null) }
    var tables by remember(connectionId) { mutableStateOf<List<CatalogTable>>(emptyList()) }
    var loading by remember(connectionId) { mutableStateOf(false) }
    var error by remember(connectionId) { mutableStateOf<String?>(null) }
    var dbExpanded by remember(connectionId) { mutableStateOf(true) }
    var tablesFolderExpanded by remember(connectionId) { mutableStateOf(true) }
    var mvFolderExpanded by remember(connectionId) { mutableStateOf(true) }
    var selection by remember(connectionId) { mutableStateOf<CatalogSelection?>(null) }
    var search by remember(connectionId) { mutableStateOf("") }
    // 字段：null = 加载中；空列表 = 加载完成但确实无字段。
    val columnCache = remember(connectionId) { mutableStateMapOf<String, List<CatalogColumn>?>() }
    val partitionCache = remember(connectionId) { mutableStateMapOf<String, PartitionLoadState>() }
    val ddlCache = remember(connectionId) { mutableStateMapOf<String, String>() }
    val scope = rememberConnectionScope(connectionId)

    /**
     * 拉表结构（字段）。
     *
     * `[Meta]` 打点三件事：开始 / 结束的行数；**解析后行数少于原始行数**时打 WARN 并附
     * 原始 ResultSet 形状（列名 + 前几行，截断 500）—— 这是「字段缺失」类问题
     * （information_schema 列名变化、权限被裁剪）最直接的证据；取数失败同样 WARN 带栈。
     */
    fun ensureColumns(table: String) {
        if (columnCache.containsKey(table)) return
        columnCache[table] = null
        scope.launch {
            val start = System.currentTimeMillis()
            StarRocksLog.meta("columns load start table=$table")
            val columns = try {
                val raw = queryRows(connectionId, columnsSql(table))
                val parsed = parseColumns(raw)
                val cost = System.currentTimeMillis() - start
                StarRocksLog.meta(
                    "columns parsed table=$table rows=${raw.rows.size} columns=${parsed.size} cost=${cost}ms",
                )
                if (parsed.size < raw.rows.size) {
                    // 坏行被 parseColumns 静默丢弃：不报错，但必须留证据。
                    StarRocksLog.meta(
                        LogLevel.WARN,
                        "columns dropped table=$table parsed=${parsed.size}/${raw.rows.size} " +
                            "raw=${StarRocksLog.truncate(describeRows(raw))}",
                    )
                } else {
                    // 形状细节只在需要时看：列名 + 首行，DEBUG。
                    StarRocksLog.meta(
                        LogLevel.DEBUG,
                        "columns shape table=$table ${StarRocksLog.truncate(describeRows(raw))}",
                    )
                }
                StarRocksLog.perf(action = "load_columns", type = "META", costMs = cost)
                parsed
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // 局部降级（只影响这一格），但对用户是一块空白：WARN + 栈。
                StarRocksLog.meta(
                    LogLevel.WARN,
                    "columns load failed table=$table " +
                        "cost=${System.currentTimeMillis() - start}ms error=${e.message}",
                    e,
                )
                emptyList()
            }
            columnCache[table] = columns
        }
    }

    fun ensurePartitions(table: String) {
        if (partitionCache.containsKey(table)) return
        partitionCache[table] = PartitionLoadState.Loading
        scope.launch {
            val start = System.currentTimeMillis()
            val state = try {
                val raw = queryRows(connectionId, partitionsSql(table))
                val parsed = parsePartitions(raw)
                val cost = System.currentTimeMillis() - start
                StarRocksLog.meta("partitions parsed table=$table rows=${raw.rows.size} parsed=${parsed.size} cost=${cost}ms")
                StarRocksLog.perf(action = "load_partitions", type = "META", costMs = cost)
                PartitionLoadState.Loaded(parsed)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // 「引擎不支持 SHOW PARTITIONS」是**预期降级**（MySQL），走 DEBUG 不刷 WARN；
                // 真正的失败（连接 / 权限）会先由 queryRows 的 WARN 记录。
                StarRocksLog.meta(
                    LogLevel.DEBUG,
                    "partitions unsupported table=$table (${e::class.simpleName}: ${e.message})",
                )
                PartitionLoadState.Unsupported
            }
            partitionCache[table] = state
        }
    }

    fun ensureDdl(table: String) {
        if (ddlCache.containsKey(table)) return
        ddlCache[table] = ""
        scope.launch {
            val start = System.currentTimeMillis()
            StarRocksLog.meta("ddl load start table=$table")
            val ddl = try {
                queryRows(connectionId, ddlSql(table)).rows.firstOrNull()
                    ?.getOrNull(1)?.toString().orEmpty()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                StarRocksLog.meta(
                    LogLevel.WARN,
                    "ddl load failed table=$table " +
                        "cost=${System.currentTimeMillis() - start}ms error=${e.message}",
                    e,
                )
                ""
            }
            val cost = System.currentTimeMillis() - start
            StarRocksLog.meta("ddl loaded table=$table chars=${ddl.length} cost=${cost}ms")
            StarRocksLog.perf(action = "load_ddl", type = "META", costMs = cost)
            ddlCache[table] = ddl
        }
    }

    /**
     * 刷新表清单。清缓存后若右侧仍停在某张表上，立即补拉它的字段 / 分区 —— 否则
     * 详情面板的 `LaunchedEffect(tableName)` 不会因表名未变而重跑，界面会永远停在 spinner。
     */
    suspend fun load() {
        loading = true
        error = null
        val loadStart = System.currentTimeMillis()
        StarRocksLog.meta("load start connectionId=$connectionId")
        try {
            runPanelLoad(
                onError = { e ->
                    StarRocksLog.meta(
                        LogLevel.WARN,
                        "load failed connectionId=$connectionId " +
                            "cost=${System.currentTimeMillis() - loadStart}ms error=${e.message}",
                        e,
                    )
                    error = e.userMessage()
                    tables = emptyList()
                },
            ) {
                // 三个可选查询用 runOptional：失败降级为 null / 空集，但**取消必须上抛**
                // （裸 runCatching 会把 CancellationException 一起吞掉，切连接后还会继续发 SQL）。
                dbName = runOptional { queryRows(connectionId, "SELECT DATABASE();") }
                    ?.rows?.firstOrNull()?.firstOrNull()?.toString()
                    ?.takeIf { it.isNotBlank() }
                // 物化视图名单走 SHOW MATERIALIZED VIEWS（3.4 的 table_type 不可靠）；失败静默降级。
                val mvNames = runOptional {
                    parseMaterializedViewNames(queryRows(connectionId, MATERIALIZED_VIEW_SQL))
                }.orEmpty()
                val rawTables = queryRows(connectionId, TABLES_SQL)
                val parsedTables = parseTables(rawTables, mvNames)
                if (parsedTables.size < rawTables.rows.size) {
                    // 表清单同样会因单行坏数据被静默丢弃：留证据（列名 + 前几行）。
                    StarRocksLog.meta(
                        LogLevel.WARN,
                        "tables dropped parsed=${parsedTables.size}/${rawTables.rows.size} " +
                            "raw=${StarRocksLog.truncate(describeRows(rawTables))}",
                    )
                }
                tables = parsedTables
                StarRocksLog.meta(
                    "load done connectionId=$connectionId tables=${tables.size} " +
                        "mv=${mvNames.size} db=${dbName ?: "-"} cost=${System.currentTimeMillis() - loadStart}ms",
                )
                StarRocksLog.perf(action = "load_catalog", type = "META", costMs = System.currentTimeMillis() - loadStart)
                columnCache.clear()
                partitionCache.clear()
                ddlCache.clear()
                (selection as? CatalogSelection.Table)?.let { table ->
                    ensureColumns(table.name)
                    ensurePartitions(table.name)
                    ensureDdl(table.name)
                }
            }
        } finally {
            // 取消（切连接）也要复位 spinner；状态按 connectionId 分键，写的是旧状态，无害。
            loading = false
        }
    }

    /** 单击表：选中 + 懒加载右侧要用的字段 / 分区（树只导航，不做展开）。 */
    fun selectTable(table: CatalogTable) {
        selection = CatalogSelection.Table(table.name)
        ensureColumns(table.name)
        ensurePartitions(table.name)
    }

    LaunchedEffect(connectionId) { load() }

    val normalTables = tables.filter { !it.materializedView }
    val materializedViews = tables.filter { it.materializedView }
    val query = search.trim()
    val filteredNormal = if (query.isEmpty()) normalTables else normalTables.filter {
        it.name.contains(query, ignoreCase = true) || it.comment.contains(query, ignoreCase = true)
    }
    val filteredMv = if (query.isEmpty()) materializedViews else materializedViews.filter {
        it.name.contains(query, ignoreCase = true) || it.comment.contains(query, ignoreCase = true)
    }

    Row(modifier.fillMaxSize()) {
        // ── 左侧树 ──
        Column(Modifier.weight(0.42f).fillMaxHeight()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource("olap.starrocks.catalog.title"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource("olap.starrocks.catalog.refresh"))
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = {
                    Text(
                        stringResource("olap.starrocks.catalog.search_placeholder"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )

            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }

                error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource("olap.starrocks.catalog.error_prefix") + error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                tables.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource("olap.starrocks.catalog.empty"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                    // 第一层：库（可折叠，点击选中 → 右侧表清单）
                    item {
                        TreeRow(depth = 0, onClick = {
                            dbExpanded = !dbExpanded
                            selection = CatalogSelection.Database
                        }) {
                            Chevron(dbExpanded)
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                            )
                            Text(
                                dbName ?: stringResource("olap.starrocks.catalog.current_db"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                            Text(
                                stringResource("olap.starrocks.catalog.table_count").withArg(tables.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    if (dbExpanded) {
                        // 第二层 A：普通表文件夹
                        folderItem(
                            label = stringResource("olap.starrocks.catalog.tables_folder"),
                            count = filteredNormal.size,
                            expanded = tablesFolderExpanded,
                            onToggle = { tablesFolderExpanded = !tablesFolderExpanded },
                        )
                        if (tablesFolderExpanded) {
                            tableItems(
                                entries = filteredNormal,
                                keyPrefix = "t",
                                selection = selection,
                                onSelect = ::selectTable,
                                onOpenConsole = { table -> openConsoleWithSql(connectionId, previewSql(table.name)) },
                            )
                        }

                        // 第二层 B：物化视图文件夹（Layers 图标区分）
                        folderItem(
                            label = stringResource("olap.starrocks.catalog.mv_folder"),
                            count = filteredMv.size,
                            expanded = mvFolderExpanded,
                            onToggle = { mvFolderExpanded = !mvFolderExpanded },
                            materializedView = true,
                        )
                        if (mvFolderExpanded) {
                            if (filteredMv.isEmpty()) {
                                item {
                                    HintText(
                                        stringResource("olap.starrocks.catalog.mv_empty"),
                                        startIndent = 48.dp,
                                    )
                                }
                            } else {
                                tableItems(
                                    entries = filteredMv,
                                    keyPrefix = "mv",
                                    selection = selection,
                                    onSelect = ::selectTable,
                                    onOpenConsole = { table -> openConsoleWithSql(connectionId, previewSql(table.name)) },
                                )
                            }
                        }
                    }
                }
            }
        } // 左侧树 Column 闭合

        VerticalDivider()

        // ── 右侧详情面板 ──
        Column(Modifier.weight(0.58f).fillMaxHeight()) {
            CatalogDetailPane(
                selection = selection,
                dbName = dbName,
                tables = tables,
                columnCache = columnCache,
                partitionCache = partitionCache,
                ddlCache = ddlCache,
                onFetchDdl = { table -> ensureDdl(table) },
                onOpenTable = { name ->
                    tables.firstOrNull { it.name == name }?.let(::selectTable)
                },
                onEnsureTable = { table ->
                    ensureColumns(table)
                    ensurePartitions(table)
                },
            )
            }
    }
}

/**
 * 树行通用外壳：缩进引导线（每层一条竖线）+ 单击 / 双击回调 + 内容。
 *
 * 单击**立即**回调，不等双击超时 —— 选表要立刻出结构，不能为了双击识别搭上 300ms。
 */
@Composable
private fun TreeRow(
    depth: Int,
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        if (depth > 0) {
            Box(Modifier.matchParentSize()) {
                repeat(depth) { i ->
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = (i * 16 + 8).dp)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = (depth * 16).dp)
                .fillMaxWidth()
                .let { m ->
                    if (onClick == null) {
                        m
                    } else {
                        m.pointerInput(Unit) {
                            detectTapAndDoubleTap(onTap = onClick, onDoubleTap = onDoubleClick)
                        }
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            content()
        }
    }
}

/**
 * 首击立即 [onTap]，双击窗口内的第二击再补一次 [onDoubleTap]。
 *
 * 不用 `detectTapGestures(onTap, onDoubleTap)` 的原因：它必须等过双击超时（~300ms）
 * 才能确定「不是双击」，于是单击选表会明显卡顿。这里的语义是「先选中，再打开控制台」，
 * 双击时首击的选中动作已经完成，正好是用户预期的顺序。
 */
private suspend fun PointerInputScope.detectTapAndDoubleTap(
    onTap: () -> Unit,
    onDoubleTap: (() -> Unit)? = null,
) {
    var lastTapUptime = 0L
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        val isDoubleTap = onDoubleTap != null &&
            up.uptimeMillis - lastTapUptime <= viewConfiguration.doubleTapTimeoutMillis
        lastTapUptime = up.uptimeMillis
        if (isDoubleTap) onDoubleTap() else onTap()
    }
}

/** 展开箭头（18dp）。 */
@Composable
private fun Chevron(expanded: Boolean) {
    Icon(
        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

private fun LazyListScope.folderItem(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    materializedView: Boolean = false,
) {
    item {
        TreeRow(depth = 1, onClick = onToggle) {
            Chevron(expanded)
            Icon(
                if (materializedView) Icons.Filled.Layers else Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp),
            )
            Text(
                stringResource("olap.starrocks.catalog.folder_count").withArg(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 表 / 物化视图行：单行导航项，**不再展开字段或分区**。字段在右侧二维表格，
 * 分区在右侧概览底部的折叠小节；树只回答「有哪些对象」。
 *
 * 单击选中（右侧立即加载结构），双击打开 SQL 控制台并填入 `SELECT * ... LIMIT 100`。
 */
private fun LazyListScope.tableItems(
    entries: List<CatalogTable>,
    keyPrefix: String,
    selection: CatalogSelection?,
    onSelect: (CatalogTable) -> Unit,
    onOpenConsole: (CatalogTable) -> Unit,
) {
    items(entries, key = { "$keyPrefix:${it.name}" }) { table ->
        val selected = (selection as? CatalogSelection.Table)?.name == table.name
        TreeRow(
            depth = 2,
            onClick = { onSelect(table) },
            onDoubleClick = { onOpenConsole(table) },
        ) {
            Icon(
                when {
                    table.materializedView -> Icons.Filled.Layers
                    table.type == "VIEW" -> Icons.Filled.Visibility
                    else -> Icons.Filled.TableChart
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
            Column(Modifier.padding(start = 4.dp).weight(1f)) {
                Text(
                    table.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (table.comment.isNotBlank()) {
                    Text(
                        table.comment,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 右侧详情面板：按选中节点动态渲染。
 * - 库：表清单概览（表名 / 类型 / 注释，行可点进入表详情）；
 * - 表：概览（字段二维表格 + 分区折叠小节）+ DDL（SHOW CREATE TABLE 文本 + 复制）双 Tab。
 */
@Composable
private fun CatalogDetailPane(
    selection: CatalogSelection?,
    dbName: String?,
    tables: List<CatalogTable>,
    columnCache: Map<String, List<CatalogColumn>?>,
    partitionCache: Map<String, PartitionLoadState>,
    ddlCache: Map<String, String>,
    onFetchDdl: (String) -> Unit,
    onOpenTable: (String) -> Unit,
    onEnsureTable: (String) -> Unit,
) {
    when (selection) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource("olap.starrocks.catalog.detail_hint"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CatalogSelection.Database -> DatabaseDetailPane(dbName, tables, onOpenTable)

        is CatalogSelection.Table -> TableDetailPane(
            tableName = selection.name,
            columns = columnCache[selection.name],
            partitions = partitionCache[selection.name],
            ddl = ddlCache[selection.name],
            onFetchDdl = { onFetchDdl(selection.name) },
            onEnsureTable = { onEnsureTable(selection.name) },
        )
    }
}

/** 库详情：库内表清单概览（表名 / 类型 / 注释），行可点进入表详情。 */
@Composable
private fun DatabaseDetailPane(
    dbName: String?,
    tables: List<CatalogTable>,
    onOpenTable: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            dbName ?: stringResource("olap.starrocks.catalog.current_db"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource("olap.starrocks.catalog.detail_tables").withArg(tables.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(tables, key = { it.name }) { table ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(table.name) { detectTapGestures(onTap = { onOpenTable(table.name) }) }
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        when {
                            table.materializedView -> Icons.Filled.Layers
                            table.type == "VIEW" -> Icons.Filled.Visibility
                            else -> Icons.Filled.TableChart
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        table.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        table.type.ifBlank { "-" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Text(
                        table.comment.ifBlank { "-" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp).weight(1f),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** 表详情：概览（字段二维表格 + 分区折叠小节）+ DDL 双 Tab。 */
@Composable
private fun TableDetailPane(
    tableName: String,
    columns: List<CatalogColumn>?,
    partitions: PartitionLoadState?,
    ddl: String?,
    onFetchDdl: (String) -> Unit,
    onEnsureTable: (String) -> Unit,
) {
    var tab by remember(tableName) { mutableStateOf(0) }
    // 从库概览直接点表进来时字段 / 分区还没拉过；缓存命中时 onEnsureTable 是空操作。
    LaunchedEffect(tableName) {
        onFetchDdl(tableName)
        onEnsureTable(tableName)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            tableName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource("olap.starrocks.catalog.tab_overview")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource("olap.starrocks.catalog.tab_ddl")) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> OverviewPane(columns, partitions)
                else -> DdlPane(tableName, ddl)
            }
        }
    }
}

/**
 * 概览 Tab：字段二维表格 + 分区折叠小节。
 *
 * 字段用传统 DataGrid（Name / Type / Nullable / Key / Default / Extra / Comment）
 * 而不是「一字段一卡片」：字段几十个时卡片要滚很久才能看完，而且无法横向对比类型
 * 与默认值 —— 这正是数据库客户端（DBeaver / Navicat）用表格的理由。
 */
@Composable
private fun OverviewPane(columns: List<CatalogColumn>?, partitions: PartitionLoadState?) {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        when {
            columns == null -> Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            columns.isEmpty() -> HintText(stringResource("olap.starrocks.catalog.columns_empty"), 0.dp)

            else -> {
                Text(
                    stringResource("olap.starrocks.catalog.table_columns_title").withArg(columns.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                ColumnDataGrid(columns, Modifier.weight(1f))
            }
        }
        PartitionSection(partitions)
    }
}

/** 绑定 [CatalogColumn] 的列定义：省掉每处重复的泛型实参。 */
private fun gridColumn(
    title: String,
    weight: Float,
    cell: @Composable (CatalogColumn) -> Unit,
): DataGridColumn<CatalogColumn> = DataGridColumn(title = title, weight = weight, cell = cell)

/**
 * 字段表：只负责「哪 7 列、各占多宽、每格取哪个字段」—— 表格本身完全交给
 * `ui-common` 的 [DataGrid]，与 IBM MQ / Kafka 属性对话框共用同一套行高（40dp）、
 * 斑马纹（`surfaceVariant@30%`）、4dp 圆角、8dp 行距与 bodyMedium 字号。
 *
 * 列宽用整数比例（Name 2f / Type 1.5f / Comment 2f / 其余 1f），长字段名只会被
 * 省略号截断，不会把表格撑变形；表头与行共用同一份 [DataGridColumn]，列宽不会漂移。
 */
@Composable
private fun ColumnDataGrid(columns: List<CatalogColumn>, modifier: Modifier = Modifier) {
    DataGrid(
        columns = listOf(
            gridColumn(stringResource("olap.starrocks.catalog.col_name"), 2f) { column ->
                DataGridTextCell(column.name, emphasized = true)
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_type"), 1.5f) { column ->
                DataGridTextCell(column.type)
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_nullable"), 1f) { column ->
                DataGridTextCell(column.nullable.ifBlank { "-" }, subdued = true)
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_key"), 1f) { column ->
                DataGridTextCell(column.key.ifBlank { "-" }, subdued = true)
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_default"), 1.2f) { column ->
                DataGridTextCell(column.default.ifBlank { "-" }, subdued = column.default.isBlank())
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_extra"), 1f) { column ->
                DataGridTextCell(column.extra.ifBlank { "-" }, subdued = column.extra.isBlank())
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_comment"), 2f) { column ->
                DataGridTextCell(column.comment.ifBlank { "-" }, subdued = column.comment.isBlank())
            },
        ),
        rows = columns,
        rowKey = { it.name },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 概览底部的分区小节：默认收起成一行，展开后限高滚动，不抢字段表格的高度。
 *
 * 分区从左侧树移到这里：它属于表的**细节**，不属于导航；引擎不支持
 * `SHOW PARTITIONS`（MySQL）时只留一行说明，不写全局 error。
 */
@Composable
private fun PartitionSection(partitions: PartitionLoadState?) {
    when (partitions) {
        is PartitionLoadState.Loaded -> if (partitions.partitions.isNotEmpty()) {
            PartitionList(partitions.partitions)
        }

        PartitionLoadState.Unsupported ->
            HintText(stringResource("olap.starrocks.catalog.partitions_unsupported"), 0.dp)

        else -> Unit
    }
}

/** 分区清单：折叠头（分区数）+ 展开后的摘要行（每行一个分区，单行省略）。 */
@Composable
private fun PartitionList(partitions: List<CatalogPartition>) {
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onTap = { expanded = !expanded }) }
            .padding(vertical = 4.dp),
    ) {
        Chevron(expanded)
        Text(
            stringResource("olap.starrocks.catalog.partitions_title").withArg(partitions.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
            items(partitions) { partition ->
                Text(
                    partition.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                )
            }
        }
    }
}

/** DDL Tab：SHOW CREATE TABLE 文本（等宽、可选中）+ 一键复制。 */
@Composable
private fun DdlPane(tableName: String, ddl: String?) {
    val clipboard = LocalClipboardManager.current
    when {
        ddl == null -> Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        ddl.isBlank() -> Text(
            stringResource("olap.starrocks.catalog.ddl_empty"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )

        else -> Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(ddl)) }) {
                Text(stringResource("olap.starrocks.catalog.ddl_copy"), style = MaterialTheme.typography.labelMedium)
            }
            // 共享 CodeBlock：与 Profile 查看器完全同源——主题色底 / 无边框 /
            // 8dp 圆角 / 16dp padding / 等宽字体，两处视觉始终一致。
            CodeBlock(
                text = AnnotatedString(ddl),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 树里的中性提示行（非错误、非加载动画）。 */
@Composable
private fun HintText(
    text: String,
    startIndent: Dp,
    bold: Boolean = false,
) {
    Text(
        text,
        style = if (bold) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.Medium else null,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = startIndent, top = 3.dp, bottom = 3.dp),
    )
}
