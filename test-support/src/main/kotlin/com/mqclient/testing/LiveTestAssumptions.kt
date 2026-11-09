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

/**
 * 建立了连接才算「有测试环境」：**连不上就 SKIP，而不是 FAIL**。
 *
 * 真机用例的职责是验证「这条链路在真实环境下的行为」，不是验证「环境本身活着」。
 * 环境没起来（容器停了 / 端口没发布 / 防火墙挡了）属于前置条件不满足，报告里应该
 * 显示 skipped —— 否则 `scripts/verify.sh` 会红，而代码一行没错，门禁就失去了
 * 「只反映代码问题」这个唯一的可信度。
 *
 * ## 为什么必须抽出来
 *
 * 项目里原来有 4 处直接 `factory.newConnection()`（RabbitMQ 心跳 / 线程命名 /
 * 会话泄漏两条），broker 一停就把全量门禁弄红，而同一批文件里的其它用例都会 SKIP。
 * 同一个语义散落成两种行为，读代码的人无从判断哪个才是约定。
 *
 * ## 用法与纪律
 *
 * ```kotlin
 * val connection = assumeConnected("RabbitMQ") { factory.newConnection() }
 * ```
 *
 * - `AssumptionViolatedException` 会原样穿出（不被兜住）—— 否则 assumption 又变成
 *   PASSED，正是 [assumeLive] 的 KDoc 里警告的那种失败；
 * - 只吞掉**建立连接**这一步的异常。连上之后的断言失败仍然是 FAIL：那些才是真缺陷。
 * - [block] 里的阻塞调用请在 IO 上执行（本函数不切换调度器，不替调用方猜）。
 */
inline fun <T : Any> assumeConnected(
    target: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (assumption: org.junit.internal.AssumptionViolatedException) {
        // 兜底里如果又调用了一个 assumption，必须让它穿出去，不能吞。
        throw assumption
    } catch (error: Throwable) {
        assumeLive(
            "LIVE SKIP: $target 连接失败（环境未就绪）：" +
                "${error::class.simpleName}: ${error.message ?: "-"}",
            false,
        )
        // assumption 已经抛过异常；这行只为满足类型推断（不会执行）。
        throw error
    }
