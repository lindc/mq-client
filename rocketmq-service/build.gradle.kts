plugins {
    id("mq.kotlin-jvm")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // MQ 公共基类（MQServiceAdapter）：api 暴露，消费方能看到完整父类链。
            api(projects.mqBase)
        }
        jvmMain.dependencies {
            // RocketMQ Remoting 管理客户端（DefaultMQAdminExt 在 tools 构件里，传递
            // rocketmq-client / remoting / netty）。5.3.1：兼容 4.x NameServer/Broker
            // 的管理面；面向 5.x gRPC Proxy 的接入（rocketmq-client-java）后续按需另开。
            // 照 ibm-mq / kafka 模式直接写坐标，不进 libs.versions.toml。
            implementation("org.apache.rocketmq:rocketmq-tools:5.3.1")
            // ACL 凭证：AclClientRPCHook + SessionCredentials（RPCHook 构造参数注入）。
            implementation("org.apache.rocketmq:rocketmq-acl:5.3.1")
            // 消息正文二进制判定（isProbablyBinary）：消息列表的 valueProbablyBinary 用。
            implementation(projects.messageInterpreterApi)
        }
        jvmTest.dependencies {
            // JUnit assumption 包装：LiveTest 无环境时记 SKIP 而不是 PASSED。
            implementation(projects.testSupport)
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // diag 实连测试（RocketMqConnectionDiagTest）的开关透传：命令行 -Drocketmqdiag.xxx=...
    // 原样传给测试 JVM。默认一个都不设 → 测试自己打 skip，日常 jvmTest 不触网。
    System.getProperties()
        .filterKeys { it is String && it.startsWith("rocketmqdiag.") }
        .forEach { (key, value) -> systemProperty(key.toString(), value.toString()) }
}
