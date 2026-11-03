// 需要 SQLDelight Gradle 插件的模块底座（具体的 database 定义仍在各模块自己的
// `sqldelight { databases { ... } }` 块里，这里只负责把插件接上）。
plugins {
    id("mq.kotlin-jvm")
    id("app.cash.sqldelight")
}
