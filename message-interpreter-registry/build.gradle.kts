plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // 契约 api() 传递给宿主（UI 渲染参数区需要 MessageInterpreter/ParamDef 类型可见）。
            api(projects.messageInterpreterApi)
        }
        jvmMain.dependencies {
            // 聚合器只保证解释器实现出现在【运行时】classpath（ServiceLoader 发现），
            // 编译期不可见 —— 宿主只认 api 的契约与 registry 的注册表，新增解释器
            // 对 UI / 宿主零改动。
            // 新增解释器 = 新模块 + settings include + 这里加一行 runtimeOnly。
            runtimeOnly(projects.messageInterpreterText)
            runtimeOnly(projects.messageInterpreterJson)
            runtimeOnly(projects.messageInterpreterXml)
            runtimeOnly(projects.messageInterpreterBase64)
            runtimeOnly(projects.messageInterpreterIdoc)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // i18nTables 的 reader 是 suspend（与 ModuleI18nTable 同形）——测试里 runBlocking 读资源。
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
