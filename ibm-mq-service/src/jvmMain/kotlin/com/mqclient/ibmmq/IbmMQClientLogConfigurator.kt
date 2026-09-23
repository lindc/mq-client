package com.mqclient.ibmmq

import com.mqclient.core.ConfigStore
import java.nio.file.Files
import kotlin.jvm.Synchronized

/**
 * IBM MQ Java 客户端（allclient）的日志落点配置 —— **必须在任何 MQ 类被触碰之前调用**
 * （与 [IbmMQModule.initialize] 里关 cipher 映射是同一时机约束：客户端在首次类加载时
 * 读取并缓存这些属性）。
 *
 * 不配置时客户端把 `mqjms.log.0` 落在**当前工作目录**，污染项目根/模块根；这里把它
 * 引到应用的统一日志目录 `~/.mqclient/logs/`（跟随 `mqclient.config.dir` 覆盖，与
 * app-core 自身的 `mqclient.log` 同目录）。文件名给 `mqjms.log`，滚动产物由客户端
 * 默认策略追加 `.0` / `.1` 后缀，无需自定义。
 *
 * 幂等 + 线程安全：双检 + [initialized] 标记，重复调用零开销；任何失败只记日志，
 * 不抛 —— 日志配置坏了不该挡住模块启动。
 */
object IbmMQClientLogConfigurator {

    private const val PROP_LOG_OUTPUT = "com.ibm.msg.client.commonservices.log.outputName"
    private const val PROP_LOG_STATUS = "com.ibm.msg.client.commonservices.log.status"
    private const val PROP_TRACE_STATUS = "com.ibm.msg.client.commonservices.trace.status"

    /** 日志目录下的文件名；实际落盘是 `mqjms.log.0`（客户端滚动策略自动加序号）。 */
    private const val MQ_LOG_FILE_NAME = "mqjms.log"

    @Volatile
    private var initialized = false

    /**
     * 配置 MQ 客户端日志落点。幂等：只有第一次调用真正 setProperty。
     * 失败（目录建不出等）只记 warn，不抛异常、不置位 —— 下次调用可重试。
     */
    @Synchronized
    fun init() {
        if (initialized) return
        try {
            val logDir = ConfigStore.configDir().resolve("logs").toFile()
            Files.createDirectories(logDir.toPath())
            val logFile = logDir.resolve(MQ_LOG_FILE_NAME)

            System.setProperty(PROP_LOG_OUTPUT, logFile.absolutePath)
            System.setProperty(PROP_LOG_STATUS, "on")
            // 默认关 trace：那是逐方法级的跟踪，量级远超日志，只在排障时手动打开。
            System.setProperty(PROP_TRACE_STATUS, "off")

            initialized = true
            ibmMqLog.info("mq client log configured: output=${logFile.absolutePath}")
        } catch (e: Exception) {
            ibmMqLog.warn("mq client log config failed: ${e.message} (client falls back to cwd)")
        }
    }

    /** 当前生效的 MQ 客户端日志相关系统属性（排障用：确认 init 是否生效）。 */
    fun describe(): String =
        listOf(PROP_LOG_OUTPUT, PROP_LOG_STATUS, PROP_TRACE_STATUS)
            .joinToString("\n") { "$it=${System.getProperty(it)}" }
}
