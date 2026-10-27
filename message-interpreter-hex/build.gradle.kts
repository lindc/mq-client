plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        commonMain.dependencies {
            // 只依赖 api 模块：不依赖其他解释器、不依赖任何 MQ / UI 模块。
            implementation(projects.messageInterpreterApi)
        }
    }
}
