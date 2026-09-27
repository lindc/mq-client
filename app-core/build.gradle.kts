plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // 拓扑通用数据模型（graph-core：点/边/图，纯数据、零业务依赖）。
            // TopologyProvider 契约放在本模块，复用其 coroutines / serialization 能力。
            implementation(projects.graphCore)

            // Only the Compose runtime is needed for the @Composable contract.
            implementation(libs.compose.runtime)
            // Painter（ModuleIcon SPI 的返回类型）在 ui-graphics 里，business 模块经
            // SPI 拿到的是这个类型；app-core 仍然不引 foundation / material。
            implementation(libs.compose.ui.graphics)
            // 事件总线 (core/event/EventBus.kt) 的公开签名里有 CoroutineScope / Job /
            // SharedFlow, 必须 api 暴露, 否则 app-ui 与各业务模块看不到这些类型。
            api(libs.kotlinx.coroutines.core)
            // Serialization for Config + ConnectionInfo (persisted as JSON).
            implementation(libs.kotlinx.serialization.json)
            // okio for ConfigManager's file reads / atomic writes.
            implementation(libs.okio)
            // SQLDelight runtime is part of app-core's public API: DriverFactory
            // and DatabaseManager expose SqlDriver to consumer modules, so the
            // types must be visible on their compile classpath.
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.coroutines)
        }
        jvmMain.dependencies {
            // JDBC SQLite driver (JdbcSqliteDriver) — desktop only.
            implementation(libs.sqldelight.sqlite.driver)
            // Ktoml — config.toml 的 TOML 编解码（ConfigManager）。
            implementation(libs.ktoml.core)
            implementation(libs.ktoml.file)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// mq_connection 表（连接历史快照，供 operation_history 关联查询）：app-core 目前唯一的表。
// 写入走事件（`core.mq.connection.snapshot.created`，payload 由各 MQ 模块组装、
// MqConnectionSnapshotHandler 落库），读走 MqConnectionSnapshotRepository。
// 各业务模块的表仍归各模块自己的 .sq（见 ai-service 等），与 core 共享同一个 SQLite 文件。
sqldelight {
    databases {
        create("CoreDatabase") {
            packageName.set("com.mqclient.core.db")
        }
    }
}