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
 * [resolveQueuePropertyGroups]、[resolveListenerPropertyGroups]、
 * [resolveTopicPropertyGroups]、[resolveSubscriptionPropertyGroups]，
 * 再拿真实生成的 `ibm_mq_attrs_*` 表
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

    @Test
    fun `topic properties resolve like mq explorer`() {
        val zh = attrsTable("zh-CN")
        val groups = resolveTopicPropertyGroups(topicSnapshot())
        fun valueOf(labelEn: String): String? {
            val r = groups.flatMap { it.second }.firstOrNull { it.labelEn == labelEn } ?: return null
            return listOfNotNull(r.valueKey, r.valueFallbackKey)
                .map { k -> k to (zh[k] ?: k) }
                .firstOrNull { (k, v) -> v != k }?.second
                ?: r.rawValue
        }

        // 值与 MQ Explorer 的 Topic 页对齐（zh 表为准）。
        assertEquals("本地", valueOf("Topic type"))
        assertEquals("customer/", valueOf("Topic string"))
        assertEquals("作为父代", valueOf("Durable subscriptions"))
        // 单常量枚举：2 = MQTA_PASSTHRU（消歧前会被 MQTA_PUB_ALLOWED「允许」抢中）。
        assertEquals("传递", valueOf("Wildcard operation"))
        assertEquals("作为父代", valueOf("Publication"))
        assertEquals("作为父代", valueOf("NPM delivery"))
        assertEquals("作为父代", valueOf("Publication scope"))
        assertEquals("首先使用", valueOf("Proxy subscription behavior"))
        assertEquals("直接", valueOf("Cluster route"))
        // MQPRT_RESPONSE_AS_PARENT=0（快照值 0 = 随父项；alias 页的 1 才是同步）。
        assertEquals("作为父代", valueOf("Default put response type"))
        // -1 / -2 = AS_PARENT 族常量。
        assertEquals("作为父代", valueOf("Default persistence"))
        assertEquals("作为父代", valueOf("Default priority"))
    }

    /** 真机 CUSTOMER.TOPIC 快照形状（INQUIRE_TOPIC 全量应答）。 */
    private fun topicSnapshot() = MqObjectSnapshot(        name = "CUSTOMER.TOPIC",
        type = MqObjectType.TOPIC,
        attributes = mapOf(
            "MQCA_TOPIC_NAME" to "CUSTOMER.TOPIC",
            "MQIA_TOPIC_TYPE" to 0, // MQTOPT_LOCAL
            "MQCA_TOPIC_STRING" to "customer/",
            "MQCA_TOPIC_DESC" to "",
            "MQCA_CLUSTER_NAME" to "",
            "MQIA_CLUSTER_PUB_ROUTE" to 0, // MQCLROUTE_DIRECT
            "MQIA_DURABLE_SUB" to 0, // MQSUB_DURABLE_AS_PARENT
            "MQIA_INHIBIT_PUB" to 0, // MQTA_PUB_AS_PARENT
            "MQIA_INHIBIT_SUB" to 0, // MQTA_SUB_AS_PARENT
            "MQIA_TOPIC_DEF_PERSISTENCE" to -1, // MQPER_PERSISTENCE_AS_PARENT
            "MQIA_DEF_PRIORITY" to -2, // MQPRI_PRIORITY_AS_PARENT
            "MQIA_DEF_PUT_RESPONSE_TYPE" to 0, // MQPRT_SYNC_RESPONSE
            "MQIA_PM_DELIVERY" to 0, // MQDLV_AS_PARENT
            "MQIA_NPM_DELIVERY" to 0, // MQDLV_AS_PARENT
            "MQIA_PUB_SCOPE" to 1, // MQSCOPE_AS_PARENT
            "MQIA_SUB_SCOPE" to 1, // MQSCOPE_AS_PARENT
            "MQIA_PROXY_SUB" to 2, // MQTA_PROXY_SUB_FIRSTUSE
            "MQIA_WILDCARD_OPERATION" to 2, // MQTA_PASSTHRU
            "MQCA_MODEL_DURABLE_Q" to "",
            "MQCA_MODEL_NON_DURABLE_Q" to "",
            "MQIA_MULTICAST" to 0, // MQMC_AS_PARENT
            "MQCA_COMM_INFO_NAME" to "",
            "MQIA_USE_DEAD_LETTER_Q" to 0, // MQUSEDLQ_AS_PARENT
            "MQCA_CUSTOM" to "",
            "MQCA_ALTERATION_DATE" to "2026-09-25",
            "MQCA_ALTERATION_TIME" to "12.39.02",
        ),
    )

    @Test
    fun `subscription properties resolve like mq explorer`() {
        // 运行时 stringResource 查模块内合并表；这里合并 attrs 生成表与手写模块表
        // （MQEXPIRY_NOLIMIT.title 手补在 ibm_mq_zh-CN.json）。
        val zh = attrsTable("zh-CN") + Regex("\"([^\"]+)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(locate("src/commonMain/composeResources/files/i18n/ibm_mq_zh-CN.json").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val groups = resolveSubscriptionPropertyGroups(subscriptionSnapshot())
        fun valueOf(labelEn: String): String? {
            val r = groups.flatMap { it.second }.firstOrNull { it.labelEn == labelEn } ?: return null
            return listOfNotNull(r.valueKey, r.valueFallbackKey)
                .map { k -> k to (zh[k] ?: k) }
                .firstOrNull { (k, v) -> v != k }?.second
                ?: r.rawValue
        }

        // 值与 IBM 文档 / MQ Explorer 对齐（zh 表为准）。
        assertEquals("管理", valueOf("Type"))
        assertEquals("SHIPPING.SUB.NOTIFY", valueOf("Subscription name"))
        assertEquals("shipping/", valueOf("Topic string"))
        assertEquals("已提供", valueOf("Destination class"))
        assertEquals("是", valueOf("Durable"))
        // 客户端无 MQEXPIRY_NOLIMIT 常量，走 valueConstMap + 手补 title。
        assertEquals("无限制", valueOf("Expiry"))
        assertEquals("无", valueOf("Selector type"))
        assertEquals("作为已发布对象", valueOf("Publication priority"))
        assertEquals("消息属性", valueOf("Properties"))
        assertEquals("全部", valueOf("Request only"))
        assertEquals("全部", valueOf("Scope"))
        assertEquals("任何", valueOf("Variable user ID"))
        assertEquals("主题级别通配符", valueOf("Wildcard use"))
        assertEquals("mqadmin", valueOf("User"))

        // 字节 ID 类参数（SUB_ID 等）不进「全部属性」兜底。
        val allKeys = groups.lastOrNull { it.first.titleKey == "sub_props_group_all" }
            ?.second?.map { it.labelEn }.orEmpty()
        assertFalse(allKeys.any { it.contains("SUB_ID") || it.contains("ACCOUNTING") })
    }

    /** 真机 SHIPPING.SUB.NOTIFY 快照形状（INQUIRE_SUBSCRIPTION）。 */
    private fun subscriptionSnapshot() = MqObjectSnapshot(
        name = "SHIPPING.SUB.NOTIFY",
        type = MqObjectType.SUBSCRIPTION,
        attributes = mapOf(
            "MQBACF_SUB_ID" to "AMQ QM1 ...",
            "MQBACF_ACCOUNTING_TOKEN" to "?1001...",
            "MQBACF_DESTINATION_CORREL_ID" to "AMQ QM1 ...",
            "MQCACF_SUB_NAME" to "SHIPPING.SUB.NOTIFY",
            "MQCA_TOPIC_STRING" to "shipping/",
            "MQCA_TOPIC_NAME" to "SHIPPING.TOPIC",
            "MQCACF_DESTINATION" to "ORDER.NOTIFY.Q",
            "MQCACF_DESTINATION_Q_MGR" to "",
            "MQCACF_SUB_SELECTOR" to "",
            "MQIACF_SELECTOR_TYPE" to 0, // MQSELTYPE_NONE
            "MQCACF_APPL_IDENTITY_DATA" to "",
            "MQCACF_SUB_USER_DATA" to "",
            "MQIACF_DESTINATION_CLASS" to 2, // MQDC_PROVIDED
            "MQIACF_DURABLE_SUBSCRIPTION" to 1, // MQSUB_DURABLE_YES
            "MQIACF_EXPIRY" to -1, // MQEXPIRY_NOLIMIT（客户端无此常量）
            "MQIACF_PUBSUB_PROPERTIES" to 3, // MQPSPROP_MSGPROP
            "MQIACF_PUB_PRIORITY" to -3, // MQPRI_PRIORITY_AS_PUBLISHED
            "MQIACF_REQUEST_ONLY" to 2, // MQRU_PUBLISH_ALL
            "MQIACF_SUBSCRIPTION_SCOPE" to 2, // MQTSCOPE_ALL
            "MQIACF_SUB_LEVEL" to 1,
            "MQIACF_SUB_TYPE" to 2, // MQSUBTYPE_ADMIN
            "MQIACF_VARIABLE_USER_ID" to 2, // MQVU_ANY_USER
            "MQIACF_WILDCARD_SCHEMA" to 2, // MQWS_TOPIC
            "MQCACF_SUB_USER_ID" to "mqadmin",
            "MQCA_CREATION_DATE" to "2026-09-24",
            "MQCA_CREATION_TIME" to "12:42:11",
            "MQCA_ALTERATION_DATE" to "2026-09-25",
            "MQCA_ALTERATION_TIME" to "12:39:02",
        ),
    )
}
