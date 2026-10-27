package com.mqclient.testing

import org.junit.Assume.assumeTrue

/**
 * 「当前没有测试环境」的统一声明方式（JUnit assumption → 报告里显示 **skipped**）。
 *
 * 为什么不能用老的 `println("SKIP: ..."); return`：
 *  - `return` 让测试正常结束 → JUnit 记为 **passed**。CI 上看起来一片绿，实际上这些
 *    LiveTest / DiagTest 一次都没跑过 —— 假绿（false green）比失败更危险，因为它
 *    让人相信「真机链路是通的」；
 *  - assumption 抛 `AssumptionViolatedException`，JUnit 记 **skipped**，并且
 *    Gradle 的 `--tests` / 报告里能直接数出来「有几个没跑」。
 *
 * 使用纪律（很重要）：
 *  1. **不要在 `catch (Throwable)` 里调用**，也不要在它上层的 try 里调用 ——
 *     `AssumptionViolatedException` 是 `RuntimeException`，宽 catch 会把它吞掉，
 *     于是又变回 PASSED。要么放在 try 之前，要么在 catch 里先 `if (e is
 *     AssumptionViolatedException) throw e`；
 *  2. assumption 不改变 Kotlin 的**可空性**推断（`if (x == null) return` 之后
 *     `x` 会 smart-cast，`assumeTrue(x != null)` 不会）。需要非空值时用
 *     [assumeNotNull]，它同时完成「断言非空」与「返回非空引用」。
 */

/** 条件不满足时跳过当前测试（`message` 会出现在报告里）。 */
fun assumeLive(message: String, condition: Boolean) {
    assumeTrue(message, condition)
}

/**
 * 断言 [value] 非空并把它当作非空返回；为空则跳过当前测试。
 *
 * 替代 `if (x == null) { println(...); return }; use(x)` 这种旧写法 ——
 * 旧写法既把测试记成 PASSED，又依赖 `return` 的 smart-cast。
 */
fun <T : Any> assumeNotNull(message: String, value: T?): T {
    assumeTrue(message, value != null)
    // assumption 已保证非空；这里的兜底只为满足编译器（不会被执行）。
    return value ?: throw AssertionError("unreachable: assumption passed but value was null")
}
