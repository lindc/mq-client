rootProject.name = "mq-client"
// Gradle 9.7.1 仍需这个预览开关：删掉之后各 build.gradle.kts 里的 `projects.appCore`
// 之类类型安全项目访问器会直接「Unresolved reference」（实测）。
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(
    ":app-core",
    ":app-ui",
    ":ai-ui",
    ":ai-service",
    ":ui-common",
    // Compose UI 测试工具（画布手势 / 语义定位），各 UI 模块 jvmTest 共用。
    ":ui-test-kit",
    // AI 核心层（契约 + 基建 + 注册表），不认识任何具体 provider。
    ":ai-core",
    // 每个 provider 一个模块，平级：删掉某个 provider 不影响 AI 栈其余部分。
    ":openai-service",
    ":openai-ui",
    ":anthropic-service",
    ":anthropic-ui",
    ":ibm-mq-service", ":ibm-mq-ui",
    ":mq-base",
    ":mq-ui",
    ":kafka-service", ":kafka-ui",
    ":rabbitmq-service", ":rabbitmq-ui",
    ":rocketmq-service", ":rocketmq-ui",
    // 拓扑图通用件：graph-core 纯模型（统一节点词汇），topology-ui 通用渲染。
    ":graph-core",
    ":topology-ui",
    // 消息解释器 SPI：接口模块 + 每个解释器一个模块 + 聚合注册表。
    ":message-interpreter-api",
    ":message-interpreter-json",
    ":message-interpreter-base64",
    ":message-interpreter-text",
    ":message-interpreter-xml",
    ":message-interpreter-idoc",
    ":message-interpreter-hex",
    ":message-interpreter-registry",
)
