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
    }
}
