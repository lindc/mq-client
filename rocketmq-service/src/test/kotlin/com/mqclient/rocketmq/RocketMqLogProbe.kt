package com.mqclient.rocketmq

/**
 * 子进程探针：只被 [RocketMqLogsStayOutOfHomeTest] fork 出来的独立 JVM 调用。
 *
 * 放在测试源集里是刻意的 —— 它要跑在**和被测模块相同的 classpath** 上，才能证明
 * 「配置层面」的修复（`rocketmq-service` resources 里的 `rmq.logback.xml`）单独成立，
 * 而不是靠某个先跑过的 `install()`。
 *
 * 参数：`args[0]` = `"bare"`（不调 install）或 `"install"`（先调 install 再打日志）。
 * 打日志时同时用 `RocketmqTools`（tools 配置里写死到 `$HOME/logs` 的那个 logger 名）
 * 和普通名字，确保覆盖两条真实路径。
 */
object RocketMqLogProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: "bare"
        if (mode == "install") {
            println("LOG_ROOT=" + RocketMqLogging.install())
            println("CONFIG_URL=" + RocketMqLogging.configUrl)
        }
        println("PROP_rocketmq.log.root=" + System.getProperty("rocketmq.log.root"))
        println("PROP_rocketmq.client.logRoot=" + System.getProperty("rocketmq.client.logRoot"))
        println("PROP_rmq.logback.configurationFile=" + System.getProperty(RocketMqLogging.CONFIG_PROPERTY))
        println("USER_HOME=" + System.getProperty("user.home"))

        // 触发 shaded logback 的 LoggerContext 初始化（首次加载即定格配置）。
        val tools = org.apache.rocketmq.logging.org.slf4j.LoggerFactory.getLogger("RocketmqTools")
        tools.info("probe from RocketmqTools")
        val common = org.apache.rocketmq.logging.org.slf4j.LoggerFactory.getLogger("RocketmqCommon")
        common.info("probe from RocketmqCommon")

        // AsyncAppender 后台线程落盘，给它一点时间再退出。
        Thread.sleep(500)
        println("PROBE_DONE")
    }
}
