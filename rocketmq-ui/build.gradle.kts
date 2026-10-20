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
            // RocketMqModule（contentFor 的连接判活 / scanNow）与 RocketMqConfig。
            implementation(projects.rocketmqService)
            // 对象列表通用组件（ObjectListPanel / ObjectCardData / LoadStateContent /
            // CompactSearchField / ToolbarButton）：主题 / 消费组列表页复用。
            implementation(projects.mqUi)
            // 拓扑渲染（TopologyCanvas / 布局契约）+ 图模型（graph-core 经 api 传递）。
            implementation(projects.topologyUi)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // Material Icons Extended：Topic / Send 等扩展图标（core 集不全）。
            implementation(compose.materialIconsExtended)
            // composeResources（品牌图标 drawable + i18n JSON）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
            // stringResource（i18n 查表）来自 ui-common 的 LanguageResources。
            implementation(projects.uiCommon)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
