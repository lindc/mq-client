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
            // KafkaConfig / toOptions / fromOptions：表单草稿与 options 的转换在
            // kafka-service，表单只做 UI 与落盘调用。KafkaModule（连接上下文入口）同源。
            implementation(projects.kafkaService)
            // 对象列表通用组件（ObjectListPanel / ObjectCardData / LoadStateContent /
            // CompactSearchField / ToolbarButton）：Topic 列表页复用。
            implementation(projects.mqUi)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // Material Icons Extended：密码可见性切换等小图标，跨平台（Linux 无 Emoji 字体）一致。
            implementation(compose.materialIconsExtended)
            // composeResources（品牌图标 drawable）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
            // stringResource（i18n 查表）来自 ui-common 的 LanguageResources。
            implementation(projects.uiCommon)
            // 「询问 AI」链路上下文的 JSON 序列化（KafkaAiChainBuilder，buildJsonObject）。
            implementation(libs.kotlinx.serialization.json)
            // 拓扑图通用件：模型契约（graph-core）+ 通用渲染（TopologyCanvas 等，topology-ui）。
            implementation(projects.graphCore)
            implementation(projects.topologyUi)
        }
        jvmMain.dependencies {
            // Kuiver 模型 API（buildKuiver / LayoutProvider / buildKuiverWithClassifiedEdges）：
            // Kafka 拓扑 builder 与列布局 provider 直用（渲染走 topology-ui 的 TopologyCanvas）。
            implementation(libs.kuiver)
            // Kafka 客户端类型（AdminClient / ListTopicsOptions / TopicCollection）：
            // TopicListPanel 直连实时查询。kafka-service 里是 implementation 不传递，
            // 照模块间各声明坐标的惯例在本模块再声明一份（版本必须与 service 一致）。
            // 版本须与 kafka-service 一致（3.9.1：JDK 24+ 移除 SecurityManager，
            // 3.9.0 的 SASL 登录在该 JDK 上会 UnsupportedOperationException）。
            implementation("org.apache.kafka:kafka-clients:3.9.1")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
