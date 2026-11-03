package com.mqclient.rocketmq

import com.mqclient.core.ConfigStore
import java.nio.file.Files
import kotlin.jvm.Synchronized

/**
 * RocketMQ 客户端（shaded logback：`io.github.aliyunmq:rocketmq-logback-classic`）
 * 的日志落点配置 —— **必须在任何 RocketMQ 类被触碰之前调用**（客户端在 shaded
 * LoggerFactory 首次初始化时读取并缓存这些属性，只读一次）。主力调用点是
 * [RocketMqJvmBootstrap]（经 `ModuleRegistry.runJvmBootstraps` 在 `main()` 最开头
 * 跑，早于一切模块类加载）；[RocketMqModule.initialize] 第一行是兜底调用点。两者
 * 都先于任何 RocketMQ 客户端类被加载，且 [init] 幂等，重复调用零开销。
 *
 * 不配置时 5.x 客户端把 `rocketmq_client.log` 落在 `~/logs/rocketmqlogs`，污染用户
 * HOME 根目录；这里把它引到应用的统一数据目录 `~/.mqclient/logs/rocketmqlogs`
 * （跟随 `mqclient.config.dir` 覆盖，与 IBM MQ 客户端日志的落点策略同一口径，
 * 见 ibm-mq 的 IbmMQClientLogConfigurator）。
 *
 * 属性名两套都设：5.x 的 `rocketmq.log.*` 是 rmq.client.logback.xml 的实测口径
 * （解包 rocketmq-client-5.3.1 核对），4.x 的 `rocketmq.client.logRoot` 是 ClientLogger
 * 的老名 —— 多余属性无副作用，防版本变动复发。
 *
 * 幂等 + 线程安全：双检 + [initialized] 标记，重复调用零开销；任何失败只记日志
 * （客户端回落默认的 `~/logs/rocketmqlogs`），不抛、不置位 —— 日志配置坏了不该
 * 挡住模块启动，下次 initialize 可重试。
 */
object RocketMqClientLogConfigurator {

    // 5.x 属性名：rmq.client.logback.xml 的变量引用（rocketmq-logback-classic 解析）。
    private const val PROP_LOG_ROOT_5X = "rocketmq.log.root"
    private const val PROP_MAX_INDEX_5X = "rocketmq.log.file.maxIndex"
    private const val PROP_LOG_LEVEL_5X = "rocketmq.log.level"

    // 4.x 属性名（ClientLogger 口径）：兼容兜底，版本变动时老名字也能命中。
    private const val PROP_LOG_ROOT_4X = "rocketmq.client.logRoot"
    private const val PROP_MAX_INDEX_4X = "rocketmq.client.logFileMaxIndex"

    /** 滚动文件上限：与客户端默认一致（10 × 64MB），显式写死防默认值变化。 */
    private const val LOG_FILE_MAX_INDEX = "10"

    @Volatile
    private var initialized = false

    /**
     * 配置客户端日志落点。幂等：只有第一次调用真正 setProperty。
     * 失败（目录建不出等）只记 warn，不抛异常、不置位 —— 下次调用可重试。
     */
    @Synchronized
    fun init() {
        if (initialized) return
        try {
            val logDir = ConfigStore.configDir().resolve("logs").resolve("rocketmqlogs").toFile()
            Files.createDirectories(logDir.toPath())

            System.setProperty(PROP_LOG_ROOT_5X, logDir.absolutePath)
            System.setProperty(PROP_MAX_INDEX_5X, LOG_FILE_MAX_INDEX)
            System.setProperty(PROP_LOG_LEVEL_5X, "INFO")
            System.setProperty(PROP_LOG_ROOT_4X, logDir.absolutePath)
            System.setProperty(PROP_MAX_INDEX_4X, LOG_FILE_MAX_INDEX)

            initialized = true
            rocketMqLog.info("rocketmq client log root: ${logDir.absolutePath}")
        } catch (e: Exception) {
            rocketMqLog.warn(
                "rocketmq client log config failed: ${e.message} (client falls back to ~/logs/rocketmqlogs)",
            )
        }
    }

    /** 当前生效的客户端日志相关系统属性（排障用：确认 init 是否生效）。 */
    fun describe(): String =
        listOf(PROP_LOG_ROOT_5X, PROP_MAX_INDEX_5X, PROP_LOG_LEVEL_5X, PROP_LOG_ROOT_4X, PROP_MAX_INDEX_4X)
            .joinToString("\n") { "$it=${System.getProperty(it)}" }

    /** 仅供测试：清掉幂等标记（`initialized` 是单例状态，用例间要隔离）。 */
    internal fun resetForTest() {
        initialized = false
    }
}
