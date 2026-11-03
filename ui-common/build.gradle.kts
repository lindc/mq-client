plugins {
    id("mq.kotlin-jvm")
    id("mq.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // app-core 的统一日志入口（Log）：失败上报走它，不再自己摸 System.err。
            implementation(projects.appCore)
            // nucleus 应用框架：ObjectDetailDialog 用其 DecoratedDialog
            // （内部把 AWT 窗口操作包到 EDT，规避 Tao 主线程非 EDT 的崩溃）。
            implementation(libs.nucleus.application)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // ✕ 关闭按钮等图标（ObjectDetailDialog 标题栏）。
            implementation(compose.materialIconsExtended)
            // nucleus 窗口原语：ObjectDetailDialog 的 windowDragArea / setMinimumSize。
            implementation(libs.nucleus.window)
            // Compose resources packaging (i18n JSON tables under
            // composeResources/files/i18n/).
            implementation(compose.components.resources)
            // JSON parsing for LanguageResources (runtime only — no
            // @Serializable classes, so no serialization compiler plugin).
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            // FileKit 文件选择器（uicommon.file.FilePickerUtil 的 actual：证书路径 /
            // 消息体导入导出等共用）。expect 侧只用 stdlib 类型，依赖只需给 actual。
            implementation(libs.filekit.dialogs)
            // 消息详情对话框（MessageDetailDialog）：解释器选择区 + 正文渲染管线。
            // registry 以 api() 传递契约（MessageInterpreter/ParamDef），实现经
            // runtimeOnly 聚合进运行时 classpath（ServiceLoader 发现）。
            implementation(projects.messageInterpreterRegistry)
        }
        // jvmTest: kotlin.test + JUnit4 runner for the i18n behaviour tests.
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}