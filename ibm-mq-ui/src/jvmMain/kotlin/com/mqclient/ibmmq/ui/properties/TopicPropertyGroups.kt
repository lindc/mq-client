package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * 主题属性分组表：对齐 MQ Explorer 的 Topic properties 对话框（General 单页，
 * 字段序按 DmTopic.xml；**全部文案走 i18n 键**，键规则
 * `topic_prop_name_<字段>` / `topic_prop_desc_<字段>`，枚举值走 `ibm_mq_attrs`
 * 表的 `<常量>.title`（MQ Explorer 同款 zh 文案））。
 *
 * 复用 queue / listener 对话框的通用类型（[ObjectPropertyField] /
 * [ObjectPropertyGroup] / [ResolvedObjectProperty]）；键不在快照里 → 该行不显示。
 *
 * 快照属性键实测（INQUIRE_TOPIC 全量应答）：27 个，见下方字段表。
 *
 * 坑位（与 listener 同源，见 [resolveTopicPropertyGroups] 注释）：
 * - 不走 DM 枚举归属（按 Queue 模型生成，对 topic 键会误判）；
 * - `MQTA_*` 一族常量服务 4 个属性（通配符 / 发布 / 预订 / 代理预订），同值
 *   碰撞（如 2 = PASSTHRU 也 = PUB_ALLOWED），前缀必须取到子族或精确常量；
 * - 通配符操作是**单常量枚举**（MQTA_PASSTHRU 无子族后缀），valuePrefix 直接
 *   传常量名，依赖 [MqAttributeNameMapper.lookupName] 的精确匹配分支。
 */
