plugins {
    id("mq.kotlin-jvm")
    id("mq.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // 协议层骨架（PROTOCOL_ID / PROTOCOL_NAME 常量）；service 侧 api
            // 暴露的 app-core / olap-base 类型随之传递，jvmMain 的 AdapterUI
            // 实现需要它们。
            implementation(projects.starrocksService)
            // 连接表单复用 mq-ui 的保存状态机（AbstractConnectionFormState）与
            // 「测试失败是否仍要保存」确认框（SaveAnywayDialog / SaveState）——
            // 与 IBM MQ / Kafka / RabbitMQ / RocketMQ 四套表单完全同构。
            implementation(projects.mqUi)
            // 拓扑面板：TopologyCanvas / feBeLayout（拓扑渲染）+ graph-core 的
            // TopologyData / TopologyNodeType（经 topology-ui 的 api 传递）。
            implementation(projects.topologyUi)
            // AI 慢查询诊断：ChatService / AdapterChatService（SSE 流式）、
            // wrapUntrustedContext 安全边界、AiMessageBubble 的 Markdown 渲染。
            implementation(projects.aiUi)
            // stringResource（i18n 查表）来自 ui-common；占位页复用其共享
            // 基础能力，不新造可复用组件。
            implementation(projects.uiCommon)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // composeResources（品牌图标 drawable）需要资源 API 才能生成访问器
            // （与 kafka-ui 同款；缺了它 Res 类不生成，ModuleIcon 编译不过）。
            implementation(compose.components.resources)
            // 密码可见性切换的 Visibility / VisibilityOff 图标（跨平台矢量，
            // 与 kafka-ui / ibm-mq-ui 同款；Linux 无 Emoji 字体也能正常显示）。
            implementation(compose.materialIconsExtended)
            // Profile 结果的 JSON 美化（ANALYZE PROFILE 返回文本/JSON）。
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Compose 画布冒烟测试工具（runComposeUiTest / 语义定位）。
            implementation(projects.uiTestKit)
            // Skia 原生运行时由 compose 插件按宿主机注入，需显式声明（ui-test-kit 不透传）。
            implementation(compose.desktop.currentOs)
        }
    }
}
