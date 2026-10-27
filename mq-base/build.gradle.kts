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
            // ServiceAdapter 在 app-core 的 jvmMain（本模块的基类因此也只能放
            // jvmMain，见 MQServiceAdapter.kt 的说明）。api 暴露：各 service
            // 编译自己的 Adapter 时要看到完整的父类链。
            api(projects.appCore)
        }
    }
}
