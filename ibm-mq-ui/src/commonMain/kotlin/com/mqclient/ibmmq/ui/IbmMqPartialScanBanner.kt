package com.mqclient.ibmmq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.uicommon.PartialScanBanner
import com.mqclient.uicommon.i18n.stringResource

/**
 * 「部分数据未刷新」提示条：这一轮对象慢扫有类型没取到时，在该连接的所有对象页顶部。
 *
 * 为什么需要它：QMGR 上不是每个用户都有全部对象的查看权（2035 无权限是**预期内**的
 * 权限边界，不是故障），某一类查询超时也只跳过该类。所以连接不该标红，但代价是
 * 界面会安静地少一块数据 —— 用户无从区分「这里本来就没有」和「这次没读到」。
 *
 * 外壳（配色 / 图标 / 布局）在 ui-common 的 [PartialScanBanner]（四个 MQ 模块共用），
 * 这里只保留「本连接失败了哪些类型」与「类型名怎么翻译」这两件 IBM MQ 特有的事。
 *
 * 数据源是 [IbmMQModule.scanFailuresFlow] 而不是事件：事件是瞬时的，切个 Tab 再回来
 * 订阅者就重建了，提示会消失而数据依旧是旧的。StateFlow 与缓存同寿命，下一轮扫全
 * 或断开连接才清。
 */
@Composable
internal fun IbmMqPartialScanBanner(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val failures by IbmMQModule.scanFailuresFlow.collectAsState()
    val types = scanFailureTypes(failures, connectionId)
    PartialScanBanner(
        failureNames = types.map { stringResource(it.i18nKey()) },
        messageKey = "ibm_mq_partial_scan_banner",
        hintKey = "ibm_mq_partial_scan_hint",
        modifier = modifier,
    )
}

/**
 * 取该连接本轮失败的类型，按枚举声明顺序排（提示文案顺序因此稳定，不随 Map 迭代抖动）。
 *
 * 纯函数：不碰 Compose 运行时，可直接单测。
 */
internal fun scanFailureTypes(
    failures: Map<String, Set<MqObjectType>>,
    connectionId: String,
): List<MqObjectType> =
    failures[connectionId].orEmpty().sortedBy { it.ordinal }

/**
 * 失败类型 → 模块 i18n 键。
 *
 * 除队列管理器外都复用导航项已有的 `ibm_mq_nav_*` 文案 —— 提示条里说的类型名
 * 与左侧导航一致，用户不用做第二次对应。用 `when` 穷举而不是拼字符串：
 * 新增一种对象类型时编译器会在这里报错，而不是界面上显示裸 key。
 */
internal fun MqObjectType.i18nKey(): String = when (this) {
    MqObjectType.QUEUE_MANAGER -> "ibm_mq_scan_kind_queue_manager"
    MqObjectType.QUEUE -> "ibm_mq_nav_queues"
    MqObjectType.CHANNEL -> "ibm_mq_nav_channels"
    MqObjectType.TOPIC -> "ibm_mq_nav_topics"
    MqObjectType.SUBSCRIPTION -> "ibm_mq_nav_subscriptions"
    MqObjectType.LISTENER -> "ibm_mq_nav_listeners"
    MqObjectType.SERVICE -> "ibm_mq_nav_services"
    MqObjectType.PROCESS -> "ibm_mq_nav_process_definitions"
    MqObjectType.NAMELIST -> "ibm_mq_nav_namelists"
    MqObjectType.AUTH_INFO -> "ibm_mq_nav_auth_info"
    MqObjectType.COMM_INFO -> "ibm_mq_nav_comm_info"
}
