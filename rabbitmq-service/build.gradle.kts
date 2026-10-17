plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // 管理 API 响应 DTO（RabbitMqAdminClient）用 kotlinx.serialization 解析。
    alias(libs.plugins.kotlinSerialization)
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
            // AMQP 0.9.1 客户端（后续消息收发阶段用；第一阶段连接/扫描全走管理 API）。
            // 照 ibm-mq / kafka 模式直接写坐标，不进 libs.versions.toml。
            implementation("com.rabbitmq:amqp-client:5.22.0")
            // 管理 API HTTP 客户端（Ktor：core + CIO 引擎，版本随 catalog）。
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            // 管理 API 响应 DTO 解析。
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
