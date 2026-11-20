package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.catalog.CatalogColumn
import com.mqclient.starrocks.catalog.CatalogTable
import com.mqclient.starrocks.catalog.MATERIALIZED_VIEW_SQL
import com.mqclient.starrocks.catalog.TABLES_SQL
import com.mqclient.starrocks.catalog.columnsSql
import com.mqclient.starrocks.catalog.ddlSql
import com.mqclient.starrocks.catalog.parseColumns
import com.mqclient.starrocks.catalog.parseMaterializedViewNames
import com.mqclient.starrocks.catalog.parsePartitions
import com.mqclient.starrocks.catalog.parseTables
import com.mqclient.starrocks.catalog.partitionsSql
import com.mqclient.starrocks.catalog.previewSql
import com.mqclient.starrocks.log.StarRocksLog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
 *
 * ## 文件组织
 *
 * 数据目录的代码分五处，按「谁负责什么」切：
 *  - 本文件 —— 面板状态机（加载 / 缓存 / 选中）与左右分栏布局；
 *  - [StarRocksCatalogTree.kt] —— 左侧树的行与手势（单击选中、双击开控制台）；
 *  - [StarRocksCatalogDetailPane.kt] —— 右侧详情（表清单 / 字段表格 / DDL / 分区）；
 *  - [StarRocksCatalogState.kt] —— 两侧共享的选中态与分区加载态；
 *  - `starrocks-service` 的 `catalog/` —— SQL 构造与结果解析（与 cluster/、system/
 *    同一约定：service 决定发什么 SQL，UI 只渲染）。
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
