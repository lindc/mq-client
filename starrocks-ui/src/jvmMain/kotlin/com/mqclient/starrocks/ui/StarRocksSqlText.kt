package com.mqclient.starrocks.ui

import androidx.compose.material3.Tab
import androidx.compose.ui.text.input.TextFieldValue
import com.mqclient.olap.base.query.ResultColumn

/**
 * 结果 → TSV 文本（表头 + 行，Tab 分隔，NULL 原样）。纯函数便于测试；
 * 大结果集的拼接由调用方放到 IO 线程。
 */
internal fun buildTsv(columns: List<ResultColumn>, rows: List<List<Any?>>): String = buildString {
    appendLine(columns.joinToString("\t") { it.name })
    rows.forEach { row -> appendLine(row.joinToString("\t") { it?.toString() ?: "NULL" }) }
}

/**
 * 「执行选中」的取源逻辑（与 mq-ui `CommandConsoleViewModel.execute` 同构）：
 * selection 在反向选择（从后往前拖）时 start > end，必须用 min/max 归一，
 * 否则 substring 会抛 StringIndexOutOfBoundsException。
 * 返回 null = 没有选中文本（调用方回落全文）。
 */
internal fun selectedSource(textFieldValue: TextFieldValue): String? {
    val selection = textFieldValue.selection
    // 无选区（光标只是一个闪烁竖线）→ 返回 null，由调用方执行全文。
    if (selection.start == selection.end) return null
    // 反向选择（从后往前拖）时 start > end，用 min/max 归一防 substring 越界；
    // 除此之外**原样截取**，不改写任何选区状态。
    return textFieldValue.text.substring(selection.min, selection.max)
}

/**
 * 分号切分多语句（已知限制：不处理字符串字面量内的分号 —— SQL 字面量里写分号的
 * 场景在本工具的目标查询里极少）。剔除 `--` 行注释与空段，末尾多余分号
 * 自然产生空段被过滤。
 */
internal fun splitStatements(text: String): List<String> =
    text.split(';')
        .map { segment ->
            segment.lines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .trim()
        }
        .filter { it.isNotEmpty() }
