plugins {
    id("mq.kotlin-jvm")
    id("mq.compose")
}

kotlin {
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
            // 离屏导出测试要加载 Skia 原生库（ImageComposeScene → skiko）；
            // 同 ibm-mq-ui 的 UI 测试，声明当前桌面平台的 runtime。
            implementation(compose.desktop.currentOs)
        }
    }
}

// Skiko 默认把解压后的原生库放到 ~/.skiko；CI / 沙箱的 home 可能只读。
// 测试时改放构建目录，避免离屏渲染测试因环境权限失败。
tasks.named<Test>("jvmTest") {
    systemProperty(
        "skiko.data.path",
        layout.buildDirectory.dir("skiko").get().asFile.absolutePath,
    )
}
