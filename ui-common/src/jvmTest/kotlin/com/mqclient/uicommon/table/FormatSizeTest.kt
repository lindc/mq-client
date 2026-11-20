package com.mqclient.uicommon.table

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [formatSize] 的单位上浮与格式契约。
 *
 * 这个函数现在是**全应用唯一**的字节格式化出口（消息大小、集群容量、
 * 节点内存共用）。改它的输出等于改四个模块的显示，所以把边界钉死在这里。
 */
class FormatSizeTest {

    @Test
    fun `bytes stay exact integers`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("512 B", formatSize(512))
        assertEquals("1023 B", formatSize(1023))
    }

    @Test
    fun `kilobytes and megabytes keep one decimal`() {
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("1.0 MB", formatSize(1_048_576))
    }

    @Test
    fun `large capacities float up to gb and tb`() {
        // 236 GB 的集群盘：旧实现（只到 MB）会输出 242148.4 MB。
        assertEquals("236.5 GB", formatSize(253_910_950_346))
        assertEquals("2.0 GB", formatSize(2_147_483_648))
        assertEquals("1.0 TB", formatSize(1_099_511_627_776))
    }

    @Test
    fun `petabyte is the top unit and does not overflow`() {
        assertEquals("1.0 PB", formatSize(1_125_899_906_842_624))
        assertEquals("8192.0 PB", formatSize(Long.MAX_VALUE))
    }
}
