plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
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
        }
    }
}