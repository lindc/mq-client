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
            // Kafka 客户端（AdminClient / 将来的 producer-consumer）。照 ibm-mq 模式
            // 直接写坐标，不进 libs.versions.toml。
            implementation("org.apache.kafka:kafka-clients:3.9.0")
        }
    }
}
