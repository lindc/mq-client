plugins {
    id("mq.kotlin-jvm")
}

kotlin {
    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        commonMain.dependencies {
            implementation(projects.messageInterpreterApi)
        }
    }
}
