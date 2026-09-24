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
            // 模型 / 接口（CommandResult / CommandExecutor / SyntaxHighlighter…）是
            // ibm-mq-service 等实现方的编译面，走 api 暴露：协程与 compose.ui
            // （AnnotatedString 所在）都必须传递到实现方。
            api(libs.kotlinx.coroutines.core)
            api(compose.ui)
            // stringResource / LocalSnackbarHostState 等 UI 公共设施。
            implementation(projects.uiCommon)
            // 对象列表组件用到的 app-core 类型（ContextMenuItem / CoreModule，
            // 均在 jvmMain 源集 —— 见 objectlist 包的说明）。
            implementation(projects.appCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
        }
        jvmMain.dependencies {
            // FileKit Compose 文件选择器（「打开文件」/「导出结果」按钮）。
            implementation(libs.filekit.dialogs)
            implementation(libs.filekit.dialogs.compose)
        }
    }
}
