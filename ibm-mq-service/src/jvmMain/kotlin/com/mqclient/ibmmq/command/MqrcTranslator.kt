package com.mqclient.ibmmq.command

import com.ibm.mq.pcf.PCFException

/**
 * MQRC（2000 段）/ MQRCCF（3000 段）原因码 → 可读说明。服务端模块（无 Compose），
 * 文案不经 stringResource —— 这是数据层的翻译表，不是界面拷贝。
 *
 * 3000 段的数值以 allclient 10.0.0.5 的 `CMQCFC` 常量为权威（注意：网上流传的
 * 对照表常把 CFH_TYPE_ERROR 记成 3019，实际 3001；3019 = PARM_COUNT_TOO_SMALL）。
 * 只收控制台常见的码；未收录的回退「未知原因码: N」，不猜。
 */
object MqrcTranslator {

    fun translate(reasonCode: Int): String = when (reasonCode) {
        /* ---------------- MQRC（2000 段）---------------- */
        2035 -> "权限不足 (MQRC_NOT_AUTHORIZED)"
        2033 -> "队列已满 / 无消息 (MQRC_Q_FULL)"
        2042 -> "对象已存在 (MQRC_OBJECT_ALREADY_EXISTS)"
        2046 -> "选项不支持 (MQRC_OPTION_NOT_SUPPORTED)"
        2051 -> "命令服务器未就绪 (MQRC_CMD_SERVER_NOT_AVAILABLE)" // 命令服务器未运行时的典型表现
        2085 -> "对象不存在 (MQRC_UNKNOWN_OBJECT_NAME)"
        2087 -> "对象名错误 (MQRC_UNKNOWN_OBJECT_Q_MGR)"
        2393 -> "SSL 初始化失败 (MQRC_SSL_INITIALIZATION_ERROR)"
        2397 -> "不支持的加密套件 (MQRC_UNSUPPORTED_CIPHER_SUITE)"

        /* ---------------- MQRCCF（3000 段，PCF 层）---------------- */
        3001 -> "PCF 消息头类型无效 (MQRCCF_CFH_TYPE_ERROR)"
        3002 -> "PCF 消息头长度无效 (MQRCCF_CFH_LENGTH_ERROR)"
        3003 -> "PCF 消息头版本无效 (MQRCCF_CFH_VERSION_ERROR)"
        3004 -> "PCF 消息序号无效 (MQRCCF_CFH_MSG_SEQ_NUMBER_ERR)"
        3005 -> "PCF 控制选项无效 (MQRCCF_CFH_CONTROL_ERROR)"
        3006 -> "PCF 参数计数无效 (MQRCCF_CFH_PARM_COUNT_ERROR)"
        3007 -> "PCF 命令 ID 无效 (MQRCCF_CFH_COMMAND_ERROR)"
        3008 -> "命令执行失败 (MQRCCF_COMMAND_FAILED)"
        3009 -> "整型参数长度无效 (MQRCCF_CFIN_LENGTH_ERROR)"
        3010 -> "字符串参数长度无效 (MQRCCF_CFST_LENGTH_ERROR)"
        3011 -> "字符串长度无效 (MQRCCF_CFST_STRING_LENGTH_ERR)"
        3013 -> "PCF 结构类型无效 (MQRCCF_STRUCTURE_TYPE_ERROR)"
        3014 -> "整型参数 ID 无效 (MQRCCF_CFIN_PARM_ID_ERROR)"
        3015 -> "字符串参数 ID 无效 (MQRCCF_CFST_PARM_ID_ERROR)"
        3017 -> "整型参数重复 (MQRCCF_CFIN_DUPLICATE_PARM)"
        3018 -> "字符串参数重复 (MQRCCF_CFST_DUPLICATE_PARM)"
        3019 -> "参数个数太少（escape 请求缺 EscapeType 等必需参数）(MQRCCF_PARM_COUNT_TOO_SMALL)"
        3020 -> "参数个数太多 (MQRCCF_PARM_COUNT_TOO_BIG)"
        3023 -> "消息描述符格式错误 (MQRCCF_MD_FORMAT_ERROR)"
        // 按名查 CHSTATUS / LISTENER_STATUS 时「对象无状态实例」会以此码失败（不是空结果）
        3065 -> "通道无状态记录（未运行或从未启动）(MQRCCF_CHL_STATUS_NOT_FOUND)"
        3247 -> "浮点参数 ID 无效 (MQRCCF_CFSF_PARM_ID_ERROR)"

        else -> "未知原因码: $reasonCode"
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
