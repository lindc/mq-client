plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // Just for the AiProvider SPI contract — no UI deps needed.
            implementation(projects.appCore)
        }
    }
}