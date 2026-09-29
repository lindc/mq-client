package com.mqclient.ibmmq.pcf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MqAttributeDisplay 离线单测：枚举归属判定不需要真 QMGR ——
 * 依赖 MQConstants 反射表（[MqAttributeNameMapper]）和生成的
 * [MqQueueAttrEnums]（源自 MQ Explorer DmQueue.xml）。
 */
class MqAttributeDisplayTest {

    @Test
    fun `enum value resolves to the constant legal for that attribute`() {
        // 同值横跨多族常量：只有属性自己的枚举集才配选中。
        assertEquals("MQQT_LOCAL", MqAttributeDisplay.constNameFor("MQIA_Q_TYPE", 1))
        assertEquals("MQPER_PERSISTENT", MqAttributeDisplay.constNameFor("MQIA_DEF_PERSISTENCE", 1))
        assertEquals("MQQA_GET_ALLOWED", MqAttributeDisplay.constNameFor("MQIA_INHIBIT_GET", 0))
        assertEquals("MQQA_PUT_ALLOWED", MqAttributeDisplay.constNameFor("MQIA_INHIBIT_PUT", 0))
        // CMQC: MQPRT_SYNC_RESPONSE=1、MQPRT_ASYNC_RESPONSE=2（不是 0/1）。
        assertEquals(
            "MQPRT_SYNC_RESPONSE",
            MqAttributeDisplay.constNameFor("MQIA_DEF_PUT_RESPONSE_TYPE", 1),
        )
        assertEquals(
            "MQREADA_NO",
            MqAttributeDisplay.constNameFor("MQIA_DEF_READ_AHEAD", 0),
        )
    }

    @Test
    fun `non enum attribute or unknown value falls back to null`() {
        assertNull(MqAttributeDisplay.constNameFor("MQIA_MAX_Q_DEPTH", 5000))
        assertNull(MqAttributeDisplay.constNameFor("MQCA_Q_NAME", 1))
        assertNull(MqAttributeDisplay.constNameFor("MQIA_Q_TYPE", 999))
        // 截图场景：CLWLPRTY 是 RANGE 型（0-9）而非枚举，回落显示原始数字。
        assertNull(MqAttributeDisplay.constNameFor("MQIA_CLWL_Q_PRIORITY", 0))
    }
}
