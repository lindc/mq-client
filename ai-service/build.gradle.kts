plugins {
    id("mq.kotlin-jvm")
    id("mq.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // DatabaseManager / ConfigStore / EventNames（core 是事件的发出方）。
            implementation(projects.appCore)
            // sqldelight runtime 经 app-core 的 api 传递，这里不用重复声明。
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// AI 模块自己的表（ai_connection 快照 + chat_history 对话审计）。app-core 只提供
// 基础设施（DatabaseManager 单槽串行调度器），表结构归业务模块。
sqldelight {
    databases {
        create("AiChatDatabase") {
            packageName.set("com.mqclient.aiservice.db")
        }
    }
}
