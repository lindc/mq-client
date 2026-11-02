package com.mqclient.ibmmq.command

import com.ibm.mq.pcf.PCFException

/**
 * MQRC（2000 段）/ MQRCCF（3000 段）原因码 → 可读说明。服务端模块（无 Compose），
 * 文案为英文（原因码查表，码名是通用标识；数据层翻译表，不是界面拷贝 —— 兜底链与默认语言一致）。
 *
 * 3000 段的数值以 allclient 10.0.0.5 的 `CMQCFC` 常量为权威（注意：网上流传的
 * 对照表常把 CFH_TYPE_ERROR 记成 3019，实际 3001；3019 = PARM_COUNT_TOO_SMALL）。
 * 只收控制台常见的码；未收录的回退「未知原因码: N」，不猜。
 */
object MqrcTranslator {

    fun translate(reasonCode: Int): String = when (reasonCode) {
        /* ---------------- MQRC（2000 段）---------------- */
        2035 -> "Insufficient permissions (MQRC_NOT_AUTHORIZED)"
        2033 -> "Queue full / no message available (MQRC_Q_FULL)"
        2042 -> "Object already exists (MQRC_OBJECT_ALREADY_EXISTS)"
        2046 -> "Option not supported (MQRC_OPTION_NOT_SUPPORTED)"
        2051 -> "Command server not available (MQRC_CMD_SERVER_NOT_AVAILABLE)" // 命令服务器未运行时的典型表现
        2085 -> "Unknown object name (MQRC_UNKNOWN_OBJECT_NAME)"
        2087 -> "Unknown object queue manager (MQRC_UNKNOWN_OBJECT_Q_MGR)"
        2393 -> "SSL initialization failed (MQRC_SSL_INITIALIZATION_ERROR)"
        2397 -> "Unsupported cipher suite (MQRC_UNSUPPORTED_CIPHER_SUITE)"

        /* ---------------- MQRCCF（3000 段，PCF 层）---------------- */
        3001 -> "Invalid PCF message header type (MQRCCF_CFH_TYPE_ERROR)"
        3002 -> "Invalid PCF message header length (MQRCCF_CFH_LENGTH_ERROR)"
        3003 -> "Invalid PCF message header version (MQRCCF_CFH_VERSION_ERROR)"
        3004 -> "Invalid PCF message sequence number (MQRCCF_CFH_MSG_SEQ_NUMBER_ERR)"
        3005 -> "Invalid PCF control options (MQRCCF_CFH_CONTROL_ERROR)"
        3006 -> "Invalid PCF parameter count (MQRCCF_CFH_PARM_COUNT_ERROR)"
        3007 -> "Invalid PCF command id (MQRCCF_CFH_COMMAND_ERROR)"
        3008 -> "Command failed (MQRCCF_COMMAND_FAILED)"
        3009 -> "Invalid integer parameter length (MQRCCF_CFIN_LENGTH_ERROR)"
        3010 -> "Invalid string parameter length (MQRCCF_CFST_LENGTH_ERROR)"
        3011 -> "Invalid string length (MQRCCF_CFST_STRING_LENGTH_ERR)"
        3013 -> "Invalid PCF structure type (MQRCCF_STRUCTURE_TYPE_ERROR)"
        3014 -> "Invalid integer parameter id (MQRCCF_CFIN_PARM_ID_ERROR)"
        3015 -> "Invalid string parameter id (MQRCCF_CFST_PARM_ID_ERROR)"
        3017 -> "Duplicate integer parameter (MQRCCF_CFIN_DUPLICATE_PARM)"
        3018 -> "Duplicate string parameter (MQRCCF_CFST_DUPLICATE_PARM)"
        3019 -> "Too few parameters (the escape request misses required ones like EscapeType) (MQRCCF_PARM_COUNT_TOO_SMALL)"
        3020 -> "Too many parameters (MQRCCF_PARM_COUNT_TOO_BIG)"
        3023 -> "Invalid message descriptor format (MQRCCF_MD_FORMAT_ERROR)"
        // 按名查 CHSTATUS / LISTENER_STATUS 时「对象无状态实例」会以此码失败（不是空结果）
        3065 -> "No status record for the channel (not running or never started) (MQRCCF_CHL_STATUS_NOT_FOUND)"
        3247 -> "Invalid float parameter id (MQRCCF_CFSF_PARM_ID_ERROR)"

        else -> "Unknown reason code: $reasonCode"
    }

    /**
     * MQ 查询/命令异常 → 可读文案：PCFException 走原因码翻译表，其余取 message。
     * 放在服务模块的理由：PCFException 是 allclient 类型（implementation 依赖），
     * UI 模块引用不到 —— 调用方只拿 String，不暴露底层类型。
     */
    fun describe(error: Throwable): String = when (error) {
        is PCFException -> translate(error.reasonCode)
        else -> error.message ?: error::class.simpleName ?: "unknown"
    }
}
