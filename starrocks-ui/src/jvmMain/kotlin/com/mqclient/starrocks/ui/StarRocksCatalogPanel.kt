package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTable
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
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

/** 右侧详情面板的选中节点。 */
private sealed interface CatalogSelection {
    data object Database : CatalogSelection
    data class Table(val name: String) : CatalogSelection
    data class Column(val table: String, val column: String) : CatalogSelection
}

/**
 * 「数据目录」面板：左侧树（库 → 表文件夹 → 表 → 字段 / 分区，带图标 / 缩进引导线 /
 * 搜索过滤）+ 右侧详情面板（选中库 = 表清单概览；选中表 = 表结构表格 + DDL Tab；
 * 选中字段 = 字段属性）。
 *
 * - 元数据全部走 [queryRows]（复用 `executeQuery` 流式通道）；
 * - 单击表展开 / 收起并**懒加载**字段与分区；双击表 [openConsoleWithSql]
 *   打开 SQL 控制台并填入 `SELECT * FROM 表名 LIMIT 100;`（物化视图同样适用）；
 * - 分区查询失败（MySQL 不支持 `SHOW PARTITIONS`）**静默降级**，绝不写全局 error。
 */
@Composable
internal fun StarRocksCatalogPanel(connectionId: String, modifier: Modifier = Modifier) {
    var dbName by remember { mutableStateOf<String?>(null) }
    var tables by remember { mutableStateOf<List<CatalogTable>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dbExpanded by remember { mutableStateOf(true) }
    var tablesFolderExpanded by remember { mutableStateOf(true) }
    var mvFolderExpanded by remember { mutableStateOf(true) }
    var expandedTable by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<CatalogSelection?>(null) }
    var search by remember { mutableStateOf("") }
    // 字段：null = 加载中；空列表 = 加载完成但确实无字段。
    val columnCache = remember { mutableStateMapOf<String, List<CatalogColumn>?>() }
    val partitionCache = remember { mutableStateMapOf<String, PartitionLoadState>() }
    val ddlCache = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        error = null
        com.mqclient.core.log.Log.info("starrocks-ui", "[Catalog] load start (connectionId=$connectionId)")
        try {
            dbName = runCatching { queryRows(connectionId, "SELECT DATABASE();") }
                .getOrNull()?.rows?.firstOrNull()?.firstOrNull()?.toString()
                ?.takeIf { it.isNotBlank() }
            // 物化视图名单走 SHOW MATERIALIZED VIEWS（3.4 的 table_type 不可靠）；失败静默降级。
            val mvNames = runCatching {
                parseMaterializedViewNames(queryRows(connectionId, MATERIALIZED_VIEW_SQL))
            }.getOrDefault(emptySet())
            tables = parseTables(queryRows(connectionId, TABLES_SQL), mvNames)
            com.mqclient.core.log.Log.info("starrocks-ui", "[Catalog] load done: tables=${tables.size}, dbName=$dbName")
            columnCache.clear()
            partitionCache.clear()
            ddlCache.clear()
            expandedTable = null
        } catch (e: Throwable) {
            error = e.userMessage()
            tables = emptyList()
        } finally {
            loading = false
        }
    }

    fun ensureColumns(table: String) {
        if (columnCache.containsKey(table)) return
        columnCache[table] = null
        scope.launch {
            val columns = try {
                parseColumns(queryRows(connectionId, columnsSql(table)))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                emptyList()   // 局部降级：不写全局 error（表结构查不到只影响这一格）
            }
            columnCache[table] = columns
        }
    }

    fun ensurePartitions(table: String) {
        if (partitionCache.containsKey(table)) return
        partitionCache[table] = PartitionLoadState.Loading
        scope.launch {
            val state = try {
                PartitionLoadState.Loaded(parsePartitions(queryRows(connectionId, partitionsSql(table))))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                PartitionLoadState.Unsupported
            }
            partitionCache[table] = state
        }
    }

    /** 单击表：展开 / 收起 + 选中；字段与分区各自独立懒加载，互不影响、不染红整棵树。 */
    fun toggleTable(table: CatalogTable) {
        selection = CatalogSelection.Table(table.name)
        if (expandedTable == table.name) {
            expandedTable = null
            return
        }
        expandedTable = table.name
        ensureColumns(table.name)
        ensurePartitions(table.name)
    }

    /** 展开中的表选中时补拉字段（从库概览点表进来可能尚未加载）。 */
    fun ensureForSelection(target: CatalogSelection) {
        when (target) {
            is CatalogSelection.Table -> if (expandedTable == target.name) ensureColumns(target.name)
            is CatalogSelection.Column -> ensureColumns(target.table)
            else -> Unit
        }
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

    com.mqclient.core.log.Log.info(
        "starrocks-ui",
        "[Catalog] composing split layout: tables=${tables.size}, loading=$loading, error=$error",
    )
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
                            TreeChevron(dbExpanded)
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
                                expandedTable = expandedTable,
                                columnCache = columnCache,
                                partitionCache = partitionCache,
                                search = query,
                                selection = selection,
                                onToggle = ::toggleTable,
                                onOpenConsole = { table -> openConsoleWithSql(connectionId, previewSql(table.name)) },
                                onSelectColumn = { table, column ->
                                    selection = CatalogSelection.Column(table, column)
                                    ensureForSelection(CatalogSelection.Column(table, column))
                                },
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
                                    expandedTable = expandedTable,
                                    columnCache = columnCache,
                                    partitionCache = partitionCache,
                                    search = query,
                                    selection = selection,
                                    onToggle = ::toggleTable,
                                    onOpenConsole = { table -> openConsoleWithSql(connectionId, previewSql(table.name)) },
                                    onSelectColumn = { table, column ->
                                        selection = CatalogSelection.Column(table, column)
                                        ensureForSelection(CatalogSelection.Column(table, column))
                                    },
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
                onFetchDdl = { table ->
                    if (!ddlCache.containsKey(table)) {
                        ddlCache[table] = ""
                        scope.launch {
                            val ddl = try {
                                queryRows(connectionId, ddlSql(table)).rows.firstOrNull()
                                    ?.getOrNull(1)?.toString().orEmpty()
                            } catch (c: CancellationException) {
                                throw c
                            } catch (e: Throwable) {
                                ""
                            }
                            ddlCache[table] = ddl
                        }
                    }
                },
                onOpenTable = { name ->
                    selection = CatalogSelection.Table(name)
                    tables.firstOrNull { it.name == name }?.let(::toggleTable)
                },
                onRefreshColumns = { table -> ensureForSelection(CatalogSelection.Table(table)) },
            )
            }
    }
}

