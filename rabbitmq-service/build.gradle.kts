plugins {
    id("mq.kotlin-jvm")
    id("mq.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // 消息体二进制判定（isProbablyBinary）：队列消息会话的拉取预览用。
            implementation(projects.messageInterpreterApi)
            // MQ 公共基类（MQServiceAdapter）：api 暴露，消费方能看到完整父类链。
            api(projects.mqBase)
        }
        jvmMain.dependencies {
            // AMQP 0.9.1 客户端（后续消息收发阶段用；第一阶段连接/扫描全走管理 API）。
            // 照 ibm-mq / kafka 模式直接写坐标，不进 libs.versions.toml。
            implementation("com.rabbitmq:amqp-client:5.22.0")
            // 管理 API HTTP 客户端（Ktor：core + CIO 引擎，版本随 catalog）。
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            // 自签 TLS：CIO 引擎不支持定制 SSLContext，Java 引擎可以（管理 API https 用）。
            implementation(libs.ktor.client.java)
            // 管理 API 响应 DTO 解析。
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // JUnit assumption 包装：LiveTest 无环境时记 SKIP 而不是 PASSED。
            implementation(projects.testSupport)
            implementation(kotlin("test"))
            // 管理 API 写操作契约测试：MockEngine 替换真实网络（不碰集群）。
            implementation(libs.ktor.client.mock)
        }
    }
}

// 真机诊断测试（RabbitMqScanPartialLiveTest 等）的参数透传：`-Drabbitmqdiag.xxx=...`
// 从 gradle 命令行传进测试 JVM。与 ibm-mq-service 的 `mqdiag.*` 同一机制。
tasks.withType<Test>().configureEach {
    systemProperties(
        System.getProperties().stringPropertyNames()
            .filter { it.startsWith("rabbitmqdiag.") }
            .associateWith { System.getProperty(it) },
    )
}
