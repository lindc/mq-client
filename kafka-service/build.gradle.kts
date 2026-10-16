plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // MQ 公共基类（MQServiceAdapter）：api 暴露，消费方能看到完整父类链。
            api(projects.mqBase)
        }
        jvmMain.dependencies {
            // Kafka 客户端（AdminClient / producer-consumer）。照 ibm-mq 模式
            // 直接写坐标，不进 libs.versions.toml。
            // 3.9.1：3.9.0 的 SASL 登录走 SecurityManager 旧通道（Subject.getSubject），
            // JDK 24+ 移除 SecurityManager 后直接 UnsupportedOperationException —— 升级
            // 才有 CompositeStrategy 的 modern-methods 兜底。
            implementation("org.apache.kafka:kafka-clients:3.9.1")
            // 消息正文二进制判定（isProbablyBinary）：消息列表的 valueProbablyBinary 用。
            implementation(projects.messageInterpreterApi)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // diag 实连测试（KafkaMessageDiagTest）的开关透传：命令行 -Dkafkadiag.xxx=...
    // 原样传给测试 JVM。默认一个都不设 → 测试自己打 skip，日常 jvmTest 不触网。
    System.getProperties()
        .filterKeys { it is String && it.startsWith("kafkadiag.") }
        .forEach { (key, value) -> systemProperty(key.toString(), value.toString()) }
}
