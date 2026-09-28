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
            implementation(projects.graphCore)
            // Kuiver — 有向图可视化（KuiverViewer）：布局/缩放/平移/拖拽由它承担。
            implementation(libs.kuiver)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // Material Icons Extended：节点图标由各 *-ui 模块登记（ImageVector），
            // 这里只引用 core 里的 Icons.Filled.Circle 作兜底，不强制依赖 extended。
            implementation(compose.materialIconsExtended)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