internal val topicPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "ibm_mq_topic_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_topic_name",
                descKey = "ibm_mq_topic_prop_desc_topic_name",
                labelEn = "Topic name",
                keys = listOf("MQCA_TOPIC_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_topic_type",
                descKey = "ibm_mq_topic_prop_desc_topic_type",
                labelEn = "Topic type",
                keys = listOf("MQIA_TOPIC_TYPE"),
                valuePrefix = "MQTOPT",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_qsg_disp",
                descKey = "ibm_mq_topic_prop_desc_qsg_disp",
                labelEn = "QSG disposition",
                keys = listOf("MQIA_QSG_DISP"),
                valuePrefix = "MQQSGD",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_topic_string",
                descKey = "ibm_mq_topic_prop_desc_topic_string",
                labelEn = "Topic string",
                keys = listOf("MQCA_TOPIC_STRING"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_topic_desc",
                descKey = "ibm_mq_topic_prop_desc_topic_desc",
                labelEn = "Description",
                keys = listOf("MQCA_TOPIC_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_durable_sub",
                descKey = "ibm_mq_topic_prop_desc_durable_sub",
                labelEn = "Durable subscriptions",
                keys = listOf("MQIA_DURABLE_SUB"),
                valuePrefix = "MQSUB_DURABLE",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_wildcard_operation",
                descKey = "ibm_mq_topic_prop_desc_wildcard_operation",
                labelEn = "Wildcard operation",
                keys = listOf("MQIA_WILDCARD_OPERATION"),
                // 单常量枚举：MQTA_PASSTHRU=2 / MQTA_BLOCK=1（精确匹配）。
                valuePrefix = "MQTA_PASSTHRU",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_npm_delivery",
                descKey = "ibm_mq_topic_prop_desc_npm_delivery",
                labelEn = "NPM delivery",
                keys = listOf("MQIA_NPM_DELIVERY"),
                valuePrefix = "MQDLV",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_pm_delivery",
                descKey = "ibm_mq_topic_prop_desc_pm_delivery",
                labelEn = "PM delivery",
                keys = listOf("MQIA_PM_DELIVERY"),
                valuePrefix = "MQDLV",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_inhibit_pub",
                descKey = "ibm_mq_topic_prop_desc_inhibit_pub",
                labelEn = "Publication",
                keys = listOf("MQIA_INHIBIT_PUB"),
                valuePrefix = "MQTA_PUB",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_inhibit_sub",
                descKey = "ibm_mq_topic_prop_desc_inhibit_sub",
                labelEn = "Subscription",
                keys = listOf("MQIA_INHIBIT_SUB"),
                valuePrefix = "MQTA_SUB",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_pub_scope",
                descKey = "ibm_mq_topic_prop_desc_pub_scope",
                labelEn = "Publication scope",
                keys = listOf("MQIA_PUB_SCOPE"),
                valuePrefix = "MQSCOPE",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_sub_scope",
                descKey = "ibm_mq_topic_prop_desc_sub_scope",
                labelEn = "Subscription scope",
                keys = listOf("MQIA_SUB_SCOPE"),
                valuePrefix = "MQSCOPE",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_proxy_sub",
                descKey = "ibm_mq_topic_prop_desc_proxy_sub",
                labelEn = "Proxy subscription behavior",
                keys = listOf("MQIA_PROXY_SUB"),
                valuePrefix = "MQTA_PROXY_SUB",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_cluster_name",
                descKey = "ibm_mq_topic_prop_desc_cluster_name",
                labelEn = "Cluster name",
                keys = listOf("MQCA_CLUSTER_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_cluster_pub_route",
                descKey = "ibm_mq_topic_prop_desc_cluster_pub_route",
                labelEn = "Cluster route",
                keys = listOf("MQIA_CLUSTER_PUB_ROUTE"),
                valuePrefix = "MQCLROUTE",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_multicast",
                descKey = "ibm_mq_topic_prop_desc_multicast",
                labelEn = "Multicast",
                keys = listOf("MQIA_MULTICAST"),
                valuePrefix = "MQMC",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_model_durable_q",
                descKey = "ibm_mq_topic_prop_desc_model_durable_q",
                labelEn = "Model durable queue",
                keys = listOf("MQCA_MODEL_DURABLE_Q"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_model_non_durable_q",
                descKey = "ibm_mq_topic_prop_desc_model_non_durable_q",
                labelEn = "Model non-durable queue",
                keys = listOf("MQCA_MODEL_NON_DURABLE_Q"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_comm_info_name",
                descKey = "ibm_mq_topic_prop_desc_comm_info_name",
                labelEn = "Communication information",
                keys = listOf("MQCA_COMM_INFO_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_use_dead_letter_q",
                descKey = "ibm_mq_topic_prop_desc_use_dead_letter_q",
                labelEn = "Use DLQ",
                keys = listOf("MQIA_USE_DEAD_LETTER_Q"),
                valuePrefix = "MQUSEDLQ",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_def_persistence",
                descKey = "ibm_mq_topic_prop_desc_def_persistence",
                labelEn = "Default persistence",
                keys = listOf("MQIA_TOPIC_DEF_PERSISTENCE"),
                valuePrefix = "MQPER",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_def_priority",
                descKey = "ibm_mq_topic_prop_desc_def_priority",
                labelEn = "Default priority",
                keys = listOf("MQIA_DEF_PRIORITY"),
                valuePrefix = "MQPRI",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_def_put_response_type",
                descKey = "ibm_mq_topic_prop_desc_def_put_response_type",
                labelEn = "Default put response type",
                keys = listOf("MQIA_DEF_PUT_RESPONSE_TYPE"),
                valuePrefix = "MQPRT",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_custom",
                descKey = "ibm_mq_topic_prop_desc_custom",
                labelEn = "Custom",
                keys = listOf("MQCA_CUSTOM"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_alteration_date",
                descKey = "ibm_mq_topic_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_topic_prop_name_alteration_time",
                descKey = "ibm_mq_topic_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

/**
 * 主题快照 →（分组字段表内命中 + 「全部属性」兜底）的展示行。
 */
internal fun resolveTopicPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = topicPropertyGroups,
        allGroupTitleKey = "ibm_mq_topic_props_group_all",
    )
