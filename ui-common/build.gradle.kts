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
            // app-core 的统一日志入口（Log）：失败上报走它，不再自己摸 System.err。
            implementation(projects.appCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // Compose resources packaging (i18n JSON tables under
            // composeResources/files/i18n/).
            implementation(compose.components.resources)
            // JSON parsing for LanguageResources (runtime only — no
            // @Serializable classes, so no serialization compiler plugin).
            implementation(libs.kotlinx.serialization.json)
        }
        // jvmTest: kotlin.test + JUnit4 runner for the i18n behaviour tests.
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}