/** 树行通用外壳：缩进引导线（每层一条竖线）+ 点击回调 + 内容。 */
@Composable
private fun TreeRow(
    depth: Int,
    onClick: (() -> Unit)? = null,
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
                .let { m -> onClick?.let { m.pointerInput(Unit) { detectTapGestures(onTap = { it() }) } } ?: m }
                .padding(vertical = 6.dp),
        ) {
            content()
        }
    }
}

/**
 * 带引导线的小节标题行（字段 / 分区分组头、加载 / 空态提示）：
 * 与 TreeRow 共用同一套引导线绘制逻辑，保证从表（depth=2）到小节（depth=3）
 * 再到列（depth=4）的竖线逐层连续、不再断开。
 */
@Composable
private fun TreeHintRow(depth: Int, text: String, bold: Boolean = false) {
    TreeRow(depth = depth) {
        Text(
            text,
            style = if (bold) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Medium else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 3.dp),
        )
    }
}

/** 展开箭头（18dp）。 */
@Composable
private fun TreeChevron(expanded: Boolean) {
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
            TreeChevron(expanded)
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

private fun LazyListScope.tableItems(
    entries: List<CatalogTable>,
    keyPrefix: String,
    expandedTable: String?,
    columnCache: Map<String, List<CatalogColumn>?>,
    partitionCache: Map<String, PartitionLoadState>,
    search: String,
    selection: CatalogSelection?,
    onToggle: (CatalogTable) -> Unit,
    onOpenConsole: (CatalogTable) -> Unit,
    onSelectColumn: (String, String) -> Unit,
) {
    items(entries, key = { "$keyPrefix:${it.name}" }) { table ->
        val expanded = expandedTable == table.name
        val selected = selection is CatalogSelection.Table && selection.let { (it as CatalogSelection.Table).name == table.name }
        val columns = columnCache[table.name]
        // 搜索时字段按关键字过滤（仅对已加载的字段生效）。
        val visibleColumns = columns
            ?.takeIf { search.isEmpty() }
            ?: columns?.filter {
                it.name.contains(search, ignoreCase = true) || it.type.contains(search, ignoreCase = true)
            }

        Column {
            TreeRow(depth = 2, onClick = { onToggle(table) }) {
                TreeChevron(expanded)
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

            if (expanded) {
                // 字段小节（depth=3，引导线与表 depth=2 / 列 depth=4 逐层连续）
                TreeHintRow(
                    depth = 3,
                    text = stringResource("olap.starrocks.catalog.columns_label"),
                    bold = true,
                )
                when {
                    columns == null -> TreeHintRow(3, stringResource("olap.starrocks.catalog.loading"))
                    visibleColumns.isNullOrEmpty() ->
                        TreeHintRow(3, stringResource("olap.starrocks.catalog.columns_empty"))
                    else -> visibleColumns.forEach { column ->
                        TreeRow(depth = 4, onClick = { onSelectColumn(table.name, column.name) }) {
                            Icon(
                                Icons.Filled.ViewColumn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                column.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                            Text(
                                "(${column.type})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 分区小节（失败静默降级，不弹红字；引导线与字段区同层连续）
                TreeHintRow(
                    depth = 3,
                    text = stringResource("olap.starrocks.catalog.partitions_label"),
                    bold = true,
                )
                when (val state = partitionCache[table.name]) {
                    null, PartitionLoadState.Loading ->
                        TreeHintRow(3, stringResource("olap.starrocks.catalog.loading"))
                    PartitionLoadState.Unsupported ->
                        TreeHintRow(3, stringResource("olap.starrocks.catalog.partitions_unsupported"))
                    is PartitionLoadState.Loaded ->
                        if (state.partitions.isEmpty()) {
                            TreeHintRow(3, stringResource("olap.starrocks.catalog.partitions_empty"))
                        } else {
                            state.partitions.forEach { partition ->
                                TreeHintRow(4, partition.summary)
                            }
                        }
                }
            }
        }
    }
}

/**
 * 右侧详情面板：按选中节点动态渲染。
 * - 库：表清单概览（表名 / 类型 / 注释，行可点进入表详情）；
 * - 表：概览（表结构表格）+ DDL（SHOW CREATE TABLE 文本 + 复制）双 Tab；
 * - 字段：字段属性（PropertyTable）。
 */
@Composable
private fun CatalogDetailPane(
    selection: CatalogSelection?,
    dbName: String?,
    tables: List<CatalogTable>,
    columnCache: Map<String, List<CatalogColumn>?>,
    @Suppress("UNUSED_PARAMETER") partitionCache: Map<String, PartitionLoadState>,
    ddlCache: Map<String, String>,
    onFetchDdl: (String) -> Unit,
    onOpenTable: (String) -> Unit,
    onRefreshColumns: (String) -> Unit,
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
            ddl = ddlCache[selection.name],
            onFetchDdl = { onFetchDdl(selection.name) },
            onRefreshColumns = { onRefreshColumns(selection.name) },
        )

        is CatalogSelection.Column -> ColumnDetailPane(selection.table, selection.column, columnCache[selection.table])
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

/** 表详情：概览（表结构表格）+ DDL 双 Tab。 */
@Composable
private fun TableDetailPane(
    tableName: String,
    columns: List<CatalogColumn>?,
    ddl: String?,
    onFetchDdl: (String) -> Unit,
    onRefreshColumns: (String) -> Unit,
) {
    var tab by remember(tableName) { mutableStateOf(0) }
    LaunchedEffect(tableName) { onFetchDdl(tableName) }
    LaunchedEffect(tableName) { onRefreshColumns(tableName) }

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
                0 -> SchemaGrid(tableName, columns)
                else -> DdlPane(tableName, ddl)
            }
        }
    }
}

/**
 * 表结构详情：与「列」节点一致的属性面板风格 —— 每个字段一个分组
 * （字段名加粗标题 + [PropertyTable] 键值对），避免横向 6 列表格挤在一起。
 */
@Composable
private fun SchemaGrid(tableName: String, columns: List<CatalogColumn>?) {
    when {
        columns == null -> Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        columns.isEmpty() -> HintText(stringResource("olap.starrocks.catalog.columns_empty"), 16.dp)

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource("olap.starrocks.catalog.table_columns_title").withArg(columns.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(columns, key = { it.name }) { column ->
                Column {
                    Text(
                        column.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    PropertyTable(items = columnProperties(column))
                }
            }
        }
    }
}

/** 字段的完整属性键值对（表详情每个字段分组 / 列节点详情共用，风格一致）。 */
@Composable
private fun columnProperties(column: CatalogColumn): List<PropertyItem> = listOf(
    PropertyItem(stringResource("olap.starrocks.catalog.col_name"), column.name),
    PropertyItem(stringResource("olap.starrocks.catalog.col_type"), column.type),
    PropertyItem(stringResource("olap.starrocks.catalog.col_nullable"), column.nullable.ifBlank { "-" }),
    PropertyItem(stringResource("olap.starrocks.catalog.col_key"), column.key.ifBlank { "-" }),
    PropertyItem(stringResource("olap.starrocks.catalog.col_default"), column.default.ifBlank { "-" }),
    PropertyItem(stringResource("olap.starrocks.catalog.col_extra"), column.extra.ifBlank { "-" }),
    PropertyItem(stringResource("olap.starrocks.catalog.col_comment"), column.comment.ifBlank { "-" }),
)

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

/** 字段详情：该字段的完整属性（PropertyTable 键值展示）。 */
@Composable
private fun ColumnDetailPane(
    tableName: String,
    columnName: String,
    columns: List<CatalogColumn>?,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "$tableName · $columnName",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        when {
            columns == null -> HintText(stringResource("olap.starrocks.catalog.loading"), 16.dp)
            else -> {
                val column = columns.firstOrNull { it.name == columnName }
                if (column == null) {
                    HintText(stringResource("olap.starrocks.catalog.columns_empty"), 16.dp)
                } else {
                    PropertyTable(items = columnProperties(column))
                }
            }
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
