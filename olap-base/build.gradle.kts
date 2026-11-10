plugins {
    id("mq.kotlin-jvm")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // 模板层的全部基类都吃 app-core 的公共契约（ServiceContext / EventBus /
            // Log / ServiceException）。api 暴露：未来 olap-service 编译自己的子类时
            // 要看到完整的父类签名链（与 mq-base 对 app-core 的口径一致）。
            api(projects.appCore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // runTest（协程测试标准入口）。
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
