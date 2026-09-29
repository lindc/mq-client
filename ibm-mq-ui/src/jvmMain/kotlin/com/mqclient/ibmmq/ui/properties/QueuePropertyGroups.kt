package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * 队列属性分组表：分组 / 字段中英文名 / 字段顺序对齐 MQ Explorer 的 Local Queue
 * properties 对话框（内容提取自本机 MQ Explorer 9.4.5 官方帮助文档
 * `e_properties_queues.html` 的 en / zh-CN 双语版本；**全部文案走 i18n 键**，
 * 键规则：queue_prop_name_<mqsc 小写> / queue_prop_desc_<mqsc 小写> /
 * queue_value_<常量去前缀小写>）。
 *
 * [QueuePropertyField.keys] 是 PCF 快照（[MqObjectSnapshot.attributes]）里该字段的
 * 候选键（快照键由 [MqAttributeNameMapper.getName] 反查生成）。键不在快照里 → 该行
 * 不显示；快照里未被分组的属性统一进「全部属性」组兜底，不丢数据。
 * 值为整型枚举时按 [QueuePropertyField.valuePrefix] 反查 MQ 常量名，再取
 * `queue_value_*` 的 i18n 文案（缺失回落常量去前缀的英文展开）。
 *
 * allclient 10.0.0.5 缺失常量的字段（CFSTRUCT / CLWLRANK / CLWLPRTY / QSVCIEV /
 * QSVCINT / RETENTION 等）不在表内或在快照中天然缺席。
 */
internal data class QueuePropertyField(
    /** 属性名 i18n 键。 */
    val nameKey: String,
    /** 说明 i18n 键（en 表可缺，回落中文）。 */
    val descKey: String,
    /** 英文兜底名（i18n 键缺失时显示）。 */
    val labelEn: String,
    /** PCF 快照候选键。 */
    val keys: List<String>,
    /** 整型枚举值反查可读名的前缀族。 */
    val valuePrefix: String? = null,
)

internal data class QueuePropertyGroup(
    val titleKey: String,
    val fields: List<QueuePropertyField>,
)

