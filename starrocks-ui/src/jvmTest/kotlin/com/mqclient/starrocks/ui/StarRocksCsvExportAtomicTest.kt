package com.mqclient.starrocks.ui

import com.mqclient.olap.base.query.ResultBatch
import com.mqclient.olap.base.query.ResultColumn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CSV 导出的**原子性**守卫：失败的导出不能在磁盘上留下任何东西。
 *
 * 为什么值得单独测：`FileOutputStream(target)` 在**打开时**就截断目标文件
 * （实测 31 字节 → 0）。所以旧实现的失败路径有两个真实后果：
 *  1. 用户原有的同名文件被清空；
 *  2. 留下半截 CSV，看起来像一次正常的短导出 —— 用户会拿它当完整数据用。
 *
 * 这两点都不是异常，无法靠「看异常信息」发现，只能靠断言文件系统的最终状态。
 */
class StarRocksCsvExportAtomicTest {

    private val dir: File = createTempDirectory("csv-export").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun batch(rows: List<List<Any?>>, last: Boolean = false) = ResultBatch(
        columns = listOf(ResultColumn("id", ""), ResultColumn("name", "")),
        rows = rows,
        lastBatch = last,
        batchIndex = 0,
    )

    @Test
    fun `successful export produces a complete file and no temp leftover`() = runBlocking {
        val target = File(dir, "out.csv")

        val count = writeCsvAtomically(
            target = target,
            batches = {
                flowOf(
                    batch(listOf(listOf(1L, "a"), listOf(2L, "b"))),
                    batch(listOf(listOf(3L, "c")), last = true),
                )
            },
            onProgress = {},
        )

        assertEquals(3, count)
        val text = target.readText()
        assertTrue(text.contains("id,name"), "表头缺失：$text")
        assertTrue(text.contains("1,a") && text.contains("3,c"), "数据不完整：$text")
        assertFalse(File(dir, "out.csv.part").exists(), "成功后不应留下 .part 文件")
    }

    @Test
    fun `failing export leaves no partial file at the target path`() = runBlocking {
        val target = File(dir, "out.csv")

        val error = runCatching {
            // 第二批抛错 —— 第一批已经写进文件了。
            writeCsvAtomically(
                target = target,
                batches = {
                    flow {
                        emit(batch(listOf(listOf(1L, "a"))))
                        throw IllegalStateException("connection lost")
                    }
                },
                onProgress = {},
            )
        }.exceptionOrNull()

        assertEquals("connection lost", error?.message)
        // 关键是这个断言：目标路径上不能有任何东西。
        assertFalse(target.exists(), "失败的导出不应在目标路径留下文件")
    }

    /**
     * 这条覆盖的是旧实现最糟的后果：**用户原有的文件被毁掉**。
     *
     * 断言的是最终状态：失败后目标文件与导出前**逐字节相同**。
     */
    @Test
    fun `a pre-existing target file is untouched when the source fails`() = runBlocking {
        val target = File(dir, "out.csv")
        target.writeText("重要数据，不能被导出失败毁掉\n")

        runCatching {
            writeCsvAtomically(
                target = target,
                batches = { flow<ResultBatch> { throw IllegalStateException("boom") } },
                onProgress = {},
            )
        }

        assertEquals(
            "重要数据，不能被导出失败毁掉\n",
            target.readText(),
            "目标文件被改动了 —— 导出失败不应影响已有文件",
        )
    }

    @Test
    fun `empty result still writes a header`() = runBlocking {
        val target = File(dir, "out.csv")

        val count = writeCsvAtomically(
            target = target,
            batches = { flowOf(batch(emptyList(), last = true)) },
            onProgress = {},
        )

        assertEquals(0, count)
        assertTrue(target.readText().contains("id,name"), "空结果也应写出表头")
    }
}
