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
            // 新建连接表单（ConnectionFormUi 实现）用本模块对应的 service：
            // IbmMQConfig / IbmMQModule（testConnection）都在那里。
            implementation(projects.ibmMqService)
            // Kuiver：MQ 专属固定列拓扑面板（ui.topology.TopologyPanel）直接渲染图模型。
            implementation(libs.kuiver)
            // 通用命令控制台 UI（CommandConsolePanel 等）已抽到 mq-ui，本模块只做注入组装。
            implementation(projects.mqUi)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // composeResources（品牌图标 drawable）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
            // Material Icons Extended：密码可见性切换等小图标，跨平台（Linux 无 Emoji 字体）一致。
            implementation(compose.materialIconsExtended)
            // 表单字段组件（LabeledField / PressableButton / SettingsDropdownRow）与 i18n。
            implementation(projects.uiCommon)
            // 消息解释器 SPI（api）+ 独立解释器模块：进运行时 classpath 供 ServiceLoader 发现。
            implementation(projects.messageInterpreterApi)
            implementation(projects.messageInterpreterJson)
            implementation(projects.messageInterpreterBase64)
        }
        jvmMain.dependencies {
            // FileKit 文件选择器（证书路径的「浏览…」按钮）。选择器 API 在 dialogs 模块。
            implementation(libs.filekit.dialogs)
            // FileKit Compose 文件选择器（命令控制台的「打开文件」/「导出结果」按钮）。
            implementation(libs.filekit.dialogs.compose)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
