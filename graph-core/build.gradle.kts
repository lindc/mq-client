plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // 仅用于 @Serializable：拓扑数据模型设计为可序列化（语义枚举 + 纯数据类），
            // 不引入任何 Compose / Swing / 具体 MQ 客户端依赖。
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
