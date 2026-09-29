package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * 订阅属性分组表：对齐 MQ Explorer 的 Subscription properties 对话框（General
 * 单页，字段序按 INQUIRE_SUBSCRIPTION 响应文档；**全部文案走 i18n 键**，键规则
 * `sub_prop_name_<字段>` / `sub_prop_desc_<字段>`，枚举值走 `ibm_mq_attrs` 表的
 * `<常量>.title`（MQ Explorer 同款 zh 文案））。
 *
 * 复用通用类型（[ObjectPropertyField] / [ObjectPropertyGroup] /
 * [ResolvedObjectProperty]）；键不在快照里 → 该行不显示。
 *
 * 快照属性键实测（INQUIRE_SUBSCRIPTION）：27 个，其中 3 个是 MQBACF_* 字节 ID
 * （SUB_ID / ACCOUNTING_TOKEN / DESTINATION_CORREL_ID，解码即乱码）——见
 * [resolveSubscriptionPropertyGroups] 的兜底过滤。
 *
 * 坑位（同 listener / topic）：
 * - 不走 DM 枚举归属（按 Queue 模型生成，对订阅键会误判），一律 valuePrefix /
 *   valueConstMap 反查；
 * - 客户端缺 4 个枚举族常量：`MQSAT_*`（选择器类型）、`MQDUR_*`、`MQEXPIRY_*`、
 *   `MQSCOPE_SUBSCRIPTION`，IBM 文档真名是 MQSELTYPE_* / MQSUB_DURABLE_* /
 *   MQTSCOPE_* / MQRU_*（已收录），到期 -1 = MQEXPIRY_NOLIMIT 走 valueConstMap。
 */
