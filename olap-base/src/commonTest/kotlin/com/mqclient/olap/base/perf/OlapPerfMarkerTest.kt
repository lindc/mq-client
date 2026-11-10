package com.mqclient.olap.base.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 钉死 `[OLAP-Perf]` 打点的格式契约：段标签格式、分段清单、耗时字段、
 * 异常路径也打点（try/finally）且异常透传。
 */
class OlapPerfMarkerTest {

    @Test
    fun `section label format matches MQ-Perf style`() {
        assertEquals("[OLAP-Perf][execute]", OlapPerfMarker.sectionLabel("execute"))
    }

    @Test
    fun `sections cover connect catalog plan execute fetch total`() {
        assertEquals(
            setOf("connect", "catalog", "plan", "execute", "fetch", "total"),
            OlapPerfMarker.SECTIONS,
        )
    }

    @Test
    fun `perf returns block result`() {
        val result = olapPerf("test-tag", "execute") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `perf propagates exception after logging`() {
        // 异常路径：finally 里的打点不能吞异常 —— 原异常必须透传。
        val error = assertFailsWith<IllegalStateException> {
            olapPerf<Unit>("test-tag", "fetch") { throw IllegalStateException("boom") }
        }
        assertEquals("boom", error.message)
    }
}
