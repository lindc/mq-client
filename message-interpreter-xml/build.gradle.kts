plugins {
    id("mq.jvm")
}

dependencies {
    implementation(projects.messageInterpreterApi)
    testImplementation(kotlin("test"))
}
