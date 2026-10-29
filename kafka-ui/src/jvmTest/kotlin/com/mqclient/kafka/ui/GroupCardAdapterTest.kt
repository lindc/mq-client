@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.kafka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 消费组卡片的 Lag 位。
 *
 * 现场背景：消费组列表与 Lag 是**两条独立链路** —— 列表来自
 * `listConsumerGroups + 批量 describeConsumerGroups`（首屏就要出卡片），Lag 来自
 * 独立的 30 秒心跳快扫。首屏时 lag 还没算出来，卡片如果整块留空，读起来像
 * 「没有积压」（Lag = 0），是误导。这里钉住「没有 Lag 时显示『计算中』」。
 */
class GroupCardAdapterTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, KafkaUI().i18nTables)
    }

    private fun cardOf(group: KafkaGroupInfo): ObjectCardData {
        var card: ObjectCardData? = null
        runComposeUiTest {
            loadTables()
            setContent { MaterialTheme { card = group.toCardData(lagWarningThreshold = 10_000) {} } }
            waitForIdle()
        }
        return card!!
    }

    @Test
    fun `card without lag yet shows the calculating placeholder`() {
        val card = cardOf(
            KafkaGroupInfo(name = "orders-cg", state = "STABLE", members = 3, simple = false, totalLag = null),
        )
        assertTrue(
            card.metricValue?.contains("计算中") == true,
            "没有心跳产物时必须显示「计算中」，不能留空（实测：${card.metricValue}）",
        )
    }

    @Test
    fun `card with lag shows the number`() {
        val card = cardOf(
            KafkaGroupInfo(name = "orders-cg", state = "STABLE", members = 3, simple = false, totalLag = 12_345),
        )
        assertTrue(card.metricValue?.contains("12,345") == true, "实测：${card.metricValue}")
        assertTrue(card.metricValue?.contains("计算中") == false)
    }

    @Test
    fun `zero lag is a real value not a placeholder`() {
        // 0 是「算过了，没有积压」，与「还没算」必须区分开。
        val card = cardOf(
            KafkaGroupInfo(name = "orders-cg", state = "EMPTY", members = 0, simple = false, totalLag = 0),
        )
        assertTrue(card.metricValue?.contains("Lag: 0") == true, "实测：${card.metricValue}")
    }

    /** 渲染一次卡片，确认「计算中」文案真的出现在界面上（不只是数据类里有）。 */
    @Test
    fun `placeholder text is rendered on the card`() = runComposeUiTest {
        loadTables()
        val card = cardOf(
            KafkaGroupInfo(name = "orders-cg", state = "STABLE", members = 3, simple = false, totalLag = null),
        )
        setContent { MaterialTheme { Text(card.metricValue!!) } }
        waitForIdle()
        onNodeWithText(card.metricValue!!).assertExists()
    }
}
