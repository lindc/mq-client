package com.mqclient.starrocks.ui

import com.mqclient.core.log.LogLevel
import com.mqclient.olap.base.query.ResultBatch
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

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
 * ## 为什么先写临时文件再替换
 *
 * 直接 `FileOutputStream(target)` 会在**打开时立刻截断**目标文件（实测：31 字节的
 * 原文件在打开后变成 0，写了一半失败就停在半截）。所以失败的导出会：
 *  1. 毁掉用户原有的同名文件；
 *  2. 在磁盘上留下一个「看起来正常、其实是前半截」的 CSV —— 用户很可能拿它去
 *     做后续分析，以为数据完整。
 *
 * 改为写同目录的 `<name>.part`，全部写完后 `Files.move(..., ATOMIC_MOVE)` 覆盖目标：
 * 成功时目标要么是旧文件、要么是完整新文件，永远不会是半截；失败 / 取消时临时文件
 * 被删掉，目标文件一个字节都没动过。
 *
 * @return 实际写入的数据行数（不含表头）。
 */
internal suspend fun streamCsvExport(
    connectionId: String,
    sql: String,
    path: String,
    onProgress: (Long) -> Unit,
): Long = withContext(Dispatchers.IO) {
    writeCsvAtomically(
        target = File(path),
        batches = {
            // 流式导出需要逐批消费，不能走「一次性收集」的 queryRows；但连接守卫必须
            // 同样走 ensureSession（否则树不会变绿，且会话属于别的连接时会静默连错库）。
            com.mqclient.starrocks.query.ensureSession(connectionId).getOrThrow()
            StarRocksModule.executeQuery(connectionId, sql)
        },
        onProgress = onProgress,
    )
}

/**
 * 「先写临时文件、成功再原子替换」的完整导出：**失败时目标路径一个字节都不动**。
 *
 * 这个函数就是原子性的唯一实现处，`StarRocksCsvExportAtomicTest` 直接测它 ——
 * 把不变式绑在一个可测函数上，而不是散在调用链里靠人记得。
 */
internal suspend fun writeCsvAtomically(
    target: File,
    batches: suspend () -> Flow<ResultBatch>,
    onProgress: (Long) -> Unit,
): Long {
    // 临时文件必须与目标同目录：跨文件系统的 move 不是原子的。
    val temp = File(target.absoluteFile.parentFile, "${target.name}.part")
    try {
        val count = writeCsvBatches(file = temp, batches = batches, onProgress = onProgress)
        replaceAtomically(temp, target)
        return count
    } finally {
        // 成功路径上 temp 已被 move 掉（exists() 为 false），这里只清理失败 / 取消的残留。
        if (temp.exists()) temp.delete()
    }
}

/**
 * 把批次流写进 [file]（不负责命名与替换）。抽出来是为了让「失败不留半截」这条
 * 不变式能在没有数据库的情况下单测。
 */
internal suspend fun writeCsvBatches(
    file: File,
    batches: suspend () -> Flow<ResultBatch>,
    onProgress: (Long) -> Unit,
): Long {
    var headerWritten = false
    var count = 0L
    // UTF-8 显式编码（FileWriter 是平台默认 —— 中文 Windows 下 GBK，数据跨工具必乱码）；
    // BOM 让 Excel 双击打开时正确识别 UTF-8。
    BufferedWriter(
        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8),
    ).use { writer ->
        writer.write("\ufeff")
        batches().collect { batch ->
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
    return count
}

/**
 * 用 [temp] 原子替换 [target]。
 *
 * `ATOMIC_MOVE` 在个别文件系统（部分网络盘 / FAT）上会抛
 * `AtomicMoveNotSupportedException` —— 那种情况下退化成普通覆盖移动，
 * 仍是「先写好再换」，只是小概率可见一个非原子窗口。
 */
private fun replaceAtomically(temp: File, target: File) {
    val targetPath = target.toPath()
    val tempPath = temp.toPath()
    try {
        java.nio.file.Files.move(
            tempPath,
            targetPath,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (atomicUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
        StarRocksLog.meta(
            LogLevel.DEBUG,
            "csv export: atomic move unsupported, falling back to plain replace: ${atomicUnsupported.message}",
        )
        java.nio.file.Files.move(tempPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
