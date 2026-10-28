plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // MQ 公共基类（MQServiceAdapter：category="MQ"）。api 暴露，
            // 消费方（app-ui SPI 注册）能看到完整父类链。
            api(projects.mqBase)
            // 注意：这里**不**依赖 mq-ui。命令控制台的三个适配器
            // （MqscCommandParser / MqscCommandExecutor / MqscSyntaxHighlighter）
            // 实现的是 UI 契约，已放在 ibm-mq-ui；本模块只保留协议本体
            // （MqscParser / MqscCommandExpander / MqscExecutor / MqscPcfSender）。
            // 「*-service 不得依赖 Compose」由 scripts/check_architecture.py 守。
        }
        jvmMain.dependencies {
            implementation("com.ibm.mq:com.ibm.mq.allclient:10.0.0.5")
        }
        jvmTest.dependencies {
            // JUnit assumption 包装：LiveTest 无环境时记 SKIP 而不是 PASSED。
            implementation(projects.testSupport)
            implementation(kotlin("test"))
        }
    }
}

// SSL 诊断基准测试（IbmMQSslDiagTest / IbmMqTlsLiveTest）的参数透传：
// `-Dmqdiag.xxx=...` 与 `-Djavax.net.debug=...` 从 gradle 命令行传进测试 JVM。
// `javax.net.debug` 必须在 **JVM 启动时**给出（JSSE 在类初始化时读它），测试里
// 再 System.setProperty 已经晚了 —— 所以它只能走这条转发，不能靠 mqdiag.debug。
tasks.withType<Test>().configureEach {
    systemProperties(
        System.getProperties().stringPropertyNames()
            .filter { it.startsWith("mqdiag.") || it == "javax.net.debug" }
            .associateWith { System.getProperty(it) },
    )
}
