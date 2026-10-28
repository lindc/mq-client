package com.mqclient.mqui.file

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 消息体文件的大小守卫（[readMessageBytes]）。
 *
 * 这几个用例守的是同一条性质：**超限的文件一个字节都不该读进内存**。
 * 旧实现是 `file.readBytes()` 之后再判尺寸 —— 误选一个几十 GB 的备份文件时，
 * 守卫要等整份文件已经进堆才抛得出来，等于没有守卫。
 */
class MessageFileBytesTest {

    private val temporary = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        temporary.forEach { it.delete() }
    }

    private fun sparseFile(bytes: Long): File {
        val file = Files.createTempFile("mqclient-msg-", ".bin").toFile()
        temporary += file
        // setLength 只改元数据（稀疏文件），不会真的写满磁盘 —— 这里就是要造一个
        // 「长度远超上限、但内容并不占空间」的文件来验证守卫读的是 length()。
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return file
    }

    @Test
    fun `file over the limit is rejected by length without being read`() {
        val file = sparseFile(MAX_MESSAGE_FILE_BYTES + 1)

        val error = assertFailsWith<MessageFileTooLargeException> { file.readMessageBytes() }

        assertEquals(MAX_MESSAGE_FILE_BYTES + 1, error.sizeBytes, "应报告真实文件长度")
        assertEquals(MAX_MESSAGE_FILE_BYTES, error.maxBytes)
        assertEquals(file.name, error.fileName)
    }

    @Test
    fun `file exactly at the limit is accepted`() {
        // 边界必须放行：上限是「不得超过」，不是「必须小于」。
        val file = sparseFile(MAX_MESSAGE_FILE_BYTES)

        val bytes = file.readMessageBytes()

        assertEquals(MAX_MESSAGE_FILE_BYTES, bytes.size.toLong())
    }

    @Test
    fun `small file round-trips its bytes`() {
        val file = Files.createTempFile("mqclient-msg-", ".txt").toFile()
        temporary += file
        file.writeBytes("hello".toByteArray())

        assertEquals("hello", String(file.readMessageBytes(), Charsets.UTF_8))
    }

    @Test
    fun `custom limit is honoured`() {
        val file = Files.createTempFile("mqclient-msg-", ".txt").toFile()
        temporary += file
        file.writeBytes(ByteArray(11))

        assertFailsWith<MessageFileTooLargeException> { file.readMessageBytes(maxBytes = 10) }
        assertEquals(11, file.readMessageBytes(maxBytes = 11).size)
    }

    @Test
    fun `missing file fails as unreadable rather than too large`() {
        val missing = File(temporaryDirectory(), "does-not-exist.bin")

        val error = assertFailsWith<java.io.IOException> { missing.readMessageBytes() }

        assertFalse(
            MessageFileFailure.of(missing.name, error).tooLarge,
            "文件不存在属于「读不出来」，不该被报成「太大」",
        )
    }

    @Test
    fun `failure classification distinguishes too large from unreadable`() {
        val tooLarge = MessageFileFailure.of("dump.bin", MessageFileTooLargeException("dump.bin", 200, 100))
        val unreadable = MessageFileFailure.of("dump.bin", java.io.FileNotFoundException("nope"))

        assertTrue(tooLarge.tooLarge)
        assertFalse(unreadable.tooLarge)
        assertEquals("dump.bin", tooLarge.fileName)
    }

    @Test
    fun `limit constants stay ordered`() {
        // 命令脚本上限（10MB）应显著小于消息体上限（100MB）—— 两者是不同用途的
        // 两道闸，混成一个常量会让改一方时误伤另一方（见 KDoc）。
        assertTrue(MAX_COMMAND_FILE_BYTES < MAX_MESSAGE_FILE_BYTES)
        assertEquals(MAX_MESSAGE_FILE_BYTES, MAX_MESSAGE_FILE_MB * 1024 * 1024)
        assertEquals(MAX_COMMAND_FILE_BYTES, MAX_COMMAND_FILE_KB * 1024)
    }

    private fun temporaryDirectory(): String = System.getProperty("java.io.tmpdir")
}
