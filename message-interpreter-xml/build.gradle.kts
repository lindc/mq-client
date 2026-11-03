plugins {
    id("mq.kotlin-jvm")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.messageInterpreterApi)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
