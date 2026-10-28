package com.mqclient.app.fonts

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 诊断：捆绑字体（`lxgw_wen_kai_mono_regular.ttf`，**25.6 MB CJK**）的解析代价。
 *
 * ## 为什么怀疑它
 *
 * 现场：拉消息很快（AMQP 正常），但**打开单个消息详情**第一次 40s、第二次 60s+，
 * 而且递增。详情框不碰网络、正文解码实测毫秒级（`MessageDetailLocalCostTest`）：
 * 剩下的可疑项是「每新建一个窗口就重新付一次的东西」。字体是头号嫌疑人 ——
 * 默认配置用的正是这个 25.6 MB 的 CJK 字体，而 `rememberAppFontFamily` 在
 * `FONT_LXGW_WEN_KAI_MONO` 分支里**每次 composition 都新建 `FontFamily(ResourceFont(...))`**
 * （注释只说 FontFamily 是薄包装、重建便宜，没提 Font 资源读字节那一步）。
 *
 * ## 本测试量什么
 *
 * 直接用 Skia（`FontMgr.makeFromData`）解析真实字体文件，并设两个**真实阈值**：
 *
 *  - 单次解析 < 2s；
 *  - 连读 5 次的总耗时 < 5s（即没有“每开一个窗口就更慢”那种累积）。
 *
 * 这条是守卫，不是表演：它拦的是「有人把更大的字体拷进 `composeResources`」与「字体
 * 缓存被改坏」两种回归。历史上的 40 秒卡顿已经查明与字体无关（见本次提交的说明），
 * 但正因为查过一次，值得留一条下限。
 */
class BundledFontCostTest {

    private val fontFile: java.io.File =
        java.io.File("src/commonMain/composeResources/font/lxgw_wen_kai_mono_regular.ttf")

    @Test
    fun `measure skia typeface parsing cost for the bundled font`() {
        assertTrue(
            fontFile.isFile,
            "测试的工作目录里找不到字体文件：${fontFile.absolutePath}（jvmTest 的 cwd 通常是模块根）",
        )
        val bytes = fontFile.readBytes()
        println("=== BUNDLED FONT COST ===")
        println("  file   = ${fontFile.name}")
        println("  size   = ${"%.1f".format(bytes.size / 1024.0 / 1024.0)} MB")

        // 读盘一次（会被 OS page cache 加速，不代表首次冷读）。
        val readNs = measureNanos { fontFile.readBytes() }
        println("  read() = ${ms(readNs)} ms")

        // 反复解析：每次都是全新的 Skia typeface，模拟「每个新窗口都重来一遍」。
        val rounds = 5
        val samples = mutableListOf<Long>()
        repeat(rounds) { index ->
            val ns = measureNanos {
                val typeface = org.jetbrains.skia.FontMgr.default.makeFromData(
                    org.jetbrains.skia.Data.makeFromBytes(bytes),
                    0,
                )
                assertTrue(typeface != null, "Skia 无法解析该字体")
            }
            samples += ns
            println("  parse #${index + 1} = ${ms(ns)} ms")
        }
        println("  samples = ${samples.map { ms(it) }}")
        println("=== END ===")

        assertTrue(
            samples.first() < TimeUnit.SECONDS.toNanos(2),
            "单次字体解析不应超过 2s（实测 ${ms(samples.first())} ms）—— 真出现说明字体体积或解析方式退化了",
        )
        assertTrue(
            TimeUnit.NANOSECONDS.toMillis(samples.sum()) < 5_000,
            "连读 $rounds 次的总耗时应 < 5s（实测 ${ms(samples.sum())} ms）—— 超出即说明每次都在重新付出全额代价",
        )
    }

    private inline fun measureNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun ms(ns: Long): String = "%.1f".format(TimeUnit.NANOSECONDS.toMicros(ns) / 1000.0)
}
