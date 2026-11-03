package com.mqclient.message.interpreter.hex

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.hexPreview

/**
 * 「Hex 预览」解释器：任意字节流按 hex dump（偏移 + hex×16 + ASCII 列）渲染，
 * 只取前 [hexPreview] 默认上限的字节。由 RabbitMQ 详情框的「Hex 预览」伪解释器
 * 选项转正而来 —— 二进制内容的兜底展示，用户至少能看到字节层面的事实（魔数、
 * 结构）。
 *
 * 对所有输入都可用（[canInterpret] 恒 true）；[sortOrder] 排在全部内置解释器
 * 之后（列表末位）。无参数。
 */
class HexInterpreter : MessageInterpreter {

    override val id: String = "hex"

    override val displayName: String = "msg_interp_hex"

    /** 内置解释器用 10/20/30… 保持稳定顺序，Hex 恒居末位。 */
    override val sortOrder: Int = 900

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean = true

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult = InterpreterResult.Success(hexPreview(content))
}
