plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // 聚合器：api() 把契约与全部解释器实现传递给宿主（编译期可见 + 运行时
            // classpath），宿主只需声明 registry 一个依赖。新增解释器 = 新模块 +
            // settings include + 这里加一行，宿主零改动。
            api(projects.messageInterpreterApi)
            api(projects.messageInterpreterText)
            api(projects.messageInterpreterJson)
            api(projects.messageInterpreterXml)
            api(projects.messageInterpreterBase64)
            api(projects.messageInterpreterIdoc)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
