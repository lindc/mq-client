plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
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
