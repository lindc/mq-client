plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // MQ 公共基类（MQServiceAdapter）：api 暴露，消费方能看到完整父类链。
            api(projects.mqBase)
        }
    }
}
