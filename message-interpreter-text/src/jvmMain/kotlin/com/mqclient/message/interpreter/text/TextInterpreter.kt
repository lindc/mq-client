package com.mqclient.message.interpreter.text

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef

/** 文本：按 MQMD CodedCharSetId 解码原始字节，原样展示。默认解释器（首位）。 */
class TextInterpreter : MessageInterpreter {
    override val id = "text"
    override val displayName = "msg_interp_text"
    override val sortOrder = 10
    override val defaultParams: Map<String, String> = emptyMap()
    override val paramDefs: List<ParamDef> = emptyList()

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean = false

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult = InterpreterResult.Success(MessageCharsets.decode(content, codedCharSetId))
}
