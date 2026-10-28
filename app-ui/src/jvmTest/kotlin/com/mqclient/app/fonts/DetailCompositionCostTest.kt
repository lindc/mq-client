@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.app.fonts

import androidx.compose.material3.Text
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.core.FONT_LXGW_WEN_KAI_MONO
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 诊断：**「新窗口 = 新 Composition」要不要把 25.6 MB 字体重新付一遍**。
 *
 * ## 为什么问这个
 *
 * 现场：拉消息很快，但每开一个消息详情弹窗要 40s，第二个 60s+，而且**递增**。
 * 纯本地渲染（解码 + 解释器）实测是毫秒级，锁也排除了，Skia 解析这个字体首次
 * 204ms、之后 17ms —— 都是小数。剩下唯一能解释「递增」的形态是：
 * **每新建一个窗口（= 一段新的 Composition）就把字体资源从磁盘/classpath 重读一遍，
 * 而且旧的没释放** → 堆越堆越高，每开一次就更慢。
 *
 * `rememberAppFontFamily` 的 `FONT_LXGW_WEN_KAI_MONO` 分支正是「每次 composition 都
 * 新建 `FontFamily(ResourceFont(...))`」，而 `remember` 的槽位是**按 Composition 分
 * 的** —— 新窗口拿不到主窗口那份。
 *
 * ## 本测试怎么问
 *
 * `runComposeUiTest` 每次调用创建一段**独立的** Composition（等价于一个新窗口的根）。
 * 连续跑 3 次，每次都只做「解析字体 + 画一行字」，并设两个**真实阈值**：
 *
 *  - 每次都能在 5s 内完成（卡死/抛异常即失败）；
 *  - 第 3 次不比第 2 次慢 5 倍以上 —— 拦的是“每开一个窗口就累积一层”的退化。
 *
 * 这条是守卫，不是表演：历史上的 40 秒卡顿已经查明与字体/组合无关（是渲染被拆到
 * 第二个 effect、要等下一帧，而 Tao 只在原生事件时派帧）。留一条下限，以后有人改
 * 字体解析或全局 Theme 时能第一时间发现问题。
 */
class DetailCompositionCostTest {

    @Test
    fun `measure per-composition font resolution cost`() {
        println("=== PER-COMPOSITION FONT COST ===")
        val samples = mutableListOf<Long>()
        repeat(3) { index ->
            val ns = measureNanos {
                runComposeUiTest {
                    setContent {
                        val family = rememberAppFontFamily(FONT_LXGW_WEN_KAI_MONO)
                        Text(text = "详情", fontFamily = family)
                    }
                    waitForIdle()
                }
            }
            samples += ns
            println("  composition #${index + 1} = ${ms(ns)} ms")
        }
        println("  samples = ${samples.map { ms(it) }}")
        println("=== END ===")

        // 只固定两条：每次 Composition 都必须完成，且不得逐次变慢。
        assertTrue(
            samples.all { it < TimeUnit.SECONDS.toNanos(5) },
            "每次 composition 都应在 5s 内完成：${samples.map { ms(it) }}",
        )
        assertTrue(
            samples[2] < samples[1] * 5,
            "第 3 次比第 2 次慢太多（${ms(samples[1])} → ${ms(samples[2])} ms）—— " +
                "每次新窗口都在累积成本，这正是「越开越慢」的形态。",
        )
    }

    private inline fun measureNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun ms(ns: Long): String = "%.1f".format(TimeUnit.NANOSECONDS.toMicros(ns) / 1000.0)
}
