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
            api(projects.appCore)
        }
        jvmMain.dependencies {
            // S1：StarRocks FE 走 MySQL 协议，用 Connector/J 8.0.x 做真实
            // testConnection / healthCheck。只在 jvmMain 可见 —— commonMain
            // 的模板层与 commonTest 的 FakeJdbcConnector 都不认识 java.sql。
            // 8.0.33：8.0.x 末代稳定版，支持 connectionTimeZone / connectTimeout。
            implementation("com.mysql:mysql-connector-j:8.0.33")
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
