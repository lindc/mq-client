plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // 只依赖 api 模块：解码后的链式选择经 ServiceLoader 运行时发现兄弟
            // 解释器，编译期不依赖它们（见 Base64Interpreter 的说明）。
            implementation(projects.messageInterpreterApi)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
