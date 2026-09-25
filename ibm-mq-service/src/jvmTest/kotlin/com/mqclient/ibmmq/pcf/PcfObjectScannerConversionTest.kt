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
}
