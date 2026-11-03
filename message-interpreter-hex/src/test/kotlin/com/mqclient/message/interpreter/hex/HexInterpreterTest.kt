package com.mqclient.message.interpreter.hex

import com.mqclient.message.interpreter.InterpreterResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hex 解释器：任意字节流都能渲染，且格式固定（偏移 · 16 字节 · ASCII 列）。
 *
 * 这条路径是**二进制内容的唯一兜底**（`MessageDetailDialog` 判定为二进制时默认选它），
 * 所以边界行为要钉住：空输入不崩、不可见字节显示占位符、超出上限以省略号收尾。
 */
class HexInterpreterTest {

    private val interpreter = HexInterpreter()

    private fun render(bytes: ByteArray): String {
        val result = interpreter.interpret(bytes, 0, 1208, emptyMap())
        assertTrue(result is InterpreterResult.Success, "actual: $result")
        return (result as InterpreterResult.Success).formatted
    }

    @Test
    fun `always claims any input`() {
        // 与其它解释器不同：Hex 是兜底，canInterpret 恒 true（包括空内容）。
        assertTrue(interpreter.canInterpret(ByteArray(0), 0, 1208))
        assertTrue(interpreter.canInterpret("hello".toByteArray(), 0, 1208))
        assertTrue(interpreter.canInterpret(byteArrayOf(0, 0, 0), 0, 1208))
    }

    @Test
    fun `renders offset hex and ascii columns`() {
        val out = render("AB".toByteArray())

        // 格式：偏移 · 每字节两位 hex（空格分隔）· 补足 16 列的空白 · |ASCII|
        assertTrue(out.startsWith("00000000  41 42 "), "actual: $out")
        assertTrue(out.trimEnd().endsWith("|AB|"), "actual: $out")
        assertEquals(1, out.trimEnd().lines().size)
    }

    @Test
    fun `control bytes render as a placeholder dot`() {
        val out = render(byteArrayOf(0x00, 0x01, 0x7F, 0x41))

        // ASCII 列里 0x41 是 A，其余不可见字节一律 ·（保证终端里不会出现控制字符）。
        assertTrue(out.contains("|···A|"), "actual: $out")
        assertEquals(false, out.contains('\u0000'))
    }

    @Test
    fun `row boundary at sixteen bytes`() {
        val out = render(ByteArray(16) { it.toByte() })
        val lines = out.trimEnd().lines()

        assertEquals(1, lines.size, "16 字节正好一行：$out")
        assertTrue(lines[0].startsWith("00000000"))
    }

    @Test
    fun `long input is truncated with an ellipsis`() {
        val out = render(ByteArray(2000) { 0x41 })

        // 默认上限是 1KB（hexPreview 的 maxBytes），超出以 … 标记收尾。
        assertTrue(out.endsWith("…"), "actual tail: ${out.takeLast(20)}")
        assertTrue(out.lines().size < 2000 / 16 + 5)
    }

    @Test
    fun `empty input renders empty`() {
        assertEquals("", render(ByteArray(0)))
    }
}
