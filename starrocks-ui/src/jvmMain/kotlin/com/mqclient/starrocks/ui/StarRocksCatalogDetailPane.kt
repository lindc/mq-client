package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.starrocks.catalog.CatalogColumn
import com.mqclient.starrocks.catalog.CatalogPartition
import com.mqclient.starrocks.catalog.CatalogTable
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridTextCell

/**
 * 右侧详情面板：按选中节点动态渲染。
 * - 库：表清单概览（表名 / 类型 / 注释，行可点进入表详情）；
 * - 表：概览（字段二维表格 + 分区折叠小节）+ DDL（SHOW CREATE TABLE 文本 + 复制）双 Tab。
 */
@Composable
internal fun CatalogDetailPane(
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
                else -> DdlPane(ddl)
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
    sortKey: ((CatalogColumn) -> Any?)? = null,
    cell: @Composable (CatalogColumn) -> Unit,
): DataGridColumn<CatalogColumn> = DataGridColumn(title = title, weight = weight, sortKey = sortKey, cell = cell)

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
            gridColumn(stringResource("olap.starrocks.catalog.col_name"), 2f, sortKey = { it.name }) { column ->
                DataGridTextCell(column.name, emphasized = true)
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_type"), 1.5f, sortKey = { it.type }) { column ->
                DataGridTextCell(column.type)
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_nullable"), 1f, sortKey = { it.nullable }) { column ->
                DataGridTextCell(column.nullable.ifBlank { "-" }, subdued = true)
            },
            gridColumn(stringResource("olap.starrocks.catalog.col_key"), 1f, sortKey = { it.key }) { column ->
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
private fun DdlPane(ddl: String?) {
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
