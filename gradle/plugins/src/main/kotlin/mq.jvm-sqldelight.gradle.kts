// 需要 SQLDelight Gradle 插件的纯 JVM 模块底座（database 定义仍在模块自己的
// `sqldelight { databases { ... } }` 块里，这里只负责把插件接上）。
plugins {
    id("mq.jvm")
    id("app.cash.sqldelight")
}