internal val subscriptionPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "sub_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "sub_prop_name_sub_name",
                descKey = "sub_prop_desc_sub_name",
                labelEn = "Subscription name",
                keys = listOf("MQCACF_SUB_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_sub_type",
                descKey = "sub_prop_desc_sub_type",
                labelEn = "Type",
                keys = listOf("MQIACF_SUB_TYPE"),
                valuePrefix = "MQSUBTYPE",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_topic_name",
                descKey = "sub_prop_desc_topic_name",
                labelEn = "Topic name",
                keys = listOf("MQCA_TOPIC_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_topic_string",
                descKey = "sub_prop_desc_topic_string",
                labelEn = "Topic string",
                keys = listOf("MQCA_TOPIC_STRING"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_sub_user_id",
                descKey = "sub_prop_desc_sub_user_id",
                labelEn = "User",
                keys = listOf("MQCACF_SUB_USER_ID"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_destination_class",
                descKey = "sub_prop_desc_destination_class",
                labelEn = "Destination class",
                keys = listOf("MQIACF_DESTINATION_CLASS"),
                valuePrefix = "MQDC",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_destination",
                descKey = "sub_prop_desc_destination",
                labelEn = "Destination name",
                keys = listOf("MQCACF_DESTINATION"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_destination_q_mgr",
                descKey = "sub_prop_desc_destination_q_mgr",
                labelEn = "Destination queue manager",
                keys = listOf("MQCACF_DESTINATION_Q_MGR"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_durable",
                descKey = "sub_prop_desc_durable",
                labelEn = "Durable",
                keys = listOf("MQIACF_DURABLE_SUBSCRIPTION"),
                // MQSUB_DURABLE 族内 YES=ALLOWED=1、NO=INHIBITED=2 同值碰撞，
                // 前缀反查会命中「允许/禁止」，精确映射到 Durable 语义的「是/否」。
                valueConstMap = mapOf(
                    0 to "MQSUB_DURABLE_NO",
                    1 to "MQSUB_DURABLE_YES",
                    2 to "MQSUB_DURABLE_NO",
                ),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_expiry",
                descKey = "sub_prop_desc_expiry",
                labelEn = "Expiry",
                keys = listOf("MQIACF_EXPIRY"),
                // 客户端无 MQEXPIRY_NOLIMIT 常量（-1 = 永不过期）。
                valueConstMap = mapOf(-1 to "MQEXPIRY_NOLIMIT"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_selector_type",
                descKey = "sub_prop_desc_selector_type",
                labelEn = "Selector type",
                keys = listOf("MQIACF_SELECTOR_TYPE"),
                valuePrefix = "MQSELTYPE",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_sub_selector",
                descKey = "sub_prop_desc_sub_selector",
                labelEn = "Selector",
                keys = listOf("MQCACF_SUB_SELECTOR"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_pub_priority",
                descKey = "sub_prop_desc_pub_priority",
                labelEn = "Publication priority",
                keys = listOf("MQIACF_PUB_PRIORITY"),
                valuePrefix = "MQPRI",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_pubsub_properties",
                descKey = "sub_prop_desc_pubsub_properties",
                labelEn = "Properties",
                keys = listOf("MQIACF_PUBSUB_PROPERTIES"),
                valuePrefix = "MQPSPROP",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_request_only",
                descKey = "sub_prop_desc_request_only",
                labelEn = "Request only",
                keys = listOf("MQIACF_REQUEST_ONLY"),
                valuePrefix = "MQRU",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_subscription_scope",
                descKey = "sub_prop_desc_subscription_scope",
                labelEn = "Scope",
                keys = listOf("MQIACF_SUBSCRIPTION_SCOPE"),
                valuePrefix = "MQTSCOPE",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_sub_level",
                descKey = "sub_prop_desc_sub_level",
                labelEn = "Subscription level",
                keys = listOf("MQIACF_SUB_LEVEL"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_variable_user_id",
                descKey = "sub_prop_desc_variable_user_id",
                labelEn = "Variable user ID",
                keys = listOf("MQIACF_VARIABLE_USER_ID"),
                valuePrefix = "MQVU",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_wildcard_schema",
                descKey = "sub_prop_desc_wildcard_schema",
                labelEn = "Wildcard use",
                keys = listOf("MQIACF_WILDCARD_SCHEMA"),
                valuePrefix = "MQWS",
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_appl_identity_data",
                descKey = "sub_prop_desc_appl_identity_data",
                labelEn = "Application identity data",
                keys = listOf("MQCACF_APPL_IDENTITY_DATA"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_sub_user_data",
                descKey = "sub_prop_desc_sub_user_data",
                labelEn = "User data",
                keys = listOf("MQCACF_SUB_USER_DATA"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_creation_date",
                descKey = "sub_prop_desc_creation_date",
                labelEn = "Creation date",
                keys = listOf("MQCA_CREATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_creation_time",
                descKey = "sub_prop_desc_creation_time",
                labelEn = "Creation time",
                keys = listOf("MQCA_CREATION_TIME"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_alteration_date",
                descKey = "sub_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "sub_prop_name_alteration_time",
                descKey = "sub_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

/** 订阅快照里展示为乱码的字节 ID 类参数（解码无意义，兜底组过滤掉）。 */
private val SUB_BINARY_KEYS = setOf(
    "MQBACF_SUB_ID",
    "MQBACF_ACCOUNTING_TOKEN",
    "MQBACF_DESTINATION_CORREL_ID",
)

/**
 * 订阅快照 →（分组字段表内命中 + 「全部属性」兜底）的展示行。
 * 结构与 [resolveQueuePropertyGroups] 一致；枚举归属不用 DM 表（queue 模型对
 * 订阅键会误判），一律 valuePrefix / valueConstMap 反查。
 */
internal fun resolveSubscriptionPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> {
    val attrs = snapshot.attributes
    val usedKeys = mutableSetOf<String>()

    fun resolve(field: ObjectPropertyField): ResolvedObjectProperty? {
        val key = field.keys.firstOrNull { it in attrs }
        if (key == null) {
            if (!field.showIfMissing) return null
            return ResolvedObjectProperty(
                nameKey = field.nameKey,
                descKey = field.descKey,
                labelEn = field.labelEn,
                valueKey = "queue_value_none",
                valueFallbackKey = null,
                rawValue = " ",
                rawConstName = null,
            )
        }
        usedKeys += key
        val raw = attrs[key]
        val rawText = raw?.toString()?.trim() ?: ""
        val constName = if (raw is Int) {
            field.valueConstMap[raw]
                ?: field.valuePrefix?.let { MqAttributeNameMapper.lookupName(raw, it) }
        } else {
            null
        }
        return ResolvedObjectProperty(
            nameKey = field.nameKey,
            descKey = field.descKey,
            labelEn = field.labelEn,
            valueKey = constName?.let(::curatedValueKey),
            valueFallbackKey = constName?.let { "$it.title" },
            rawValue = rawText.ifBlank { " " },
            rawConstName = constName,
        )
    }

    val grouped = subscriptionPropertyGroups.map { group ->
        group to group.fields.mapNotNull(::resolve)
    }.filter { (_, rows) -> rows.isNotEmpty() }

    // 「全部属性」兜底：字节 ID 类参数展示为乱码，过滤不丢内容意义上的数据
    // （SubId 等本就是内部标识）；名字直接查 `MQXXX.title` i18n 键，枚举不猜。
    val other = attrs.filterKeys { it !in usedKeys && it !in SUB_BINARY_KEYS }
        .map { (k, v) ->
            ResolvedObjectProperty(
                nameKey = "$k.title",
                descKey = "",
                labelEn = k,
                valueKey = null,
                valueFallbackKey = null,
                rawValue = v?.toString()?.trim()?.ifBlank { " " } ?: "",
                rawConstName = null,
            )
        }
    return if (other.isEmpty()) grouped else grouped + (ObjectPropertyGroup("sub_props_group_all", emptyList()) to other)
}
