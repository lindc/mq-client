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
            // stringResource（i18n 查表）来自 ui-common；占位页复用其共享
            // 基础能力，不新造可复用组件。
            implementation(projects.uiCommon)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // 密码可见性切换的 Visibility / VisibilityOff 图标（跨平台矢量，
            // 与 kafka-ui / ibm-mq-ui 同款；Linux 无 Emoji 字体也能正常显示）。
            implementation(compose.materialIconsExtended)
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
