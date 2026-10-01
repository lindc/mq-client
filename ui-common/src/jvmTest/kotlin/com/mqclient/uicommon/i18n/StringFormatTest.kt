package com.mqclient.uicommon.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [withArg] 占位符替换规约测试。用例取自项目真实文案模板的形状：
 *
 *  - 裸 `%s` 单参（ibm_mq 表 44 个键）；
 *  - 裸 `%d` 单参（strings 表 console_* 键）；
 *  - 位置式 `%1$s`（`channel_type_unknown_raw` 等 2 个键）；
 *  - 位置式 `%1$d`（`test_result_duration`）；
 *  - 多参顺序式裸 `%s`（msg_browse_* / msg_send_* 4 个键）；
 *  - 混用与透传边界。
 */
class StringFormatTest {

    @Test
    fun `bare %s single arg`() {
        assertEquals("队列加载失败：boom", "队列加载失败：%s".withArg("boom"))
    }

    @Test
    fun `bare %d single arg`() {
        assertEquals("共 5 条命令", "共 %d 条命令".withArg(5))
    }

    @Test
    fun `positional 1s`() {
        assertEquals("原始值 XXX", "原始值 %1\$s".withArg("XXX"))
    }

    @Test
    fun `positional 1d`() {
        assertEquals("耗时 42 ms", "耗时 %1\$d ms".withArg(42))
    }

    @Test
    fun `multiple bare %s take args in order`() {
        // 真实模板：msg_browse_total_loaded
        assertEquals("共 100 条（已加载 50 条）", "共 %s 条（已加载 %s 条）".withArg(100, 50))
        // 真实模板：msg_send_summary（三参）
        assertEquals("共 10 条，成功 8 条，失败 2 条", "共 %s 条，成功 %s 条，失败 %s 条".withArg(10, 8, 2))
    }

    @Test
    fun `positional and bare mixed`() {
        assertEquals("a-b 5", "%1\$s-%2\$s %s".withArg("a", "b", 5))
    }

    @Test
    fun `template without placeholder is returned unchanged`() {
        assertEquals("刷新", "刷新".withArg(1))
        assertEquals("刷新", "刷新".withArg())
    }

    @Test
    fun `arg containing percent literal is not re-substituted`() {
        // 参数值里的 %s / %d 不能被后续替换命中（阈值百分比等场景）。
        assertEquals("阈值 85% 已达", "阈值 %s 已达".withArg("85%"))
        assertEquals("说明: 50%d", "说明: %s".withArg("50%d"))
    }

    @Test
    fun `non string args use toString`() {
        assertEquals("错误码: 2085", "错误码: %s".withArg(2085))
    }
}
