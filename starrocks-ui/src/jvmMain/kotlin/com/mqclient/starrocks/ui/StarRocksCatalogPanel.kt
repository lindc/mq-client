package com.mqclient.starrocks.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/** 一张表（名称 + 注释）。 */
private data class CatalogTable(val name: String, val comment: String)

/** 一个字段（名称 + 类型）。 */
private data class CatalogColumn(val name: String, val type: String)

private const val TABLES_SQL =
    "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = DATABASE();"

/** 表名进 SQL 字符串字面量：单引号转义，防元数据里带引号的名称破坏语句。 */
internal fun columnsSql(table: String): String =
    "SELECT column_name, data_type FROM information_schema.columns " +
        "WHERE table_schema = DATABASE() AND table_name = '${table.replace("'", "''")}';"

/** 双击表名生成的查询 SQL：标识符用反引号包裹（内部反引号双写）。 */
internal fun previewSql(table: String): String =
    "SELECT * FROM `${table.replace("`", "``")}` LIMIT 100;"

/**
 * 「数据目录」只读面板：库 → 表 → 字段的三层树。
 *
 * - 元数据全部走 [queryRows]（复用 `executeQuery` 流式通道）；
 * - 单击表展开 / 收起并**懒加载**字段，双击表 [openConsoleWithSql] 打开 SQL
 *   控制台并填入 `SELECT * FROM 表名 LIMIT 100;`；
 * - 空状态 / 加载中 / 错误态齐全，所有文案走 i18n。
 */
@Composable
internal fun StarRocksCatalogPanel(connectionId: String, modifier: Modifier = Modifier) {
    var dbName by remember { mutableStateOf<String?>(null) }
    var tables by remember { mutableStateOf<List<CatalogTable>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dbExpanded by remember { mutableStateOf(true) }
    var expandedTable by remember { mutableStateOf<String?>(null) }
    // null = 该表字段正在加载；空列表 = 加载完成但确实无字段。
    val columnCache = remember { mutableStateMapOf<String, List<CatalogColumn>?>() }
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
            val result = queryRows(connectionId, TABLES_SQL)
            tables = result.rows.mapNotNull { row ->
                val name = row.getOrNull(0)?.toString().orEmpty()
                name.takeIf { it.isNotBlank() }?.let {
                    CatalogTable(it, row.getOrNull(1)?.toString().orEmpty())
                }
            }
            columnCache.clear()
            expandedTable = null
        } catch (e: Throwable) {
            error = e.userMessage()
            tables = emptyList()
        } finally {
            loading = false
        }
    }

    fun toggleTable(name: String) {
        if (expandedTable == name) {
            expandedTable = null
            return
        }
        expandedTable = name
        if (columnCache.containsKey(name)) return
        columnCache[name] = null // 加载中（与「无字段」区分）
        scope.launch {
            val columns = try {
                val result = queryRows(connectionId, columnsSql(name))
                result.rows.mapNotNull { row ->
                    val column = row.getOrNull(0)?.toString().orEmpty()
                    column.takeIf { it.isNotBlank() }?.let {
                        CatalogColumn(it, row.getOrNull(1)?.toString().orEmpty())
                    }
                }
            } catch (e: Throwable) {
                error = e.userMessage()
                emptyList()
            }
            columnCache[name] = columns
        }
    }

    LaunchedEffect(connectionId) { load() }

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
                    items(tables, key = { it.name }) { table ->
                        Column {
                            // 第二层：表（单击展开字段 / 双击打开 SQL 控制台）
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(table.name) {
                                        detectTapGestures(
                                            onTap = { toggleTable(table.name) },
                                            onDoubleTap = {
                                                openConsoleWithSql(connectionId, previewSql(table.name))
                                            },
                                        )
                                    }
                                    .padding(start = 24.dp, top = 6.dp, bottom = 6.dp),
                            ) {
                                Icon(
                                    if (expandedTable == table.name) Icons.Filled.KeyboardArrowDown
                                    else Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
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

                            // 第三层：字段（名称 + 类型），懒加载后缓存
                            if (expandedTable == table.name) {
                                val columns = columnCache[table.name]
                                when {
                                    columns == null -> Text(
                                        stringResource("olap.starrocks.catalog.loading"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 52.dp, bottom = 6.dp),
                                    )
                                    columns.isEmpty() -> Text(
                                        stringResource("olap.starrocks.catalog.columns_empty"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 52.dp, bottom = 6.dp),
                                    )
                                    else -> columns.forEach { column ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 52.dp, top = 2.dp, bottom = 2.dp),
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
                            }
                        }
                    }
                }
            }
        }
    }
}
