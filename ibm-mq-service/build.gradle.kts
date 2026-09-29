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
            // mq-ui 通用命令控制台的接口与模型（CommandExecutor / CommandParser /
            // SyntaxHighlighter / CommandResult…）。语法高亮的 AnnotatedString 等
            // compose ui-text 类型由 mq-ui 以 api 传递过来，本模块不直接引 compose。
            implementation(projects.mqUi)
        }
        jvmMain.dependencies {
            implementation("com.ibm.mq:com.ibm.mq.allclient:10.0.0.5")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// SSL 诊断基准测试（IbmMQSslDiagTest）的参数透传：`-Dmqdiag.xxx=...` 从 gradle
// 命令行传进测试 JVM。
tasks.withType<Test>().configureEach {
    systemProperties(
        System.getProperties().stringPropertyNames()
            .filter { it.startsWith("mqdiag.") }
            .associateWith { System.getProperty(it) },
    )
}
