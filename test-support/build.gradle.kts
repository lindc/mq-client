plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

/**
 * 测试辅助件（**只给 jvmTest 用**，不进运行时 classpath）。
 *
 * 目前只有一件事：把「没有测试环境」表达成 JUnit 的 **assumption**（SKIP），
 * 而不是 `println + return`（PASSED）。两者在报告里长得完全不一样 ——
 * 前者是 skipped（一眼看出这个测试没跑），后者是 passed（永远绿，等于没测）。
 *
 * 为什么单独一个模块而不是各模块抄一份：
 *  1. 5 个 service 模块的 LiveTest / DiagTest 都要用同一套语义，抄 5 份正是本项目
 *     这一阶段在清理的那类问题；
 *  2. `app-core` 等模块的 `kotlin("test")` 只传递 kotlin-test，拿不到 `org.junit.Assume`
 *     的稳定编译面 —— 集中在这里声明一次 junit 依赖，消费方不必各自复述。
 *
 * 注意：本模块是 `jvmMain`（不是 test source set），因为 KMP 的测试源集之间不共享
 * 编译产物；只有这样各模块的 jvmTest 才能 `implementation(projects.testSupport)`。
 */
kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        jvmMain.dependencies {
            // JUnit4 的 Assume（本项目测试跑在 JUnit4 上，见各模块的 kotlin("test")）。
            implementation(libs.junit)
        }
    }
}
