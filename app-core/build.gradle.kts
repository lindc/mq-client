plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // Only the Compose runtime is needed for the @Composable contract.
            implementation(libs.compose.runtime)
            // Serialization for Config + ConnectionInfo (persisted as JSON).
            implementation(libs.kotlinx.serialization.json)
            // okio for ConfigManager's file reads / atomic writes.
            implementation(libs.okio)
            // SQLDelight runtime is part of app-core's public API: DriverFactory
            // and DatabaseManager expose SqlDriver to consumer modules, so the
            // types must be visible on their compile classpath.
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.coroutines)
        }
        jvmMain.dependencies {
            // JDBC SQLite driver (JdbcSqliteDriver) — desktop only.
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

// app-core deliberately defines NO tables/databases: every module owns its
// own .sq files + generated Database (all sharing one SQLite file). The
// plugin is applied here only so its version is aligned for downstream
// modules that must apply it themselves.