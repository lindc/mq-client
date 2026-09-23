package com.mqclient.pulsar

import com.mqclient.core.log.Log

/**
 * 本模块的模块名 —— **模块内唯一定义处**：`ServiceAdapter.id`、任务 ID 前缀、
 * 日志 tag 全部由它派生，不再各写各的字面量。
 */
internal const val MODULE_ID = "pulsar"

/** 本模块的日志句柄：tag 与 [MODULE_ID] 同源，调用点不需要再写模块名。 */
internal val pulsarLog = Log.withTag(MODULE_ID)
