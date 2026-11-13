package com.mqclient.starrocks.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 一张表 / 物化视图（名称 + 注释 + 是否物化视图）。 */
private data class CatalogTable(
    val name: String,
    val comment: String,
    val materializedView: Boolean = false,
)

/** 一个字段（名称 + 类型）。 */
private data class CatalogColumn(val name: String, val type: String)

/** 一个分区：`SHOW PARTITIONS` 一行的可读摘要。 */
internal data class CatalogPartition(val summary: String)

/** 分区加载状态：引擎不支持与「无分区」必须区分，不能都显示空。 */
private sealed interface PartitionLoadState {
    data object Loading : PartitionLoadState
    data object Unsupported : PartitionLoadState
    data class Loaded(val partitions: List<CatalogPartition>) : PartitionLoadState
}

private const val TABLES_SQL =
    "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = DATABASE();"

/** StarRocks 的物化视图（MySQL 没有这个 table_type，返回空集即可）。 */
internal const val MATERIALIZED_VIEW_SQL =
    "SELECT TABLE_NAME FROM information_schema.tables " +
        "WHERE table_schema = DATABASE() AND table_type = 'MATERIALIZED VIEW';"

/** 表名进 SQL 字符串字面量：单引号转义，防元数据里带引号的名称破坏语句。 */
internal fun columnsSql(table: String): String =
    "SELECT column_name, data_type FROM information_schema.columns " +
        "WHERE table_schema = DATABASE() AND table_name = '${table.replace("'", "''")}';"

/** 分区查询：StarRocks 语法；MySQL 会报错，由调用方静默降级为「不支持」。 */
internal fun partitionsSql(table: String): String =
    "SHOW PARTITIONS FROM `${table.replace("`", "``")}`;"

/** 双击表名生成的查询 SQL：标识符用反引号包裹（内部反引号双写）。 */
internal fun previewSql(table: String): String =
    "SELECT * FROM `${table.replace("`", "``")}` LIMIT 100;"

/**
 * `SHOW PARTITIONS` 结果列名随引擎 / 版本变化，这里不绑定具体列：
 * 把一行里非空单元格拼成摘要（单行省略显示），任何形状都能展示点东西。
 */
internal fun parsePartitions(result: StarRocksQueryRows): List<CatalogPartition> =
    result.rows.mapNotNull { row ->
        row.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.let { CatalogPartition(it) }
    }

