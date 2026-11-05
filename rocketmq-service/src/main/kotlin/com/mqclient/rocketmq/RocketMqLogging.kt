package com.mqclient.rocketmq

import java.io.File

/**
 * RocketMQ 日志落点的**唯一安装器**：把 shaded logback 的所有日志压进
 * `<config-dir>/logs/rocketmqlogs`，绝不落到 `${user.home}/logs/rocketmqlogs`。
 *
 * ## 为什么需要它（以及为什么光靠 setProperty 不够）
 *
 * RocketMQ 5.x 把 logback 整包 relocate 成 `org.apache.rocketmq.logging.ch.qos.logback`，
 * 全进程**只有一个 LoggerContext**，配置在首个日志类加载时定格、只读一次。配置来源由
 * `org.apache.rocketmq.common.logging.DefaultJoranConfiguratorExt` 决定：
 *
 * 1. 先读系统属性 `rmq.logback.configurationFile`（非空直接用）；
 * 2. 否则按固定名单顺序在 classpath 上找：
 *    `rmq.logback-test.xml` → **`rmq.logback.xml`** → … → `rmq.tools.logback.xml` →
 *    `rmq.client.logback.xml`。
 *
 * 而 `rocketmq-tools-5.3.1.jar` 里的 `rmq.tools.logback.xml` 把 `<file>` **写死**成
 * `${user.home}/logs/rocketmqlogs/tools.log`，根本不引用任何 logRoot 变量 —— 这就是
 * 「设了 rocketmq.log.root 还是在用户根目录拉屎」的原因。
 *
 * 所以本类做两件事，缺一不可：
 *
 * - **配置层面**：`rocketmq-service` 的 resources 里带一份 `rmq.logback.xml`。只要火箭
 *   MQ 在 classpath 上，这份配置就按名称排在 tools / client 之前被选中（应用、模块测试、
 *   诊断工具、未来 CLI 全覆盖，不依赖某个入口是否先跑了本类）。本类再把
 *   `rmq.logback.configurationFile` 显式指向它，属性优先于名称顺序，双保险。
 * - **属性层面**：把所有已知口径的 logRoot / level / maxIndex 一次性设好（5.x 新名与
 *   4.x 老名都设），并在属性被外部显式指定时不覆盖 —— 让运维仍能用 `-D` 改级别。
 *
 * ## 时序契约
 *
 * **必须在任何 RocketMQ 类被加载之前调用**，否则 LoggerContext 已定格，本类只剩兜底
 * 意义。现役调用点：
 *
 * - `main()` 第一条语句（应用启动）；
 * - [RocketMqAdminClient] / [RocketMqMessageClients] 构造时（服务层兜底，保证即使有人
 *   绕过入口直接 new 客户端，属性也已设好）。
 *
 * ## 依赖纪律
 *
 * 本对象**只允许**碰 `java.io` / `java.util.concurrent` / `System`：它会在 `main()` 的
 * 第一行被调用，任何对 RocketMQ / Compose / app-core 的引用都可能反过来提前牵出
 * 日志类，把「先设属性、后加载类」的顺序毁掉。类的 `<clinit>` 里同样不许有重活
 * （[installed] 只是 AtomicBoolean，[logRoot] 只是引用）。
 */
object RocketMqLogging {

    /** 配置目录覆盖（与 `com.mqclient.core.ConfigManager.configDir` 同一个 hook）。 */
    const val CONFIG_DIR_PROPERTY: String = "mqclient.config.dir"

    /** 应用自带的 logback 配置；[rocketmq-service/resources] 下的同名资源。 */
    const val CONFIG_RESOURCE: String = "rmq.logback.xml"

    /** 强制覆盖用的系统属性名（`DefaultJoranConfiguratorExt` 优先读它）。 */
    const val CONFIG_PROPERTY: String = "rmq.logback.configurationFile"

    private val lock = Any()

    @Volatile
    private var installedRoot: String? = null

    /**
     * 已解析的日志根目录（绝对路径）；[install] 之前为 null。**只读**，供诊断与测试断言。
     */
    @Volatile
    var logRoot: String? = null
        private set

    /** 实际生效的 logback 配置文件 URL（属性覆盖值或 classpath 资源）；找不到则 null。 */
    @Volatile
    var configUrl: String? = null
        private set

    /**
     * 幂等安装：建目录 → 设全部 logRoot/level/maxIndex → 指向应用自带 logback 配置。
     *
     * 返回日志根目录绝对路径。多次调用只有第一次做实事（其余直接返回缓存的路径），
     * 所以服务层可以放心在每个客户端构造时顺手调一次。
     */
    fun install(): String {
        installedRoot?.let { return it }
        synchronized(lock) {
            installedRoot?.let { return it }

            val path = resolveLogRoot()
            runCatching { File(path).mkdirs() }

            // 落点：**强制**覆盖（这是本类的全部意义），5.x 新名 + 4.x 老名都设。
            System.setProperty("rocketmq.log.root", path)
            System.setProperty("rocketmq.client.logRoot", path)

            // 级别 / 滚动：仅在外部未指定时给默认值，保留 -D 的可控性。
            putIfAbsent("rocketmq.log.level", "INFO")
            putIfAbsent("rocketmq.client.logLevel", "INFO")
            putIfAbsent("rocketmq.log.file.maxIndex", "10")
            putIfAbsent("rocketmq.client.logFileMaxIndex", "10")

            // 配置覆盖：属性优先于 configFiles 的名称顺序（见类注释）。
            val url = classLoader().getResource(CONFIG_RESOURCE)?.toExternalForm()
            if (url != null) {
                System.setProperty(CONFIG_PROPERTY, url)
            }
            configUrl = url

            logRoot = path
            installedRoot = path
            return path
        }
    }

    /** `<config-dir>/logs/rocketmqlogs` 的绝对路径（不产生副作用，可提前用于日志文案）。 */
    fun resolveLogRoot(): String {
        val configDir = System.getProperty(CONFIG_DIR_PROPERTY)
            ?: (System.getProperty("user.home") + File.separator + ".mqclient")
        return File(configDir, "logs" + File.separator + "rocketmqlogs").absolutePath
    }

    private fun putIfAbsent(key: String, value: String) {
        if (System.getProperty(key).isNullOrBlank()) {
            System.setProperty(key, value)
        }
    }

    /**
     * 取**装载本类的** classloader，而不是线程上下文 classloader：后者在测试框架 / 应用
     * 服务器里可能被换成看不到本模块资源的 shell classloader（Gradle worker 就有这种
     * 包装），那会让 `getResource(CONFIG_RESOURCE)` 落空。
     */
    private fun classLoader(): ClassLoader =
        RocketMqLogging::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
}
