package com.mqclient.message.interpreter.base64

import com.mqclient.message.interpreter.DecodingInterpreter
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import java.util.Base64
import java.util.ServiceLoader

/**
 * Base64 解释器 —— **解码层**：先 Base64 解码字节，再交给内容解释器继续处理。
 *
 * 链式调用设计（模块互不依赖的前提下的解法）：
 *  - 本模块**只依赖 api**，不编译期引用 JSON / XML / 任何内容解释器；
 *  - 解码后经 [ServiceLoader] 运行时发现全部已注册的 [MessageInterpreter]
 *    （宿主 classpath 上有什么就有什么），按注册表的排序顺序用 canInterpret
 *    探测解码后的字节，第一个命中的接手渲染；全部不命中按 UTF-8 文本展示；
 *  - 探测时跳过一切 [DecodingInterpreter]（含自身）—— 防止 Base64 → Base64
 *    的递归；
 *  - 命中的内容解释器的输出加 `Base64 → ` 前缀，向用户交代两段式处理。
 *
 * ## 认领与解码是两件事
 *
 * 旧实现用 `%4 == 0` + 字符白名单认领，再用 `Base64.getMimeDecoder()` 解码。
 * 两个坑叠在一起：
 *  1. MIME 解码器**会静默跳过非法字符**（`hello, world!` → 得到一段垃圾字节），
 *     于是「看起来不认识的内容」被成功解码成更不认识的内容；
 *  2. Base64 字母表是 ASCII 子集的真子集，任何 4 的倍数的纯字母数字串（如
 *     `ABCD1234`、`ORDERS05`）在数学上都是合法 Base64 —— 白名单认领等于
 *     见谁都举手。
 *
 * 现在分三条规则：
 *  - **严格解码**（[decodeStrict]，`interpret` 用）：ASCII 字母表 + 长度 4 的倍数 +
 *    合法填充位置，用 `Base64.getDecoder()`（非 MIME）—— 不合法就 `Failure`，
 *    绝不产出垃圾字节；
 *  - **自动认领**（[canInterpret]）：严格合法之外，还要求「不像普通词」——
 *    出现 `=` / `+` / `/`，或长度 ≥ [LONG_LENGTH]。短纯字母数字串不自动认领
 *    （用户仍可在解释器下拉里手动选 Base64）；
 *  - **手动选中**时 `interpret` 只认严格合法性，与认领启发式无关 —— 想看就给你看，
 *    想解就按规矩解。
 */
class Base64Interpreter : DecodingInterpreter {

    override val id = "base64"
    override val displayName = "msg_interp_base64"
    override val sortOrder = 40

    /**
     * 自动认领的最小长度：短于它的一律不自动认领（`TWFu`、`QQ==` 这类虽然合法，
     * 但在消息体里更可能是普通短串）。
     */
    private val autoClaimMinLength = 8

    /**
     * 没有填充 / `+` / `/` 时要求的长度：足够长才有「这是编码数据」的说服力。
     * 低于它的纯字母数字串（`ORDERS05`、`ABCD1234`）不自动认领。
     */
    private val autoClaimPureLength = 16

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val text = decodeAsUtf8(content).trim()
        if (text.length < autoClaimMinLength) return false
        if (decodeStrict(text) == null) return false
        val compact = text.filterNot(Char::isWhitespace)
        val distinctive = compact.any { it == '=' || it == '+' || it == '/' }
        return distinctive || compact.length >= autoClaimPureLength
    }

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult {
        val text = decodeAsUtf8(content).trim()
        val decoded = decodeStrict(text)
            ?: return InterpreterResult.Failure(
                "Base64 decode failed: 内容不是合法的 Base64（长度需为 4 的倍数、仅含 " +
                    "A-Z a-z 0-9 + / 与末尾填充 =）",
            )
        // 解码后的字节交给内容解释器（跳过解码层防递归）；全部不命中按文本展示。
        val chained = ServiceLoader.load(MessageInterpreter::class.java)
            .firstOrNull { it !is DecodingInterpreter && it.canInterpret(decoded, encoding, 1208) }
        return if (chained != null) {
            when (val inner = chained.interpret(decoded, encoding, 1208, chained.defaultParams)) {
                is InterpreterResult.Success -> InterpreterResult.Success("Base64 → ${inner.formatted}")
                is InterpreterResult.Failure -> InterpreterResult.Failure(inner.message)
            }
        } else {
            InterpreterResult.Success(MessageCharsets.decode(decoded, 1208))
        }
    }

    /** 探测用 UTF-8 解码（Base64 字母表是 ASCII 子集，字符集不影响结论）。 */
    private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)

    /**
     * 严格 Base64 解码：任何不合规都返回 null（调用方转 Failure），不猜、不修补。
     *
     * 规则（RFC 4648 §4，允许换行 / 空白的 MIME 折行形态）：
     *  - 去掉空白后的长度必须是 4 的倍数且非空；
     *  - 只允许 `A-Z a-z 0-9 + / =`；
     *  - `=` 只能出现在末尾，且最多两个、且出现后不能再用非 `=` 字符。
     *
     * 用 `getDecoder()`（严格）而不是 `getMimeDecoder()`（宽松）：后者会把非法
     * 字符直接丢弃，正是“把噪声解码成更噪声”的来源。
     */
    private fun decodeStrict(text: String): ByteArray? {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || compact.length % 4 != 0) return null
        if (compact.any { !isBase64Alphabet(it) }) return null

        val firstPad = compact.indexOf('=')
        if (firstPad >= 0) {
            val padding = compact.length - firstPad
            // 末尾最多两个填充，且填充必须连续。
            if (padding > 2 || compact.substring(firstPad).any { it != '=' }) return null
        }
        return runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
    }

    private fun isBase64Alphabet(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '+' || ch == '/' || ch == '='
}
