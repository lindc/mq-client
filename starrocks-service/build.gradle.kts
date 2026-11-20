plugins {
    id("mq.kotlin-jvm")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // OLAP 模板层：分类常量（CATEGORY / LOG_TAG_PREFIX / PERF_TAG_PREFIX）
            // + 连接 / 目录 / 查询模板。api 暴露：下游 starrocks-ui 与未来协议实现
            // 要看到完整的父类链（与 mq-base 对 app-core 的口径一致）。
            api(projects.olapBase)
            // ServiceAdapter / ServiceContext 等 SPI 契约在 app-core（jvmMain）。
            implementation(projects.appCore)
        }
        jvmMain.dependencies {
            // S1：StarRocks FE 走 MySQL 协议，用 Connector/J 8.0.x 做真实
            // testConnection / healthCheck。只在 jvmMain 可见 —— commonMain
            // 的模板层与 commonTest 的 FakeJdbcConnector 都不认识 java.sql。
            // 8.0.33：8.0.x 末代稳定版，支持 connectionTimeZone / connectTimeout。
            implementation("com.mysql:mysql-connector-j:8.0.33")
            // SSH 隧道：生产环境 FE 一般不直接暴露 9030，需经堡垒机转发。
            // 只在 jvmMain —— commonMain 的模板层不认识 JSch（与 JDBC 同一隔离口径）。
            implementation(libs.jsch)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // commonTest 的 runTest 也需要协程测试库（与 olap-base 同口径）。
            implementation(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    // 真机用例（StarRocksSystemLiveTest）的开关透传：命令行 -Dstarrocks.live.*=...
    // 原样传给测试 JVM（与 kafka-service 的 kafkadiag.* 同一做法）；
    // 默认一个都不设 → 用例自己跳过，日常 jvmTest 不触网、也不要求本地有 StarRocks。
    System.getProperties()
        .filterKeys { it is String && it.startsWith("starrocks.live.") }
        .forEach { (key, value) -> systemProperty(key.toString(), value.toString()) }
}
