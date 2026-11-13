package com.mqclient.starrocks.ui

import com.mqclient.starrocks.StarRocksModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * CSV 字段转义：含逗号 / 引号 / 换行 / 回车的值用双引号包裹，内部引号双写
 * （RFC 4180）；null 一律转空字符串。
 */
internal fun csvField(value: String?): String {
    val v = value ?: ""
    return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + v.replace("\"", "\"\"") + "\""
    } else {
        v
    }
}

/** 一行数据 → CSV 行（null → 空字符串，逐字段转义）。 */
internal fun csvLine(cells: List<Any?>): String =
    cells.joinToString(",") { csvField(it?.toString()) }

/**
 * AWT `FileDialog`（SAVE）弹出「另存为」：默认文件名 `export_<时间戳>.csv`。
 *
 * 为什么不用项目里的 FileKit `saveFileBytes`：它要求一次性 `ByteArray` contents，
 * 10 万行的结果全量进内存正是要避免的；AWT FileDialog 是**模态阻塞**调用，
 * 同步拿回路径，正好配合「先选路径、再流式写入」的时序。
 *
 * 必须在 **EDT** 上调用（`Dispatchers.Main`，Compose Desktop 的 Main 即 Swing
 * EDT）—— 阻塞期间事件循环照常泵，UI 不冻结；用户取消返回 null。
 */
internal fun pickCsvSavePath(): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save CSV", java.awt.FileDialog.SAVE)
    dialog.file = "export_${System.currentTimeMillis()}.csv"
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val dir = dialog.directory ?: ""
    // 少数平台不强制扩展名：保存后没有 .csv 就补上。
    return if (file.contains('.')) dir + file else dir + file + ".csv"
}

/**
 * 流式导出：**重新执行**当前 SQL（复用 `executeQuery` 的 `Flow<ResultBatch>` 通道，
 * 不把 UI 里的快照列表二次拼接），每收一个批次立即写入并 flush —— 内存占用恒定
 * 为一个批次，10 万行不 OOM、不卡死。
 *
 * 整个写入在 `Dispatchers.IO`；`use{}` 保证 [BufferedWriter] 在异常 / 取消时关闭。
 * 进度经 [onProgress] 回调（IO 线程；SnapshotState 写线程安全，主线程重组）。
 *
 * @return 实际写入的数据行数（不含表头）。
 */
internal suspend fun streamCsvExport(
    connectionId: String,
    sql: String,
    path: String,
    onProgress: (Long) -> Unit,
): Long = withContext(Dispatchers.IO) {
    var headerWritten = false
    var count = 0L
    BufferedWriter(FileWriter(File(path))).use { writer ->
        StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
            if (!headerWritten) {
                // 列名来自 ResultBatch.columns（每批自带全量 schema），首个批次写表头。
                writer.write(batch.columns.joinToString(",") { csvField(it.name) })
                writer.newLine()
                headerWritten = true
            }
            batch.rows.forEach { row ->
                writer.write(csvLine(row))
                writer.newLine()
                count++
            }
            writer.flush()
            onProgress(count)
        }
    }
    count
}
