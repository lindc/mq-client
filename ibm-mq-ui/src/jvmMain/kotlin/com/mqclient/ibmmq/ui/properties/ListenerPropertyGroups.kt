package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * 监听器属性分组表：对齐 MQ Explorer 的 Listener properties 对话框（General 单页，
 * 字段序按 DmListener.xml 的 grpseq；**全部文案走 i18n 键**，键规则
 * `listener_prop_name_<字段>` / `listener_prop_desc_<字段>`，枚举值走
 * `ibm_mq_attrs` 表的 `<常量>.title`（MQ Explorer 同款 zh 文案））。
 *
 * 复用 queue 对话框的通用类型（[ObjectPropertyField] / [ObjectPropertyGroup] /
 * [ResolvedObjectProperty]）；键不在快照里 → 该行不显示（如停止的监听器没有
 * 运行状态 / 进程号 / 启动日期时间）。
 *
 * 快照属性键实测（INQUIRE_LISTENER + INQUIRE_LISTENER_STATUS 合并）：
 * MQCACH_LISTENER_NAME / MQCACH_LISTENER_DESC / MQIACH_LISTENER_CONTROL /
 * MQIACH_PORT / MQCACH_IP_ADDRESS / MQIACH_BACKLOG / MQCA_ALTERATION_DATE / TIME，
 * 运行中追加 MQIACH_LISTENER_STATUS / MQIACF_PROCESS_ID / START_DATE / START_TIME。
 * 传输协议参数（1501）Java 客户端反查成别名键 MQIACH_FIRST（= MQIACH_XMIT_PROTOCOL_TYPE），
 * 两个键都兜。
 */
internal val listenerPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "listener_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "listener_prop_name_listener_name",
                descKey = "listener_prop_desc_listener_name",
                labelEn = "Listener name",
                keys = listOf("MQCACH_LISTENER_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_listener_desc",
                descKey = "listener_prop_desc_listener_desc",
                labelEn = "Description",
                keys = listOf("MQCACH_LISTENER_DESC", "MQCACH_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_control",
                descKey = "listener_prop_desc_control",
                labelEn = "Control",
                keys = listOf("MQIACH_LISTENER_CONTROL"),
                // MQSVC_ 族内 CONTROL / STATUS / TYPE 三组常量同值碰撞（如 0 =
                // CONTROL_MANUAL 也 = TYPE_COMMAND「命令」），前缀取到子族消歧。
                valuePrefix = "MQSVC_CONTROL",
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_transport_type",
                descKey = "listener_prop_desc_transport_type",
                labelEn = "Transmission protocol",
                // 1501 = MQIACH_XMIT_PROTOCOL_TYPE，Java 客户端反查为别名 MQIACH_FIRST。
                keys = listOf("MQIACH_XMIT_PROTOCOL_TYPE", "MQIACH_FIRST"),
                valuePrefix = "MQXPT",
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_port",
                descKey = "listener_prop_desc_port",
                labelEn = "Port",
                keys = listOf("MQIACH_PORT"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_ip_address",
                descKey = "listener_prop_desc_ip_address",
                labelEn = "IP address",
                keys = listOf("MQCACH_IP_ADDRESS"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_backlog",
                descKey = "listener_prop_desc_backlog",
                labelEn = "Backlog",
                keys = listOf("MQIACH_BACKLOG"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_listener_status",
                descKey = "listener_prop_desc_listener_status",
                labelEn = "Listener status",
                keys = listOf("MQIACH_LISTENER_STATUS"),
                valuePrefix = "MQSVC_STATUS",
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_start_date",
                descKey = "listener_prop_desc_start_date",
                labelEn = "Start date",
                keys = listOf("MQCACH_LISTENER_START_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_start_time",
                descKey = "listener_prop_desc_start_time",
                labelEn = "Start time",
                keys = listOf("MQCACH_LISTENER_START_TIME"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_process_id",
                descKey = "listener_prop_desc_process_id",
                labelEn = "Process ID",
                keys = listOf("MQIACF_PROCESS_ID"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_alteration_date",
                descKey = "listener_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "listener_prop_name_alteration_time",
                descKey = "listener_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

/**
 * 监听器快照 →（分组字段表内命中 + 「全部属性」兜底）的展示行。
 */
internal fun resolveListenerPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = listenerPropertyGroups,
        allGroupTitleKey = "listener_props_group_all",
    )
