plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.messageInterpreterApi)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
