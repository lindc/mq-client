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
            implementation(compose.ui)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            // kuiver 图引擎：渲染层直接面对它的 viewer / edge API。
            implementation(libs.kuiver)
            // 图模型契约（TopologyNodeType / NodeSeverity / TopologyData）。
            api(projects.graphCore)
            // TopologyZoomControls 的动作文案走公共 stringResource。
            implementation(projects.uiCommon)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // 真实 Compose/Skia 运行时，用于验证 GraphicsLayer 截图 + PNG 编码。
            implementation(projects.uiTestKit)
            implementation(compose.desktop.currentOs)
        }
    }
}
