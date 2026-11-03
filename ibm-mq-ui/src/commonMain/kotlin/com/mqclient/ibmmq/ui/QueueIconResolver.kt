package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/** 队列的展示类型（决定第一行 typeIcon 的图标与提示文案）。 */
enum class QueueType {
    /** 本地队列（USAGE=NORMAL）。 */
    LOCAL,

    /** 传输队列（QLOCAL 且 USAGE=TRANSMISSION）。 */
    TRANSMISSION,

    /** 远程队列。 */
    REMOTE,

    /** 别名队列。 */
    ALIAS,

    /** 模型队列。 */
    MODEL,

    /** 未知 / 未识别。 */
    UNKNOWN,
}

/**
 * 队列类型 → 图标解析器。
 *
 * 类型来源：慢扫写入的 `attributes["MQIA_Q_TYPE"]`（Int，MQQT_* 常量值），
 * 经 [MqAttributeNameMapper.lookupName] 反查成 `MQQT_LOCAL` 这类可读名再归类；
 * 传输队列按 USAGE（MQIA_USAGE，MQUS_TRANSMISSION=1）从本地队列里细分。
 * 反查不到 / 值不是 Int 一律落 UNKNOWN —— 不猜。
 */
/** MQUS_TRANSMISSION 的值（IBM cmqc.h / CMQC：传输用途 = 1，普通 = 0）。 */
private const val MQUS_TRANSMISSION = 1

object QueueIconResolver {

    /** 从快照解析队列展示类型。 */
    fun resolveType(snapshot: MqObjectSnapshot): QueueType {
        val typeValue = snapshot.attributes["MQIA_Q_TYPE"] as? Int ?: return QueueType.UNKNOWN
        val typeName = MqAttributeNameMapper.lookupName(typeValue, "MQQT")
        return when (typeName) {
            "MQQT_LOCAL" ->
                if (isTransmissionUsage(snapshot.attributes["MQIA_USAGE"])) QueueType.TRANSMISSION
                else QueueType.LOCAL
            "MQQT_REMOTE" -> QueueType.REMOTE
            "MQQT_ALIAS" -> QueueType.ALIAS
            "MQQT_MODEL" -> QueueType.MODEL
            else -> QueueType.UNKNOWN
        }
    }

    /** 队列展示类型 → 图标（全部取自 material-icons-extended，已核实存在）。 */
    fun resolveIcon(type: QueueType): ImageVector = when (type) {
        QueueType.LOCAL -> Icons.Default.Inbox          // 本地存储
        QueueType.TRANSMISSION -> Icons.Default.LocalShipping   // 传输中
        QueueType.REMOTE -> Icons.Default.CloudUpload   // 指向远程
        QueueType.ALIAS -> Icons.Default.AltRoute       // 转发 / 别名
        QueueType.MODEL -> Icons.Default.FileCopy       // 模板
        QueueType.UNKNOWN -> Icons.Default.HelpOutline  // 未知类型兜底
    }

    /** 从快照直接解析图标（[QueueListPanel] 的卡片转换入口）。 */
    fun resolveIcon(snapshot: MqObjectSnapshot): ImageVector = resolveIcon(resolveType(snapshot))

    /** 类型的 i18n 键（Tooltip / 无障碍文案用）。 */
    fun typeNameKey(type: QueueType): String = when (type) {
        QueueType.LOCAL -> "ibm_mq_queue_type_local"
        QueueType.TRANSMISSION -> "ibm_mq_queue_type_transmission"
        QueueType.REMOTE -> "ibm_mq_queue_type_remote"
        QueueType.ALIAS -> "ibm_mq_queue_type_alias"
        QueueType.MODEL -> "ibm_mq_queue_type_model"
        QueueType.UNKNOWN -> "ibm_mq_queue_type_unknown"
    }

    /**
     * USAGE 是否为传输用途。PCF 正常给 Int（MQUS_TRANSMISSION=1）；字符串形态
     * （"1" / "MQUS_TRANSMISSION" / "TRANSMISSION"）一并兼容，防御手改缓存。
     */
    private fun isTransmissionUsage(usage: Any?): Boolean = when (usage) {
        is Int -> usage == MQUS_TRANSMISSION   // MQUS_TRANSMISSION = 1（IBM 客户端不在本模块编译类路径）
        is Long -> usage == MQUS_TRANSMISSION.toLong()
        is String -> usage.uppercase().trim() in setOf(
            "MQUS_TRANSMISSION",
            "1",
            "TRANSMISSION",
        )
        else -> false
    }
}
