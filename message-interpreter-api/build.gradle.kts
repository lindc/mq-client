plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    // 零依赖：接口与数据类型只用 kotlin-stdlib + JDK（Charset / ServiceLoader 约定）。
    // 不依赖 UI 框架、不依赖任何 MQ 模块、不依赖任何解释器实现。
}
