package com.mqclient.app.settings

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 覆盖设置对话框的关闭拦截状态机（[SettingsCloseGuard]）：干净直接关、脏必弹确认、
 * 弹窗三个出口各自的收尾，以及反复触发时不丢状态。UI 只是把这些状态接线到
 * ESC / ✕ / 取消 / Alt+F4 上，决策全部在这里。
 */
class SettingsCloseGuardTest {

    private val closes = mutableListOf<Int>()
    private val guard = SettingsCloseGuard(onClose = { closes += closes.size + 1 })

    @BeforeTest
    fun setUp() {
        closes.clear()
    }

    // ------------------------------------------------------------- requestClose

    @Test
    fun `clean draft closes immediately without confirmation`() {
        guard.requestClose(isDirty = false)

        assertEquals(listOf(1), closes, "干净草稿直接关窗，不弹确认")
        assertFalse(guard.confirmingClose)
    }

    @Test
    fun `dirty draft shows confirmation and never closes by itself`() {
        guard.requestClose(isDirty = true)

        assertTrue(guard.confirmingClose, "脏草稿必须弹确认")
        assertTrue(closes.isEmpty(), "弹确认期间绝不能关窗 —— 关不关由用户决定")
    }

    @Test
    fun `repeated close requests keep the confirmation instead of stacking closes`() {
        guard.requestClose(isDirty = true)
        guard.requestClose(isDirty = true)

        assertTrue(guard.confirmingClose, "重复触发不该抖出多余状态")
        assertTrue(closes.isEmpty())
    }

    // ------------------------------------------------------------- 弹窗三个出口

    @Test
    fun `keep editing hides confirmation and stays open`() {
        guard.requestClose(isDirty = true)

        guard.keepEditing()

        assertFalse(guard.confirmingClose)
        assertTrue(closes.isEmpty(), "继续编辑不该关窗")
    }

    @Test
    fun `discard hides confirmation and closes`() {
        guard.requestClose(isDirty = true)

        guard.discardChanges()

        assertFalse(guard.confirmingClose)
        assertEquals(listOf(1), closes)
    }

    @Test
    fun `save succeeded hides confirmation and closes`() {
        guard.requestClose(isDirty = true)

        guard.saveSucceeded()

        assertFalse(guard.confirmingClose)
        assertEquals(listOf(1), closes)
    }

    @Test
    fun `save failed hides confirmation but keeps the dialog open`() {
        guard.requestClose(isDirty = true)

        guard.saveFailed()

        assertFalse(guard.confirmingClose, "确认弹窗收起，落盘错误回设置界面展示")
        assertTrue(closes.isEmpty(), "保存失败不能把用户的编辑连同对话框一起关掉")
    }

    // ------------------------------------------------------------- 反复进出

    @Test
    fun `confirmation can reappear after keep editing then closing again`() {
        guard.requestClose(isDirty = true)
        guard.keepEditing()

        guard.requestClose(isDirty = true)

        assertTrue(guard.confirmingClose, "继续编辑后再关，确认要能重新弹出来")
        guard.discardChanges()
        assertEquals(listOf(1), closes, "每次确认只对应一次关窗")
    }

    @Test
    fun `after a successful save a clean draft closes directly`() {
        guard.requestClose(isDirty = true)
        guard.saveSucceeded()

        // 保存成功后 caller 把落盘配置传回来，draft == initial → isDirty = false。
        guard.requestClose(isDirty = false)

        assertEquals(listOf(1, 2), closes, "先确认后关闭各一次，干净草稿直接放行")
        assertFalse(guard.confirmingClose)
    }
}
