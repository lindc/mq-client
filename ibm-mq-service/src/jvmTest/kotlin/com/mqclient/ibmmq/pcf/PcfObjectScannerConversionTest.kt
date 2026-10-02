package com.mqclient.ibmmq.pcf

import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PcfObjectScanner 纯转换逻辑的离线单测：PCFMessage 可以无连接构造，
 * 属性提取 / 名称映射 / 快照转换 / 状态合并都不需要真 QMGR。
 * （需要真连接的 withAgent / scanXxx 链路不在单测覆盖范围。）
 */
class PcfObjectScannerConversionTest {

    private fun queueAttributesResponse() = PCFMessage(MQConstants.MQCMD_INQUIRE_Q).apply {
        addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
        // 字符串参数的底层形态可能是 byte[]（PCF 响应的真实形状）
        addParameter(MQConstants.MQCA_Q_DESC, "测试队列".toByteArray())
        addParameter(MQConstants.MQIA_MAX_Q_DEPTH, 5000)
    }

    private fun queueStatusResponse() = PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS).apply {
        addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
        addParameter(MQConstants.MQIA_CURRENT_Q_DEPTH, 42)
        addParameter(MQConstants.MQIA_OPEN_INPUT_COUNT, 3)
        addParameter(MQConstants.MQIA_OPEN_OUTPUT_COUNT, 2)
    }

    @Test
    fun `extract attributes maps ids to readable names`() {
        val attrs = extractAttributes(queueAttributesResponse())
        assertEquals("DEV.QUEUE.1", attrs["MQCA_Q_NAME"])
        assertEquals(5000, attrs["MQIA_MAX_Q_DEPTH"])
        // byte[] 已解码成串
        assertEquals("测试队列", attrs["MQCA_Q_DESC"])
    }

    @Test
    fun `mapper resolves known parameter id`() {
        assertEquals("MQCA_Q_NAME", MqAttributeNameMapper.getName(MQConstants.MQCA_Q_NAME))
        assertEquals("MQIA_MAX_Q_DEPTH", MqAttributeNameMapper.getName(MQConstants.MQIA_MAX_Q_DEPTH))
    }

    @Test
    fun `mapper falls back to ATTR_ for unknown id`() {
        assertEquals("ATTR_999999", MqAttributeNameMapper.getName(999999))
    }

    @Test
    fun `lookup resolves channel constants by value`() {
        assertEquals("MQCHT_SENDER", MqAttributeNameMapper.lookupName(MQConstants.MQCHT_SENDER, "MQCHT"))
        assertEquals("MQCHS_RUNNING", MqAttributeNameMapper.lookupName(MQConstants.MQCHS_RUNNING, "MQCHS"))
        assertNull(MqAttributeNameMapper.lookupName(999999, "MQCHS"))
    }

    @Test
    fun `queue snapshot merges attributes and status`() {
        val snapshot = queueAttributesResponse().toQueueSnapshot(queueStatusResponse())
        assertEquals(MqObjectType.QUEUE, snapshot.type)
        assertEquals("DEV.QUEUE.1", snapshot.name)
        assertEquals("测试队列", snapshot.description)
        assertEquals(5000, snapshot.maxDepth)
        assertEquals(42, snapshot.currentDepth)
        assertEquals(42, snapshot.messageCount)
        // consumerCount = openInput + openOutput
        assertEquals(5, snapshot.consumerCount)
        // 状态响应参数并入全量属性
        assertEquals(42, snapshot.attributes["MQIA_CURRENT_Q_DEPTH"])
    }

    @Test
    fun `queue snapshot without status leaves dynamic fields null`() {
        val snapshot = queueAttributesResponse().toQueueSnapshot(null)
        assertNull(snapshot.currentDepth)
        assertNull(snapshot.messageCount)
        assertNull(snapshot.consumerCount)
        assertEquals(5000, snapshot.maxDepth)
    }

    @Test
    fun `channel type and status names resolve via lookup`() {
        assertEquals("SENDER", channelTypeName(MQConstants.MQCHT_SENDER))
        assertNull(channelTypeName(null))
        // MQCHS_RUNNING 在客户端 CMQCFC 里存在（值反查可用）
        assertEquals("RUNNING", channelStatusName(MQConstants.MQCHS_RUNNING))
    }

    @Test
    fun `listener status resolves to readable name`() {
        // allclient jar 里没有 MQLSS_*/MQLSR_* 命名常量，改为手写映射，不再把原始数字
        // 直接拼成 STATE_n 暴露给用户。
        assertEquals("RUNNING", listenerStatusName(2))
        assertEquals("NOT_RUNNING", listenerStatusName(0))
        assertEquals("STARTING", listenerStatusName(1))
        assertEquals("STOPPING", listenerStatusName(3))
        // 未知值：返回 UNKNOWN（由适配器显示「未知」+ 记日志），不是 STATE_n
        assertEquals("UNKNOWN", listenerStatusName(999999))
        // 无状态行（没拿到状态值）：保持 null，适配器显示「未启动」
        assertNull(listenerStatusName(null))
    }

    /* ---------------------- 通道运行状态明细（queryChannelStatusDetail） ---------------------- */

    /**
     * 全量 CHSTATUS 应答（一个活动实例行）：字符串参数刻意混用 String 与 byte[]
     * 两种底层形态（PCF 线缆的真实形状是 byte[]，见 extractAttributes 的解码注释）。
     */
    private fun channelStatusResponse() = PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS).apply {
        addParameter(MQConstants.MQCACH_CHANNEL_NAME, "TO.QM2".toByteArray())
        addParameter(MQConstants.MQIACH_CHANNEL_TYPE, MQConstants.MQCHT_SENDER)
        addParameter(MQConstants.MQIACH_CHANNEL_STATUS, MQConstants.MQCHS_RUNNING)
        addParameter(MQConstants.MQIACH_CURRENT_SEQ_NUMBER, 1234)
        addParameter(MQConstants.MQIACH_LAST_SEQ_NUMBER, 1230)
        addParameter(MQConstants.MQIACH_IN_DOUBT, 1)
        addParameter(MQConstants.MQIACH_MSGS, 98_765)
        addParameter(MQConstants.MQIACH_BYTES_SENT, 111_111)
        addParameter(MQConstants.MQIACH_BYTES_RCVD, 222_222)
        addParameter(MQConstants.MQCACH_LAST_MSG_DATE, "20261002".toByteArray())
        addParameter(MQConstants.MQCACH_LAST_MSG_TIME, "14:30:25".toByteArray())
    }

    @Test
    fun `channel status detail extracts full metrics`() {
        val detail = channelStatusDetailFrom("TO.QM2", channelStatusResponse())
        assertEquals("TO.QM2", detail!!.channelName)
        assertEquals("SENDER", detail.channelType)
        assertEquals("RUNNING", detail.status)
        assertEquals(1234L, detail.currentSeqNo)
        assertEquals(1230L, detail.lastSeqNum)
        assertEquals(true, detail.inDoubt)
        assertEquals(98_765L, detail.messages)
        assertEquals(111_111L, detail.bytesSent)
        assertEquals(222_222L, detail.bytesReceived)
        assertEquals("20261002", detail.lastMsgDate)
        assertEquals("14:30:25", detail.lastMsgTime)
    }

    @Test
    fun `null response means channel not running`() {
        // 通道已完全停：CHSTATUS 无状态行 → null（UI 显示「通道未运行」，不是 0 值明细）
        assertNull(channelStatusDetailFrom("TO.QM2", null))
    }

    @Test
    fun `channel name is trimmed`() {
        val detail = channelStatusDetailFrom("  TO.QM2  ", channelStatusResponse())
        assertEquals("TO.QM2", detail!!.channelName)
    }

    @Test
    fun `missing parameters leave fields null`() {
        // 只有状态行（如仅报状态的通道实例）：其余指标缺失 = null，不是 0
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS).apply {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "TO.QM2")
            addParameter(MQConstants.MQIACH_CHANNEL_STATUS, MQConstants.MQCHS_RUNNING)
        }
        val detail = channelStatusDetailFrom("TO.QM2", response)!!
        assertEquals("RUNNING", detail.status)
        assertNull(detail.channelType)
        assertNull(detail.currentSeqNo)
        assertNull(detail.lastSeqNum)
        assertNull(detail.inDoubt)
        assertNull(detail.messages)
        assertNull(detail.bytesSent)
        assertNull(detail.bytesReceived)
        assertNull(detail.lastMsgDate)
        assertNull(detail.lastMsgTime)
    }

    @Test
    fun `in-doubt zero maps to false`() {
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS).apply {
            addParameter(MQConstants.MQIACH_IN_DOUBT, 0)
        }
        assertEquals(false, channelStatusDetailFrom("X", response)!!.inDoubt)
    }

    @Test
    fun `sequence numbers extract regardless of which alias name the mapper returns`() {
        // 经典名 MQIACH_CURRENT_SEQ_NO / MQIACH_LAST_SEQ_NUM 在 10.0.0.5 jar 不存在，
        // 同一线缆 ID 有两个别名常量；mapper 反查取哪个名字不可控，候选集必须都兜。
        // 这里用裸 ID 构造（与真实应答同形），两条路径（主名 / 别名）都断言到。
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS).apply {
            addParameter(1532, 777)  // MQIACH_CURRENT_SEQ_NUMBER
            addParameter(1529, 770)  // MQIACH_LAST_SEQ_NUMBER
        }
        val detail = channelStatusDetailFrom("X", response)!!
        assertEquals(777L, detail.currentSeqNo)
        assertEquals(770L, detail.lastSeqNum)
    }

    @Test
    fun `long of reads any of the alias keys`() {
        assertEquals(5L, mapOf<String, Any?>("MQIACH_CURRENT_SEQ_NUMBER" to 5).longOf("MQIACH_CURRENT_SEQ_NUMBER", "MQIACH_CURRENT_SEQUENCE_NUMBER"))
        assertEquals(5L, mapOf<String, Any?>("MQIACH_CURRENT_SEQUENCE_NUMBER" to 5).longOf("MQIACH_CURRENT_SEQ_NUMBER", "MQIACH_CURRENT_SEQUENCE_NUMBER"))
        assertNull(emptyMap<String, Any?>().longOf("MQIACH_CURRENT_SEQ_NUMBER"))
    }

    @Test
    fun `str of decodes and treats blank as missing`() {
        assertEquals("20261002", mapOf<String, Any?>("MQCACH_LAST_MSG_DATE" to "20261002").strOf("MQCACH_LAST_MSG_DATE"))
        // 空白值 = 缺失（PCF 字符串按固定宽度补尾随空格，全空白不该显示成"有值"）
        assertNull(mapOf<String, Any?>("MQCACH_LAST_MSG_DATE" to "   ").strOf("MQCACH_LAST_MSG_DATE"))
        assertNull(emptyMap<String, Any?>().strOf("MQCACH_LAST_MSG_DATE"))
    }

    @Test
    fun `bool of handles both in-doubt ids and missing value`() {
        assertEquals(true, mapOf<String, Any?>("MQIACH_IN_DOUBT" to 1).boolOf("MQIACH_IN_DOUBT", "MQIACH_INDOUBT_STATUS"))
        assertEquals(false, mapOf<String, Any?>("MQIACH_IN_DOUBT" to 0).boolOf("MQIACH_IN_DOUBT", "MQIACH_INDOUBT_STATUS"))
        // 别名键（mapper 取到另一个名字时）
        assertEquals(true, mapOf<String, Any?>("MQIACH_INDOUBT_STATUS" to 1).boolOf("MQIACH_IN_DOUBT", "MQIACH_INDOUBT_STATUS"))
        // 两个候选键都缺：null（UI 显示「-」，不是「否」）
        assertNull(emptyMap<String, Any?>().boolOf("MQIACH_IN_DOUBT", "MQIACH_INDOUBT_STATUS"))
    }

    /* -------------- scanner 查询方法的应答→模型转换（统一抽取模式） -------------- */

    @Test
    fun `queue depth pair from valid and incomplete responses`() {
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS).apply {
            addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
            addParameter(MQConstants.MQIA_CURRENT_Q_DEPTH, 7)
        }
        assertEquals("DEV.QUEUE.1" to 7, queueDepthPairFrom(response))
        // 深度缺失（心跳快扫的丢弃口径）
        assertNull(queueDepthPairFrom(PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS).apply {
            addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
        }))
    }

    @Test
    fun `channel status pair from valid and incomplete responses`() {
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS).apply {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "TO.QM2".toByteArray())
            addParameter(MQConstants.MQIACH_CHANNEL_STATUS, MQConstants.MQCHS_RUNNING)
        }
        assertEquals("TO.QM2" to MQConstants.MQCHS_RUNNING, channelStatusPairFrom(response))
        // 状态缺失（同名去重前先丢）
        assertNull(channelStatusPairFrom(PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS).apply {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "TO.QM2")
        }))
    }

    @Test
    fun `listener status pair from valid and incomplete responses`() {
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS).apply {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "DEV.LISTENER.TCP")
            addParameter(MQConstants.MQIACH_LISTENER_STATUS, 2)
        }
        assertEquals("DEV.LISTENER.TCP" to 2, listenerStatusPairFrom(response))
        assertNull(listenerStatusPairFrom(PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS).apply {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "DEV.LISTENER.TCP")
        }))
    }

    @Test
    fun `open process id pair from valid and incomplete responses`() {
        val handle = PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS).apply {
            addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
            addParameter(MQConstants.MQIACF_PROCESS_ID, 4242)
        }
        assertEquals("DEV.QUEUE.1" to 4242, openProcessIdPairFrom(handle))
        // 无进程号（句柄行不含 PID）：丢弃
        assertNull(openProcessIdPairFrom(PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS).apply {
            addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
        }))
    }

    @Test
    fun `queue manager snapshot merges status only when present`() {
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR).apply {
            addParameter(MQConstants.MQCA_Q_MGR_NAME, "QM1")
        }
        // 状态查询没拿到（部分平台 / 权限受限）：不加 MQIACF_Q_MGR_STATUS 键
        val without = queueManagerSnapshotFrom(response, null)
        assertEquals("QM1", without.name)
        assertEquals(MqObjectType.QUEUE_MANAGER, without.type)
        assertNull(without.attributes["MQIACF_Q_MGR_STATUS"])
        // 状态拿到：并入全量属性
        val with = queueManagerSnapshotFrom(response, 1)
        assertEquals(1, with.attributes["MQIACF_Q_MGR_STATUS"])
    }

    @Test
    fun `queue snapshot merges extended attrs and open process ids`() {
        val response = PCFMessage(MQConstants.MQCMD_INQUIRE_Q).apply {
            addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
            addParameter(MQConstants.MQIA_MAX_Q_DEPTH, 5000)
        }
        val status = PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS).apply {
            addParameter(MQConstants.MQCA_Q_NAME, "DEV.QUEUE.1")
            addParameter(MQConstants.MQIA_CURRENT_Q_DEPTH, 3)
        }
        // 无扩展、无打开者：不加 MQIACF_OPEN_PROCESS_IDS 键
        val plain = queueSnapshotFrom(response, status)
        assertEquals(3, plain.currentDepth)
        assertNull(plain.attributes["MQIACF_OPEN_PROCESS_IDS"])
        // 扩展属性 + 打开者进程号：并进 attributes
        val full = queueSnapshotFrom(
            response = response,
            status = status,
            extendedAttrs = mapOf("MQIA_DEF_READ_AHEAD" to 0),
            openProcessIds = listOf(11, 22),
        )
        assertEquals(0, full.attributes["MQIA_DEF_READ_AHEAD"])
        assertEquals(listOf(11, 22), full.attributes["MQIACF_OPEN_PROCESS_IDS"])
        // 静态字段不受合并影响
        assertEquals(5000, full.maxDepth)
    }

    @Test
    fun `channel snapshot promotes status and type to typed fields`() {
        val define = PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL).apply {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "TO.QM2")
            addParameter(MQConstants.MQIACH_CHANNEL_TYPE, MQConstants.MQCHT_SENDER)
        }
        val status = PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS).apply {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "TO.QM2")
            addParameter(MQConstants.MQIACH_CHANNEL_STATUS, MQConstants.MQCHS_RUNNING)
        }
        val snapshot = channelSnapshotFrom(define, status)
        assertEquals("TO.QM2", snapshot.name)
        assertEquals(MqObjectType.CHANNEL, snapshot.type)
        assertEquals("SENDER", snapshot.channelType)
        assertEquals("RUNNING", snapshot.channelStatus)
        // 无状态行（停掉的通道定义）：channelStatus 为 null，定义属性保留
        val stopped = channelSnapshotFrom(define, null)
        assertNull(stopped.channelStatus)
        assertEquals("SENDER", stopped.channelType)
    }

    @Test
    fun `listener snapshot takes port from status with define fallback`() {
        val define = PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER).apply {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "DEV.LISTENER.TCP")
            addParameter(MQConstants.MQIACH_PORT, 1414)
        }
        val status = PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS).apply {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "DEV.LISTENER.TCP")
            addParameter(MQConstants.MQIACH_LISTENER_STATUS, 2)
            addParameter(MQConstants.MQIACH_PORT, 1415)
        }
        // 状态行优先
        val running = listenerSnapshotFrom(define, status)
        assertEquals("RUNNING", running.listenerStatus)
        assertEquals(1415, running.port)
        // 无状态行：未运行 + 端口回落定义属性
        val stopped = listenerSnapshotFrom(define, null)
        assertNull(stopped.listenerStatus)
        assertEquals(1414, stopped.port)
    }

    /** 远端集群成员的 CLUSQMGR 应答（常量坑：通道名会被 mapper 命名成 MQCACH_FIRST）。 */
    private fun clusqmgrResponse() = PCFMessage(MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR).apply {
        addParameter(MQConstants.MQCA_CLUSTER_Q_MGR_NAME, "QM2")
        addParameter(MQConstants.MQCA_CLUSTER_NAME, "CLUSTER1")
        addParameter(MQConstants.MQCACH_CHANNEL_NAME, "TO.QM2")
        addParameter(MQConstants.MQIACF_Q_MGR_TYPE, 1) // MQQMT_REPOSITORY
        addParameter(MQConstants.MQIACF_Q_MGR_DEFINITION_TYPE, 3) // MQQMDT_CLUSTER_RECEIVER
        addParameter(MQConstants.MQCACH_CONNECTION_NAME, "host(1414)")
        addParameter(MQConstants.MQIACH_CHANNEL_STATUS, MQConstants.MQCHS_RUNNING)
    }

    @Test
    fun `cluster member maps full clusqmgr response`() {
        val member = clusterMemberFrom(clusqmgrResponse())!!
        assertEquals("QM2", member.qmgrName)
        assertEquals("CLUSTER1", member.clusterName)
        // 常量坑：3501 在本 jar 里 mapper 反查成 MQCACH_FIRST，别名兜底必须命中
        assertEquals("TO.QM2", member.channelName)
        assertEquals("REPOS", member.qmType)
        assertEquals("CLUSRCVR", member.defType)
        assertEquals("host(1414)", member.conName)
        assertEquals("RUNNING", member.status)
    }

    @Test
    fun `cluster member without qmgr name is dropped`() {
        assertNull(clusterMemberFrom(PCFMessage(MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR).apply {
            addParameter(MQConstants.MQCA_CLUSTER_NAME, "CLUSTER1")
        }))
    }

    /* ---------------------- 序列号的 SAVED 行回落（mergeSavedSeqNumbers） ---------------------- */

    private val baseDetail = ChannelStatusDetail(
        channelName = "QM1.TO.QM2", channelType = "SENDER", status = "RUNNING",
        currentSeqNo = 0L, lastSeqNum = 0L, inDoubt = false,
        messages = 5L, bytesSent = 100L, bytesReceived = null,
        lastMsgDate = "20261002", lastMsgTime = "12:00:00",
    )

    @Test
    fun `saved fallback fills zero seq numbers from saved row`() {
        // 9.2.4 容器实测形态：CURRENT 行序列号恒 0，SAVED 行有真实值（3 条消息 → 3/2）
        val merged = mergeSavedSeqNumbers(
            baseDetail,
            mapOf<String, Any?>("MQIACH_CURRENT_SEQ_NUMBER" to 3, "MQIACH_LAST_SEQ_NUMBER" to 2),
        )
        assertEquals(3L, merged.currentSeqNo)
        assertEquals(2L, merged.lastSeqNum)
        // 其余字段不受回落影响
        assertEquals(5L, merged.messages)
        assertEquals(100L, merged.bytesSent)
        assertEquals("RUNNING", merged.status)
    }

    @Test
    fun `saved fallback keeps live values when current row has them`() {
        // 健康型 QM：CURRENT 行有真实序列号（> 0），SAVED 是旧值 —— 不覆盖
        val merged = mergeSavedSeqNumbers(
            baseDetail.copy(currentSeqNo = 500L, lastSeqNum = 499L),
            mapOf<String, Any?>("MQIACH_CURRENT_SEQ_NUMBER" to 3, "MQIACH_LAST_SEQ_NUMBER" to 2),
        )
        assertEquals(500L, merged.currentSeqNo)
        assertEquals(499L, merged.lastSeqNum)
    }

    @Test
    fun `saved fallback fills only the missing side`() {
        // 单边缺失：只补缺的那边
        val merged = mergeSavedSeqNumbers(
            baseDetail.copy(currentSeqNo = 7L, lastSeqNum = 0L),
            mapOf<String, Any?>("MQIACH_CURRENT_SEQ_NUMBER" to 3, "MQIACH_LAST_SEQ_NUMBER" to 2),
        )
        assertEquals(7L, merged.currentSeqNo)
        assertEquals(2L, merged.lastSeqNum)
    }

    @Test
    fun `saved fallback without saved row returns detail unchanged`() {
        val merged = mergeSavedSeqNumbers(baseDetail, null)
        assertEquals(baseDetail, merged)
        // SAVED 行也没有序列号：保持原值（0），不猜
        val merged2 = mergeSavedSeqNumbers(baseDetail, emptyMap())
        assertEquals(0L, merged2.currentSeqNo)
        assertEquals(0L, merged2.lastSeqNum)
    }

    @Test
    fun `saved fallback reads alias keys`() {
        // mapper 反查可能命中别名（MQIACH_CURRENT_SEQUENCE_NUMBER 等）
        val merged = mergeSavedSeqNumbers(
            baseDetail,
            mapOf<String, Any?>("MQIACH_CURRENT_SEQUENCE_NUMBER" to 3, "MQIACH_LAST_SEQUENCE_NUMBER" to 2),
        )
        assertEquals(3L, merged.currentSeqNo)
        assertEquals(2L, merged.lastSeqNum)
    }
}
