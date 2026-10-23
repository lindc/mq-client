plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // RabbitMqConfig / toOptions / fromOptions：表单草稿与 options 的转换在
            // rabbitmq-service，表单只做 UI 与落盘调用。RabbitMqModule（连接上下文
            // 入口）同源。
            implementation(projects.rabbitmqService)
            // 对象列表通用组件（ObjectListPanel / ObjectCardData / LoadStateContent /
            // CompactSearchField / ToolbarButton）：队列 / 交换机列表页复用。
            implementation(projects.mqUi)
            // 拓扑图通用件：模型契约（graph-core 经 topology-ui api 传递）+ 通用渲染
            // （TopologyCanvas 等）——拓扑页复用。
            implementation(projects.topologyUi)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // Material Icons Extended：密码可见性切换等小图标，跨平台（Linux 无 Emoji 字体）一致。
            implementation(compose.materialIconsExtended)
            // composeResources（品牌图标 drawable + i18n JSON）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
            // stringResource（i18n 查表）来自 ui-common 的 LanguageResources。
            implementation(projects.uiCommon)
            // 消息正文解释器：聚合注册表一个依赖带上契约 + 全部实现（ServiceLoader
            // 运行时发现），队列消息详情对话框的「解释器」窗格用（kafka-ui 同款）。
            implementation(projects.messageInterpreterRegistry)
            // kotlinx.serialization JSON DSL：AiChainBuilder 组装链路上下文（kafka-ui 同款）。
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // 「落盘不在 UI 线程」的测试替身（RecordingConnectionPersistence / UiThreadHarness）。
            implementation(projects.uiTestKit)
        }
    }
}