internal val queuePropertyGroups: List<QueuePropertyGroup> = listOf(
    QueuePropertyGroup(
        titleKey = "queue_props_group_general",
        fields = listOf(
            QueuePropertyField(
                nameKey = "queue_prop_name_q_name",
                descKey = "queue_prop_desc_q_name",
                labelEn = "Queue name",
                keys = listOf("MQCA_Q_NAME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_q_type",
                descKey = "queue_prop_desc_q_type",
                labelEn = "Queue type",
                keys = listOf("MQIA_Q_TYPE"),
                valuePrefix = "MQQT",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_qsg_disp",
                descKey = "queue_prop_desc_qsg_disp",
                labelEn = "QSG disposition",
                keys = listOf("MQIA_QSG_DISP"),
                valuePrefix = "MQQSGD",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_q_desc",
                descKey = "queue_prop_desc_q_desc",
                labelEn = "Description",
                keys = listOf("MQCA_Q_DESC"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_inhibit_put",
                descKey = "queue_prop_desc_inhibit_put",
                labelEn = "Put messages",
                keys = listOf("MQIA_INHIBIT_PUT"),
                valuePrefix = "MQQA",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_inhibit_get",
                descKey = "queue_prop_desc_inhibit_get",
                labelEn = "Get messages",
                keys = listOf("MQIA_INHIBIT_GET"),
                valuePrefix = "MQQA",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_def_priority",
                descKey = "queue_prop_desc_def_priority",
                labelEn = "Default priority",
                keys = listOf("MQIA_DEF_PRIORITY"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_def_persistence",
                descKey = "queue_prop_desc_def_persistence",
                labelEn = "Default persistence",
                keys = listOf("MQIA_DEF_PERSISTENCE"),
                valuePrefix = "MQPER",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_scope",
                descKey = "queue_prop_desc_scope",
                labelEn = "Scope",
                keys = listOf("MQIA_SCOPE"),
                valuePrefix = "MQSCO",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_usage",
                descKey = "queue_prop_desc_usage",
                labelEn = "Usage",
                keys = listOf("MQIA_USAGE"),
                valuePrefix = "MQUS",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_base_type",
                descKey = "queue_prop_desc_base_type",
                labelEn = "Base type",
                keys = listOf("MQIA_BASE_TYPE"),
                valuePrefix = "MQOT",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_remote_q_name",
                descKey = "queue_prop_desc_remote_q_name",
                labelEn = "Remote queue",
                keys = listOf("MQCA_REMOTE_Q_NAME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_remote_q_mgr_name",
                descKey = "queue_prop_desc_remote_q_mgr_name",
                labelEn = "Remote queue manager",
                keys = listOf("MQCA_REMOTE_Q_MGR_NAME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_xmit_q_name",
                descKey = "queue_prop_desc_xmit_q_name",
                labelEn = "Transmission queue",
                keys = listOf("MQCA_XMIT_Q_NAME"),
                valuePrefix = null,
            ),
        ),
    ),
    QueuePropertyGroup(
        titleKey = "queue_props_group_extended",
        fields = listOf(
            QueuePropertyField(
                nameKey = "queue_prop_name_max_q_depth",
                descKey = "queue_prop_desc_max_q_depth",
                labelEn = "Max queue depth",
                keys = listOf("MQIA_MAX_Q_DEPTH"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_max_msg_length",
                descKey = "queue_prop_desc_max_msg_length",
                labelEn = "Max message length",
                keys = listOf("MQIA_MAX_MSG_LENGTH"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_shareability",
                descKey = "queue_prop_desc_shareability",
                labelEn = "Shareable",
                keys = listOf("MQIA_SHAREABILITY"),
                valuePrefix = "MQSHA",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_def_bind",
                descKey = "queue_prop_desc_def_bind",
                labelEn = "Default binding",
                keys = listOf("MQIA_DEF_BIND"),
                valuePrefix = "MQBND",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_msg_delivery_sequence",
                descKey = "queue_prop_desc_msg_delivery_sequence",
                labelEn = "Msg delivery sequence",
                keys = listOf("MQIA_MSG_DELIVERY_SEQUENCE"),
                valuePrefix = "MQMDS",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_def_input_open_option",
                descKey = "queue_prop_desc_def_input_open_option",
                labelEn = "Default open input option",
                keys = listOf("MQIA_DEF_INPUT_OPEN_OPTION"),
                valuePrefix = "MQOO",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_property_control",
                descKey = "queue_prop_desc_property_control",
                labelEn = "Property control",
                keys = listOf("MQIA_PROPERTY_CONTROL"),
                valuePrefix = "MQPROP",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_custom",
                descKey = "queue_prop_desc_custom",
                labelEn = "Custom",
                keys = listOf("MQCA_CUSTOM"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_retention_interval",
                descKey = "queue_prop_desc_retention_interval",
                labelEn = "Retention interval",
                keys = listOf("MQIA_RETENTION_INTERVAL"),
                valuePrefix = null,
            ),
        ),
    ),
    QueuePropertyGroup(
        titleKey = "queue_props_group_cluster",
        fields = listOf(
            QueuePropertyField(
                nameKey = "queue_prop_name_cluster_name",
                descKey = "queue_prop_desc_cluster_name",
                labelEn = "Cluster name",
                keys = listOf("MQCA_CLUSTER_NAME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_cluster_namelist",
                descKey = "queue_prop_desc_cluster_namelist",
                labelEn = "Cluster namelist",
                keys = listOf("MQCA_CLUSTER_NAMELIST"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_def_bind",
                descKey = "queue_prop_desc_def_bind",
                labelEn = "Default binding",
                keys = listOf("MQIA_DEF_BIND"),
                valuePrefix = "MQBND",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_clwl_useq",
                descKey = "queue_prop_desc_clwl_useq",
                labelEn = "Cluster workload use queue",
                keys = listOf("MQIA_CLWL_USEQ"),
                valuePrefix = "MQCLWLUSEQ",
            ),
        ),
    ),
    QueuePropertyGroup(
        titleKey = "queue_props_group_triggering",
        fields = listOf(
            QueuePropertyField(
                nameKey = "queue_prop_name_trigger_control",
                descKey = "queue_prop_desc_trigger_control",
                labelEn = "Trigger control",
                keys = listOf("MQIA_TRIGGER_CONTROL"),
                valuePrefix = "MQTC",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_trigger_type",
                descKey = "queue_prop_desc_trigger_type",
                labelEn = "Trigger type",
                keys = listOf("MQIA_TRIGGER_TYPE"),
                valuePrefix = "MQTRG",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_trigger_depth",
                descKey = "queue_prop_desc_trigger_depth",
                labelEn = "Trigger depth",
                keys = listOf("MQIA_TRIGGER_DEPTH"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_trigger_msg_priority",
                descKey = "queue_prop_desc_trigger_msg_priority",
                labelEn = "Trigger message priority",
                keys = listOf("MQIA_TRIGGER_MSG_PRIORITY"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_trigger_data",
                descKey = "queue_prop_desc_trigger_data",
                labelEn = "Trigger data",
                keys = listOf("MQCA_TRIGGER_DATA"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_process_name",
                descKey = "queue_prop_desc_process_name",
                labelEn = "Process name",
                keys = listOf("MQCA_PROCESS_NAME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_initiation_q_name",
                descKey = "queue_prop_desc_initiation_q_name",
                labelEn = "Initiation queue",
                keys = listOf("MQCA_INITIATION_Q_NAME"),
                valuePrefix = null,
            ),
        ),
    ),
    QueuePropertyGroup(
        titleKey = "queue_props_group_events",
        fields = listOf(
            QueuePropertyField(
                nameKey = "queue_prop_name_q_depth_max_event",
                descKey = "queue_prop_desc_q_depth_max_event",
                labelEn = "Queue full event",
                keys = listOf("MQIA_Q_DEPTH_MAX_EVENT"),
                valuePrefix = "MQEVO",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_q_depth_high_event",
                descKey = "queue_prop_desc_q_depth_high_event",
                labelEn = "Queue depth high event",
                keys = listOf("MQIA_Q_DEPTH_HIGH_EVENT"),
                valuePrefix = "MQEVO",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_q_depth_high_limit",
                descKey = "queue_prop_desc_q_depth_high_limit",
                labelEn = "Queue depth high limit",
                keys = listOf("MQIA_Q_DEPTH_HIGH_LIMIT"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_q_depth_low_event",
                descKey = "queue_prop_desc_q_depth_low_event",
                labelEn = "Queue depth low event",
                keys = listOf("MQIA_Q_DEPTH_LOW_EVENT"),
                valuePrefix = "MQEVO",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_q_depth_low_limit",
                descKey = "queue_prop_desc_q_depth_low_limit",
                labelEn = "Queue depth low limit",
                keys = listOf("MQIA_Q_DEPTH_LOW_LIMIT"),
                valuePrefix = null,
            ),
        ),
    ),
    QueuePropertyGroup(
        titleKey = "queue_props_group_storage",
        fields = listOf(
            QueuePropertyField(
                nameKey = "queue_prop_name_backout_req_q_name",
                descKey = "queue_prop_desc_backout_req_q_name",
                labelEn = "Backout requeue queue",
                keys = listOf("MQCA_BACKOUT_REQ_Q_NAME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_backout_threshold",
                descKey = "queue_prop_desc_backout_threshold",
                labelEn = "Backout threshold",
                keys = listOf("MQIA_BACKOUT_THRESHOLD"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_harden_get_backout",
                descKey = "queue_prop_desc_harden_get_backout",
                labelEn = "Hardened backout",
                keys = listOf("MQIA_HARDEN_GET_BACKOUT"),
                valuePrefix = "MQHA",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_npm_class",
                descKey = "queue_prop_desc_npm_class",
                labelEn = "Non-persistent message class",
                keys = listOf("MQIA_NPM_CLASS"),
                valuePrefix = "MQNPM",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_storage_class",
                descKey = "queue_prop_desc_storage_class",
                labelEn = "Storage class",
                keys = listOf("MQCA_STORAGE_CLASS"),
                valuePrefix = null,
            ),
        ),
    ),
    QueuePropertyGroup(
        titleKey = "queue_props_group_statistics",
        fields = listOf(
            QueuePropertyField(
                nameKey = "queue_prop_name_creation_date",
                descKey = "queue_prop_desc_creation_date",
                labelEn = "Creation date",
                keys = listOf("MQCA_CREATION_DATE"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_creation_time",
                descKey = "queue_prop_desc_creation_time",
                labelEn = "Creation time",
                keys = listOf("MQCA_CREATION_TIME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_alteration_date",
                descKey = "queue_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_alteration_time",
                descKey = "queue_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_current_q_depth",
                descKey = "queue_prop_desc_current_q_depth",
                labelEn = "Current queue depth",
                keys = listOf("MQIA_CURRENT_Q_DEPTH"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_open_input_count",
                descKey = "queue_prop_desc_open_input_count",
                labelEn = "Open input count",
                keys = listOf("MQIA_OPEN_INPUT_COUNT"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_open_output_count",
                descKey = "queue_prop_desc_open_output_count",
                labelEn = "Open output count",
                keys = listOf("MQIA_OPEN_OUTPUT_COUNT"),
                valuePrefix = null,
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_monitoring_q",
                descKey = "queue_prop_desc_monitoring_q",
                labelEn = "Monitoring",
                keys = listOf("MQIA_MONITORING_Q"),
                valuePrefix = "MQMON",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_statistics_q",
                descKey = "queue_prop_desc_statistics_q",
                labelEn = "Statistics",
                keys = listOf("MQIA_STATISTICS_Q"),
                valuePrefix = "MQSTS",
            ),
            QueuePropertyField(
                nameKey = "queue_prop_name_accounting_q",
                descKey = "queue_prop_desc_accounting_q",
                labelEn = "Accounting",
                keys = listOf("MQIA_ACCOUNTING_Q"),
                valuePrefix = "MQACCTQ",
            ),
        ),
    ),
)

/**
 * 快照属性 →（分组字段表内命中 + 「全部属性」兜底）的展示行。
 * 名称 / 值在渲染期经 i18n 查表（见 [QueuePropertiesContent]）。
 */
internal fun resolveQueuePropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<QueuePropertyGroup, List<ResolvedQueueProperty>>> {
    val attrs = snapshot.attributes
    val usedKeys = mutableSetOf<String>()

    fun resolve(field: QueuePropertyField): ResolvedQueueProperty? {
        val key = field.keys.firstOrNull { it in attrs } ?: return null
        usedKeys += key
        val raw = attrs[key]
        val rawText = raw?.toString()?.trim() ?: ""
        // 值的 i18n：整型枚举 → 反查常量名 → queue_value_<常量去 MQ 前缀小写>。
        val constName = if (raw is Int) {
            field.valuePrefix?.let { MqAttributeNameMapper.lookupName(raw, it) }
        } else {
            null
        }
        val valueKey = constName?.lowercase()
            ?.removePrefix("mq")?.removePrefix("qa_")?.removePrefix("ia_")
            ?.removePrefix("ca_")?.removePrefix("ot_")?.removePrefix("us_")
            ?.removePrefix("qt_")?.removePrefix("per_")?.removePrefix("sco_")
            ?.removePrefix("bnd_")?.removePrefix("mds_")?.removePrefix("evo_")
            ?.removePrefix("tc_")?.removePrefix("trg_")?.removePrefix("ha_")
            ?.removePrefix("npm_")?.removePrefix("prop_")?.removePrefix("clwluseq_")
            ?.let { "queue_value_$it" }
        return ResolvedQueueProperty(
            nameKey = field.nameKey,
            descKey = field.descKey,
            labelEn = field.labelEn,
            valueKey = valueKey,
            rawValue = rawText.ifBlank { " " },
            rawConstName = constName,
        )
    }

    val grouped = queuePropertyGroups.map { group ->
        group to group.fields.mapNotNull(::resolve)
    }.filter { (_, rows) -> rows.isNotEmpty() }

    val other = attrs.filterKeys { it !in usedKeys }
        .map { (k, v) -> ResolvedQueueProperty(nameKey = k, descKey = "", labelEn = k, valueKey = null, rawValue = v?.toString()?.trim() ?: "", rawConstName = null) }
    return if (other.isEmpty()) grouped else grouped + (QueuePropertyGroup("queue_props_group_all", emptyList()) to other)
}

/** 渲染就绪的属性行：名称 / 说明均为 i18n 键，由组合期 stringResource 解析。 */
internal data class ResolvedQueueProperty(
    val nameKey: String,
    val descKey: String,
    val labelEn: String,
    /** 值的 i18n 键；null = 显示原始值。 */
    val valueKey: String?,
    val rawValue: String,
    val rawConstName: String?,
)
