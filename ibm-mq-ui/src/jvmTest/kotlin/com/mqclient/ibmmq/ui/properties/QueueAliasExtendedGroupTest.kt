package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「扩展」分组对齐 MQ Explorer 的离线回归：用真机 alias 快照的形状
 * （QMGR 对 alias 的默认应答 + 显式请求的 DEFREADA / DEFPRESP）走
 * [resolveQueuePropertyGroups]，再拿真实生成的 `ibm_mq_attrs_*` 表
 * （scripts/gen_mq_attr_display.py 产物）模拟 stringResource 的查表链，
 * 断言最终显示文本与 MQ Explorer 一致。
 */
class QueueAliasExtendedGroupTest {

    private fun attrsTable(lang: String): Map<String, String> {
        val path = "src/commonMain/composeResources/files/i18n/ibm_mq_attrs_$lang.json"
        val text = locate(path).readText()
        return Regex("\"([^\"]+)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** 兼容从仓库根或模块目录起跑两种工作目录。 */
    private fun locate(relative: String): File =
        sequenceOf(File(relative), File("ibm-mq-ui/$relative"))
            .first { it.isFile }

    /** 真机 alias 快照形状：QMGR 默认应答 + 显式请求的两个 attr。 */
    private fun aliasSnapshot() = MqObjectSnapshot(
        name = "ALIAS.RQ.QM2",
        type = MqObjectType.QUEUE,
        attributes = mapOf(
            "MQCA_Q_NAME" to "ALIAS.RQ.QM2",
            "MQCA_Q_DESC" to "",
            "MQIA_Q_TYPE" to 3, // MQQT_ALIAS
            "MQIA_DEF_READ_AHEAD" to 0, // MQREADA_NO
            "MQIA_DEF_PUT_RESPONSE_TYPE" to 1, // MQPRT_SYNC_RESPONSE
            "MQIA_PROPERTY_CONTROL" to 0, // MQPROP_COMPATIBILITY
            "MQCA_CUSTOM" to "",
            "MQIA_DEF_BIND" to 0, // MQBND_BIND_ON_OPEN —— alias 不应显示
        ),
    )

    @Test
    fun `alias extended group matches mq explorer`() {
        val zh = attrsTable("zh-CN")
        val en = attrsTable("en-US")
        val snapshot = aliasSnapshot()

        val groups = resolveQueuePropertyGroups(snapshot)
        fun displayRow(nameKey: String, table: Map<String, String>): String? {
            val row = groups.flatMap { it.second }
                .firstOrNull { it.nameKey == nameKey || it.labelEn == nameKey }
                ?: return null
            // 复刻 PropertyRowLocalized 的查表链：策展键 → <常量>.title 兜底 → 原始值。
            val value = listOfNotNull(row.valueKey, row.valueFallbackKey)
                .map { it to (table[it] ?: it) }
                .firstOrNull { (k, v) -> v != k }?.second
                ?: row.rawValue
            val name = table["${row.rawConstName}.title"]
            return "$name=$value"
        }

        // 值与 MQ Explorer 的 Extended 页逐项对齐（zh 表为准；显示为「常量title=值title」）。
        assertEquals("否=否", displayRow("Default read ahead", zh))
        assertEquals("同步=同步", displayRow("Default put response type", zh))
        assertEquals("兼容性=兼容性", displayRow("Property control", zh))
        assertEquals(null, displayRow("Default binding", zh)) // alias 隐藏

        // 英文表同链路。
        assertEquals("No=No", displayRow("Default read ahead", en))
        assertEquals("Synchronous=Synchronous", displayRow("Default put response type", en))
        assertEquals("Compatibility=Compatibility", displayRow("Property control", en))
    }

    @Test
    fun `scope resolves to queue manager not version 1`() {
        val zh = attrsTable("zh-CN")
        val snapshot = aliasSnapshot().let {
            it.copy(attributes = it.attributes + ("MQIA_SCOPE" to 1))
        }
        val row = resolveQueuePropertyGroups(snapshot).flatMap { it.second }
            .firstOrNull { it.labelEn == "Scope" }
        val value = row?.let { r ->
            listOfNotNull(r.valueKey, r.valueFallbackKey)
                .map { k -> k to (zh[k] ?: k) }
                .firstOrNull { (k, v) -> v != k }?.second
                ?: r.rawValue
        }
        assertEquals("队列管理器", value)
        assertFalse(row?.valueFallbackKey?.contains("VERSION") == true)
    }

    @Test
    fun `accessing processes shows none when no handles hidden for remote`() {
        val local = resolveQueuePropertyGroups(
            MqObjectSnapshot(
                name = "ORDER.AUDIT.Q",
                type = MqObjectType.QUEUE,
                attributes = mapOf("MQCA_Q_NAME" to "ORDER.AUDIT.Q", "MQIA_Q_TYPE" to 1),
            ),
        ).flatMap { it.second }.firstOrNull { it.labelEn == "Accessing processes" }
        assertEquals("queue_value_none", local?.valueKey)

        val remoteHidden = resolveQueuePropertyGroups(
            MqObjectSnapshot(
                name = "BATCH.TO.QM3",
                type = MqObjectType.QUEUE,
                attributes = mapOf("MQCA_Q_NAME" to "BATCH.TO.QM3", "MQIA_Q_TYPE" to 6),
            ),
        ).flatMap { it.second }.none { it.labelEn == "Accessing processes" }
        assertTrue(remoteHidden)
    }

    @Test
    fun `listener properties resolve like mq explorer`() {
        val zh = attrsTable("zh-CN")
        val running = resolveListenerPropertyGroups(listenerSnapshot())
        fun row(labelEn: String) = running.flatMap { it.second }.firstOrNull { it.labelEn == labelEn }

        // 复刻 PropertyRowLocalized 的查表链断言最终显示值（zh 表）。
        fun valueOf(labelEn: String): String? {
            val r = row(labelEn) ?: return null
            return listOfNotNull(r.valueKey, r.valueFallbackKey)
                .map { k -> k to (zh[k] ?: k) }
                .firstOrNull { (k, v) -> v != k }?.second
                ?: r.rawValue
        }

        assertEquals("TCP", valueOf("Transmission protocol"))
        // MQSVC_CONTROL_Q_MGR=0（消歧前 0 会被 MQSVC_TYPE_COMMAND「命令」抢中）。
        assertEquals("队列管理器", valueOf("Control"))
        assertEquals("正在运行", valueOf("Listener status"))
        assertEquals("1414", valueOf("Port"))
        assertEquals("*", valueOf("IP address"))
        assertEquals("100", valueOf("Backlog"))
        assertEquals("154", valueOf("Process ID"))
        // 名称 / 描述走 listener_prop_* 键（不在 attrs 表），显示原始值。
        assertEquals("SYSTEM.LISTENER.TCP.1", valueOf("Listener name"))

        // 停止的监听器：运行状态 / 进程号 / 启动日期时间不存在 → 行隐藏。
        val stopped = resolveListenerPropertyGroups(
            listenerSnapshot().copy(attributes = listenerSnapshot().attributes
                .filterKeys { it !in setOf("MQIACH_LISTENER_STATUS", "MQIACF_PROCESS_ID", "MQCACH_LISTENER_START_DATE", "MQCACH_LISTENER_START_TIME") }),
        ).flatMap { it.second }
        assertFalse(stopped.any { it.labelEn == "Listener status" })
        assertFalse(stopped.any { it.labelEn == "Process ID" })
        assertFalse(stopped.any { it.labelEn == "Start date" })
    }

    /** 真机 SYSTEM.LISTENER.TCP.1 快照形状（INQUIRE_LISTENER + STATUS 合并）。 */
    private fun listenerSnapshot() = MqObjectSnapshot(
        name = "SYSTEM.LISTENER.TCP.1",
        type = MqObjectType.LISTENER,
        attributes = mapOf(
            "MQCACH_LISTENER_NAME" to "SYSTEM.LISTENER.TCP.1",
            "MQCACH_LISTENER_DESC" to "",
            "MQIACH_LISTENER_CONTROL" to 0, // MQSVC_CONTROL_MANUAL
            "MQIACH_FIRST" to 2, // MQXPT_TCP（1501 的 Java 客户端别名键）
            "MQIACH_PORT" to 1414,
            "MQCACH_IP_ADDRESS" to "*",
            "MQIACH_BACKLOG" to 100,
            "MQCA_ALTERATION_DATE" to "2026-09-01",
            "MQCA_ALTERATION_TIME" to "22.55.39",
            "MQIACH_LISTENER_STATUS" to 2, // MQLSR_RUNNING
            "MQIACF_PROCESS_ID" to 154,
            "MQCACH_LISTENER_START_DATE" to "2026-09-24",
            "MQCACH_LISTENER_START_TIME" to "05.59.16",
        ),
    )
}
