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
            implementation(projects.messageInterpreterApi)
        }
    }
}
