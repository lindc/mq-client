package com.mqclient.mq.base

/**
 * 消息内容解释器 SPI（2026-10 随消息详情对话框引入）：把消息原始字节按不同格式
 * （文本 / JSON / XML / IDoc / Base64 解码层 …）渲染成可读文本。
 *
 * 实现方通过 `META-INF/services/com.mqclient.mq.base.MessageInterpreter` 注册
 * （JVM ServiceLoader，装载侧对单个坏实现容错跳过，见各宿主的装载函数）。
 * 内置实现随 ibm-mq-ui 打包；第三方扩展走同一条注册路径，UI 不硬编码解释器列表。
 *
 * 约定：
 *  - [interpret] 必须纯函数、无副作用、不抛异常（失败走 [InterpreterResult.Failure]）；
 *  - [canInterpret] 只用于「推荐」提示与 Base64 解码层的内容探测，**UI 永不自动切换**
 *    当前选中的解释器；
 *  - 解释器自身无状态 —— 参数经 [interpret] 的 [params] 传入（键值来自
 *    [paramDefs] 描述的输入项，初始值取 [defaultParams]）。
 */
interface MessageInterpreter {

    /** 唯一标识（持久化选中项用），如 "text" / "json" / "base64"。 */
    val id: String

    /**
     * 显示名。允许两种取值：i18n key（内置解释器，UI 经 stringResource 查表，
 * 缺键时原样返回 key 本身）或任意明文（第三方扩展直接写可读名即可）。
     */
    val displayName: String

    /** 参数默认值（键 = [paramDefs] 里的 ParamDef.id）。 */
    val defaultParams: Map<String, String>
        get() = emptyMap()

    /**
     * 参数描述（UI 据此渲染参数输入区；空 = 该解释器无需参数）。
     * 默认空实现：保持第三方最简注册（只实现五个必需成员也能用）。
     */
    val paramDefs: List<ParamDef>
        get() = emptyList()

    /**
     * 内容探测：当前字节流是否疑似本解释器能处理的格式。只影响推荐角标与
     * Base64 解码层之后的链式选择，不做强制。
     */
    fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean

    /**
     * 解析消息体。[content] 是**原始消息体字节**（MQMessage body，不含 MQMD 头）；
     * [encoding] / [codedCharSetId] 是 MQMD.Encoding / CodedCharSetId 原始值 ——
     * 文本类解释器靠 [codedCharSetId] 选对字符集（GBK 等非 UTF-8 内容解对才可读），
     * 字节序敏感的格式看 [encoding]。
     */
    fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult
}

/** 单个参数输入项的描述（UI 据此渲染输入控件）。 */
data class ParamDef(
    /** 参数键（进 [MessageInterpreter.interpret] 的 params）。 */
    val id: String,
    /** 显示名（同 displayName 约定：i18n key 或明文）。 */
    val label: String,
    val kind: ParamKind,
)

/** 参数输入控件类型。 */
enum class ParamKind {
    /** 单行文本。 */
    TEXT,

    /** 数字（输入仍是字符串，解释器自行解析）。 */
    NUMBER,

    /** 开关（"true" / "false"）。 */
    BOOLEAN,
}

/** [MessageInterpreter.interpret] 的结果。 */
sealed interface InterpreterResult {
    /** 解析成功，[formatted] 是渲染后的可读文本。 */
    data class Success(val formatted: String) : InterpreterResult

    /** 解析失败，[message] 为原因（UI 保留原始内容 + 提示本条）。 */
    data class Failure(val message: String) : InterpreterResult
}