/**
 * 「数据目录」只读面板：库 → 文件夹（普通表 / 物化视图）→ 表 → 字段 + 分区。
 *
 * - 元数据全部走 [queryRows]（复用 `executeQuery` 流式通道）；
 * - 单击表展开 / 收起并**懒加载**字段与分区；双击表 [openConsoleWithSql]
 *   打开 SQL 控制台并填入 `SELECT * FROM 表名 LIMIT 100;`（物化视图同样适用）；
 * - 分区查询失败（MySQL 不支持 `SHOW PARTITIONS`）**静默降级**为「该引擎不支持
 *   查看分区」，绝不写全局 error 把整棵树染红 —— 普通 SQL 错误不关连接
 *   （见 MysqlJdbcConnection 的中断分类），后续展开 / 刷新照常可用。
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
    // 字段：null = 加载中；空列表 = 加载完成但确实无字段。
    val columnCache = remember { mutableStateMapOf<String, List<CatalogColumn>?>() }
    val partitionCache = remember { mutableStateMapOf<String, PartitionLoadState>() }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        error = null
        try {
            // 库名取 DATABASE()；查不到（权限 / 纯 MySQL）不影响表列表，回落占位。
            dbName = runCatching { queryRows(connectionId, "SELECT DATABASE();") }
                .getOrNull()
                ?.rows
                ?.firstOrNull()
                ?.firstOrNull()
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            val all = queryRows(connectionId, TABLES_SQL).rows.mapNotNull { row ->
                val name = row.getOrNull(0)?.toString().orEmpty()
                name.takeIf { it.isNotBlank() }?.let {
                    CatalogTable(it, row.getOrNull(1)?.toString().orEmpty())
                }
            }
            // 物化视图名单：查询失败 / 返回空（MySQL）都当「无物化视图」，静默降级。
            val mvNames = runCatching { queryRows(connectionId, MATERIALIZED_VIEW_SQL) }
                .getOrNull()
                ?.rows
                ?.mapNotNull { it.getOrNull(0)?.toString()?.takeIf { n -> n.isNotBlank() } }
                ?.toSet()
                ?: emptySet()
            tables = all.map { it.copy(materializedView = it.name in mvNames) }
            columnCache.clear()
            partitionCache.clear()
            expandedTable = null
        } catch (e: Throwable) {
            error = e.userMessage()
            tables = emptyList()
        } finally {
            loading = false
        }
    }

    /** 单击展开：字段与分区各自独立加载，任一失败都不影响另一侧、不染红整棵树。 */
    fun toggleTable(table: CatalogTable) {
        if (expandedTable == table.name) {
            expandedTable = null
            return
        }
        expandedTable = table.name

        if (!columnCache.containsKey(table.name)) {
            columnCache[table.name] = null
            scope.launch {
                val columns = try {
                    queryRows(connectionId, columnsSql(table.name)).rows.mapNotNull { row ->
                        val column = row.getOrNull(0)?.toString().orEmpty()
                        column.takeIf { it.isNotBlank() }?.let {
                            CatalogColumn(it, row.getOrNull(1)?.toString().orEmpty())
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // 局部降级：不写全局 error（表结构查不到只影响这一格）。
                    emptyList()
                }
                columnCache[table.name] = columns
            }
        }

        if (!partitionCache.containsKey(table.name)) {
            partitionCache[table.name] = PartitionLoadState.Loading
            scope.launch {
                val state = try {
                    PartitionLoadState.Loaded(parsePartitions(queryRows(connectionId, partitionsSql(table.name))))
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // MySQL / 无分区权限 / 语法不支持：静默，显示中性提示。
                    PartitionLoadState.Unsupported
                }
                partitionCache[table.name] = state
            }
        }
    }

    LaunchedEffect(connectionId) { load() }

    val normalTables = tables.filter { !it.materializedView }
    val materializedViews = tables.filter { it.materializedView }

    Column(modifier.fillMaxSize()) {
        // ── 工具栏：标题 + 刷新 ──
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

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource("olap.starrocks.catalog.loading"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.catalog.error_prefix") + error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            tables.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.catalog.empty"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                // 第一层：库名（可折叠）
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) { detectTapGestures(onTap = { dbExpanded = !dbExpanded }) }
                            .padding(vertical = 8.dp),
                    ) {
                        Icon(
                            if (dbExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
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
                        count = normalTables.size,
                        expanded = tablesFolderExpanded,
                        onToggle = { tablesFolderExpanded = !tablesFolderExpanded },
                    )
                    if (tablesFolderExpanded) {
                        tableItems(
                            entries = normalTables,
                            keyPrefix = "t",
                            expandedTable = expandedTable,
                            columnCache = columnCache,
                            partitionCache = partitionCache,
                            onToggle = ::toggleTable,
                            onOpenConsole = { table ->
                                openConsoleWithSql(connectionId, previewSql(table.name))
                            },
                        )
                    }

                    // 第二层 B：物化视图文件夹（Layers 图标区分）
                    folderItem(
                        label = stringResource("olap.starrocks.catalog.mv_folder"),
                        count = materializedViews.size,
                        expanded = mvFolderExpanded,
                        onToggle = { mvFolderExpanded = !mvFolderExpanded },
                        materializedView = true,
                    )
                    if (mvFolderExpanded) {
                        if (materializedViews.isEmpty()) {
                            item {
                                HintText(
                                    stringResource("olap.starrocks.catalog.mv_empty"),
                                    startIndent = 48.dp,
                                )
                            }
                        } else {
                            tableItems(
                                entries = materializedViews,
                                keyPrefix = "mv",
                                expandedTable = expandedTable,
                                columnCache = columnCache,
                                partitionCache = partitionCache,
                                onToggle = ::toggleTable,
                                onOpenConsole = { table ->
                                    openConsoleWithSql(connectionId, previewSql(table.name))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 文件夹行（普通表 / 物化视图），带展开箭头与条目数。 */
private fun LazyListScope.folderItem(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    materializedView: Boolean = false,
) {
    item {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(label) { detectTapGestures(onTap = { onToggle() }) }
                .padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            if (materializedView) {
                Icon(
                    Icons.Filled.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
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

/** 表节点列表：单击展开（字段 + 分区）/ 双击打开 SQL 控制台。 */
private fun LazyListScope.tableItems(
    entries: List<CatalogTable>,
    keyPrefix: String,
    expandedTable: String?,
    columnCache: Map<String, List<CatalogColumn>?>,
    partitionCache: Map<String, PartitionLoadState>,
    onToggle: (CatalogTable) -> Unit,
    onOpenConsole: (CatalogTable) -> Unit,
) {
    items(entries, key = { "$keyPrefix:${it.name}" }) { table ->
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(table.name) {
                        detectTapGestures(
                            onTap = { onToggle(table) },
                            onDoubleTap = { onOpenConsole(table) },
                        )
                    }
                    .padding(start = 44.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Icon(
                    if (expandedTable == table.name) Icons.Filled.KeyboardArrowDown
                    else Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                if (table.materializedView) {
                    Icon(
                        Icons.Filled.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
                Column(Modifier.padding(start = 4.dp).weight(1f)) {
                    Text(table.name, style = MaterialTheme.typography.bodyMedium)
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

            if (expandedTable == table.name) {
                // 字段小节
                HintText(
                    stringResource("olap.starrocks.catalog.columns_label"),
                    startIndent = 72.dp,
                    bold = true,
                )
                when {
                    columnCache[table.name] == null ->
                        HintText(stringResource("olap.starrocks.catalog.loading"), 72.dp)
                    columnCache[table.name].isNullOrEmpty() ->
                        HintText(stringResource("olap.starrocks.catalog.columns_empty"), 72.dp)
                    else -> columnCache[table.name].orEmpty().forEach { column ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 72.dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
                        ) {
                            Text(
                                column.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                column.type,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // 分区小节（失败静默降级，不弹红字）
                HintText(
                    stringResource("olap.starrocks.catalog.partitions_label"),
                    startIndent = 72.dp,
                    bold = true,
                )
                when (val state = partitionCache[table.name]) {
                    null, PartitionLoadState.Loading ->
                        HintText(stringResource("olap.starrocks.catalog.loading"), 72.dp)
                    PartitionLoadState.Unsupported ->
                        HintText(stringResource("olap.starrocks.catalog.partitions_unsupported"), 72.dp)
                    is PartitionLoadState.Loaded ->
                        if (state.partitions.isEmpty()) {
                            HintText(stringResource("olap.starrocks.catalog.partitions_empty"), 72.dp)
                        } else {
                            state.partitions.forEach { partition ->
                                Text(
                                    partition.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(
                                        start = 72.dp, top = 2.dp, bottom = 2.dp, end = 8.dp,
                                    ),
                                )
                            }
                        }
                }
            }
        }
    }
}

/** 树里的中性提示行（非错误、非加载动画）。 */
@Composable
private fun HintText(
    text: String,
    startIndent: androidx.compose.ui.unit.Dp,
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
