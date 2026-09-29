package com.mqclient.ibmmq.ui.topology

import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * [MqObjectSnapshot] 的拓扑属性提取扩展：把 PCF 属性键（MQCA_* / MQIA_* / MQCACH_*）
 * 翻译成拓扑图需要的业务语义（alias 目标、XMITQ、RQMNAME、订阅目标…）。
 *
 * 键名实测于 MQ 9.3+/10（与既有 IbmMQTopologyProvider 同源）：
 *  - alias 目标在 MQCA_BASE_OBJECT_NAME 携带（本版本响应不带 TARGQ），旧键作回落；
 *  - 通道族属性用 MQCACH_ 前缀，不是 MQCA_（XMITQ / RQMNAME 都踩过这个坑）。
 */

/** 任意属性的可读字符串值（trim + 空白归 null）。 */
private fun MqObjectSnapshot.attrStr(key: String): String? =
    attributes[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }

/** 队列子类型可读名（MQQT_LOCAL / MQQT_ALIAS / MQQT_REMOTE / MQQT_MODEL…）；非队列或缺失为 null。 */
fun MqObjectSnapshot.getQueueType(): String? {
    val v = attributes["MQIA_Q_TYPE"] as? Int ?: return null
    return MqAttributeNameMapper.lookupName(v, "MQQT")
}

/** MQIA_USAGE（整数）。 */
private fun MqObjectSnapshot.usage(): Int = (attributes["MQIA_USAGE"] as? Int) ?: 0

/** 别名队列（QUEUE + MQQT_ALIAS）。 */
fun MqObjectSnapshot.isAliasQueue(): Boolean = getQueueType() == "MQQT_ALIAS"

/** 远程队列定义（QUEUE + MQQT_REMOTE）。 */
fun MqObjectSnapshot.isRemoteQueue(): Boolean = getQueueType() == "MQQT_REMOTE"

/** 模板队列（MQQT_MODEL）：不进拓扑图。 */
fun MqObjectSnapshot.isModelQueue(): Boolean = getQueueType() == "MQQT_MODEL"

/** 传输队列（QLOCAL + USAGE=MQUS_TRANSMISSION）。 */
fun MqObjectSnapshot.isTransmission(): Boolean =
    getQueueType() == "MQQT_LOCAL" && usage() == MQUS_TRANSMISSION

/** 普通本地队列（QLOCAL + USAGE=NORMAL）。 */
fun MqObjectSnapshot.isLocalQueue(): Boolean =
    getQueueType() == "MQQT_LOCAL" && usage() != MQUS_TRANSMISSION

/**
 * Alias 目标（TARGET）：MQ 9.3+/10 的 INQUIRE_Q 响应不带 TARGQ，目标经
 * MQCA_BASE_OBJECT_NAME 携带（MQIA_BASE_TYPE 区分目标为队列/主题）；旧键名作回落。
 * 目标可以是另一条别名（alias 链）。
 */
fun MqObjectSnapshot.getAliasTarget(): String? =
    attrStr("MQCA_BASE_OBJECT_NAME")
        ?: attrStr("MQCA_TARGQ")
        ?: attrStr("MQCA_TARGET_Q_NAME")

/** 远程队列定义的目标 QMgr（RQMNAME；QREMOTE 定义自带，实测总有值）。 */
fun MqObjectSnapshot.getRemoteQueueRqmName(): String? = attrStr("MQCA_REMOTE_Q_MGR_NAME")

/** 远程队列的传输队列（XMITQ）：消息出站的下一跳。 */
fun MqObjectSnapshot.getRemoteXmitQueue(): String? = attrStr("MQCA_XMIT_Q_NAME")

/** 通道的传输队列（XMITQ）：通道族用 MQCACH_ 前缀，MQCA_ 作旧版回落。 */
fun MqObjectSnapshot.getChannelXmitQueue(): String? =
    attrStr("MQCACH_XMIT_Q_NAME") ?: attrStr("MQCA_XMIT_Q_NAME")

/**
 * 通道的远程队列管理器名（RQMNAME）：REMOTE_QMGR 虚拟节点的来源。
 * 通道族键 MQCACH_REMOTE_QMGR_NAME 优先，MQCA_REMOTE_Q_MGR_NAME 作回落。
 */
fun MqObjectSnapshot.getChannelRqmName(): String? =
    attrStr("MQCACH_REMOTE_QMGR_NAME") ?: attrStr("MQCA_REMOTE_Q_MGR_NAME")

/** 只画 Sender / Requester 通道（Receiver / Server / SvrConn / Cluster 不进拓扑图）。 */
fun MqObjectSnapshot.isSenderOrRequester(): Boolean =
    channelType?.uppercase() in SENDER_OR_REQUESTER_TYPES

/** 订阅的主题串（TOPICSTR）；缺失时回落到所属主题对象名（MQCA_TOPIC_NAME）。 */
fun MqObjectSnapshot.getSubscriptionTopic(): String? =
    attrStr("MQCA_TOPIC_STRING") ?: attrStr("MQCA_TOPIC_NAME")

/** 订阅所属的主题**对象**名（MQCA_TOPIC_NAME；admin 订阅才有，非空时优先按它匹配主题）。 */
fun MqObjectSnapshot.getSubscriptionTopicObjectName(): String? = attrStr("MQCA_TOPIC_NAME")

/** 订阅的目标队列（DEST；可为 local / remote / alias）。 */
fun MqObjectSnapshot.getSubscriptionDestination(): String? = attrStr("MQCACF_DESTINATION")

/** 主题对象的主题串（TOPICSTR）；缺失回落对象名（管理主题的常见形态）。 */
fun MqObjectSnapshot.getTopicStringOrName(): String? =
    attrStr("MQCA_TOPIC_STRING") ?: name

/** 主题串宽松匹配：完全相等，或去掉尾部 '/' 后相等（通配符语义的保守近似）。 */
fun topicStringsMatch(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    return a.trimEnd('/') == b.trimEnd('/')
}

/**
 * 系统对象判定：`SYSTEM.*` / `AMQ.*` 一律不进拓扑图（大小写不敏感）。
 *
 * 注意订阅的坑：MQ 内部系统订阅的名字**带 QMGR 前缀**，形如
 * `QM1 SYSTEM.BROKER.INTER.BROKER.COMMUNICATIONS.<SUBID hex>`——startsWith 抓不到，
 * 按空白分词后任一段以 SYSTEM. / AMQ. 开头同样算系统对象；`$` 开头（$MQ…）是另一类
 * MQ 内部订阅名，一并挡掉。
 */
fun MqObjectSnapshot.isSystemObject(): Boolean {
    val n = name
    if (n.startsWith("SYSTEM", ignoreCase = true) || n.startsWith("AMQ", ignoreCase = true)) {
        return true
    }
    if (n.startsWith("$")) return true
    return n.split(' ', '\t').asSequence().drop(1)
        .any { it.startsWith("SYSTEM.", ignoreCase = true) || it.startsWith("AMQ.", ignoreCase = true) }
}

fun List<MqObjectSnapshot>.filterNotSystem(): List<MqObjectSnapshot> =
    filter { !it.isSystemObject() }

/** MQUS_TRANSMISSION：传输队列用途（cmqc.h，值 = 1）。 */
private const val MQUS_TRANSMISSION = 1

private val SENDER_OR_REQUESTER_TYPES = setOf("SENDER", "REQUESTER")
