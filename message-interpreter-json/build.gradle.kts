plugins {
    id("mq.jvm")
}

dependencies {
    // 只依赖 api 模块：不依赖其他解释器、不依赖任何 MQ / UI 模块。
    implementation(projects.messageInterpreterApi)
    // 严格校验用（PrettyPrint 仍走自己的字符级重排，不做值级重建）。
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
