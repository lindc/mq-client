plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            implementation(projects.openaiService)
            implementation(projects.uiCommon)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// AI 模块自己的表（ai_connection 快照 + chat_history 对话审计）。app-core 只提供
// 基础设施（DatabaseManager 单槽串行调度器），表结构归业务模块 —— 与架构注释一致。
// runtime 依赖经 app-core 的 api 传递，这里不用重复声明。
sqldelight {
    databases {
        create("AiChatDatabase") {
            packageName.set("com.mqclient.aipanel.db")
        }
    }
}