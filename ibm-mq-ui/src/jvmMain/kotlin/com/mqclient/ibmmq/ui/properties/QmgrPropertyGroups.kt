package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqAttributeDisplay
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * 队列管理器属性分组表：分组 / 字段中英文名 / 字段顺序对齐 MQ Explorer 的 Queue
 * Manager properties 对话框（内容提取自本机 MQ Explorer 9.4.5 官方帮助文档
 * `e_properties_qmanager.html` 的 en / zh-CN 双语版本；**全部文案走 i18n 键**，
 * 键规则：qmgr_prop_name_<mqsc 小写> / qmgr_prop_desc_<mqsc 小写> /
 * queue_value_<常量去前缀小写>，分组标题 qmgr_props_group_*）。
 *
 * 与 [qmgrPropertyGroups] 对应的 MQ Explorer 分页：
 * General / Extended / Cluster / Repository / Communication / Events / SSL /
 * Statistics / Online monitoring / Statistics monitoring / Accounting monitoring /
 * Publish-Subscribe / Channels。Exits、Log、TCP 库、XA、Installable services 等
 * 纯 qm.ini 分页不做 —— 它们不经 PCF 返回，远程连接永远拿不到值。
 *
 * [ObjectPropertyField.keys] 是 PCF 快照（[MqObjectSnapshot.attributes]）里该字段的
 * 候选键（快照键由 [com.mqclient.ibmmq.pcf.MqAttributeNameMapper.getName] 反查生成）。
 * 键不在快照里 → 该行不显示（INQUIRE Q MGR 的应答天然按平台裁剪，z/OS 专属属性
 * 如 SYNCPT / IGQ / MARKINT 在多平台上不会出现）；快照里未被分组的属性统一进
 * 「全部属性」组兜底，不丢数据。枚举值优先经 DM 表（DmQueueManager，见
 * [com.mqclient.ibmmq.pcf.MqQMgrAttrEnums]）归属常量名，运行状态（MQIASY_*）与
 * Suite B（MQSUITEB_*）等 allclient 未收录的族走 [ObjectPropertyField.valueConstMap]。
 */
internal val qmgrPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_q_mgr_name",
                descKey = "ibm_mq_qmgr_prop_desc_q_mgr_name",
                labelEn = "Queue manager name",
                keys = listOf("MQCA_Q_MGR_NAME"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_platform",
                descKey = "ibm_mq_qmgr_prop_desc_platform",
                labelEn = "Platform",
                keys = listOf("MQIA_PLATFORM"),
                valuePrefix = "MQPL",
            ),
            // 运行状态来自 INQUIRE_Q_MGR_STATUS（服务层并进快照）；MQIASY_STATUS_*
            // 常量 allclient 未收录，只能用策展映射。
            // 键名对齐表里既有的 `mqiacf_*`（不是 `iacf_*`）—— 原先写漏了 `mq`，
            // 导致这个属性一直渲染成 key 字符串本身（由 check_i18n_refs.py 检出）。
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_mqiacf_q_mgr_status",
                descKey = "ibm_mq_qmgr_prop_desc_mqiacf_q_mgr_status",
                labelEn = "Queue manager status",
                keys = listOf("MQIACF_Q_MGR_STATUS"),
                valuePrefix = null,
                valueConstMap = mapOf(
                    1 to "MQIASY_STATUS_STARTING",
                    2 to "MQIASY_STATUS_RUNNING",
                    3 to "MQIASY_STATUS_QUIESCING",
                    5 to "MQIASY_STATUS_STANDBY",
                ),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_coded_char_set_id",
                descKey = "ibm_mq_qmgr_prop_desc_coded_char_set_id",
                labelEn = "Coded character set ID",
                keys = listOf("MQIA_CODED_CHAR_SET_ID"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_q_mgr_desc",
                descKey = "ibm_mq_qmgr_prop_desc_q_mgr_desc",
                labelEn = "Description",
                keys = listOf("MQCA_Q_MGR_DESC"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_command_level",
                descKey = "ibm_mq_qmgr_prop_desc_command_level",
                labelEn = "Command level",
                keys = listOf("MQIA_COMMAND_LEVEL"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_version",
                descKey = "ibm_mq_qmgr_prop_desc_version",
                labelEn = "Version",
                keys = listOf("MQCA_VERSION"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_cmd_server_control",
                descKey = "ibm_mq_qmgr_prop_desc_cmd_server_control",
                labelEn = "Command server control",
                keys = listOf("MQIA_CMD_SERVER_CONTROL"),
                valuePrefix = "MQSVC",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_chinit_control",
                descKey = "ibm_mq_qmgr_prop_desc_chinit_control",
                labelEn = "Channel initiator control",
                keys = listOf("MQIA_CHINIT_CONTROL"),
                valuePrefix = "MQSVC",
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_extended",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_dead_letter_q_name",
                descKey = "ibm_mq_qmgr_prop_desc_dead_letter_q_name",
                labelEn = "Dead-letter queue",
                keys = listOf("MQCA_DEAD_LETTER_Q_NAME"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_trigger_interval",
                descKey = "ibm_mq_qmgr_prop_desc_trigger_interval",
                labelEn = "Trigger interval",
                keys = listOf("MQIA_TRIGGER_INTERVAL"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_max_uncommitted_msgs",
                descKey = "ibm_mq_qmgr_prop_desc_max_uncommitted_msgs",
                labelEn = "Max uncommitted messages",
                keys = listOf("MQIA_MAX_UNCOMMITTED_MSGS"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_max_handles",
                descKey = "ibm_mq_qmgr_prop_desc_max_handles",
                labelEn = "Max handle limit",
                keys = listOf("MQIA_MAX_HANDLES"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_max_msg_length",
                descKey = "ibm_mq_qmgr_prop_desc_max_msg_length",
                labelEn = "Max message length",
                keys = listOf("MQIA_MAX_MSG_LENGTH"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_max_properties_length",
                descKey = "ibm_mq_qmgr_prop_desc_max_properties_length",
                labelEn = "Max properties length",
                keys = listOf("MQIA_MAX_PROPERTIES_LENGTH"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_max_priority",
                descKey = "ibm_mq_qmgr_prop_desc_max_priority",
                labelEn = "Max priority",
                keys = listOf("MQIA_MAX_PRIORITY"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_command_input_q_name",
                descKey = "ibm_mq_qmgr_prop_desc_command_input_q_name",
                labelEn = "Command input queue",
                keys = listOf("MQCA_COMMAND_INPUT_Q_NAME"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_custom",
                descKey = "ibm_mq_qmgr_prop_desc_custom",
                labelEn = "Custom",
                keys = listOf("MQCA_CUSTOM"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_cap_expiry",
                descKey = "ibm_mq_qmgr_prop_desc_cap_expiry",
                labelEn = "Expiry interval",
                keys = listOf("MQIA_CAP_EXPIRY"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_group_ur",
                descKey = "ibm_mq_qmgr_prop_desc_group_ur",
                labelEn = "Group unit of recovery",
                keys = listOf("MQIA_GROUP_UR"),
                valuePrefix = "MQGUR",
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_cluster",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_cluster_workload_exit",
                descKey = "ibm_mq_qmgr_prop_desc_cluster_workload_exit",
                labelEn = "Cluster workload exit",
                keys = listOf("MQCA_CLUSTER_WORKLOAD_EXIT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_cluster_workload_data",
                descKey = "ibm_mq_qmgr_prop_desc_cluster_workload_data",
                labelEn = "Cluster workload data",
                keys = listOf("MQCA_CLUSTER_WORKLOAD_DATA"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_cluster_workload_length",
                descKey = "ibm_mq_qmgr_prop_desc_cluster_workload_length",
                labelEn = "Cluster workload length",
                keys = listOf("MQIA_CLUSTER_WORKLOAD_LENGTH"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_clwl_mru_channels",
                descKey = "ibm_mq_qmgr_prop_desc_clwl_mru_channels",
                labelEn = "Max outbound cluster channels",
                keys = listOf("MQIA_CLWL_MRU_CHANNELS"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_clwl_useq",
                descKey = "ibm_mq_qmgr_prop_desc_clwl_useq",
                labelEn = "CLWL use queue",
                keys = listOf("MQIA_CLWL_USEQ"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_def_cluster_xmit_q_type",
                descKey = "ibm_mq_qmgr_prop_desc_def_cluster_xmit_q_type",
                labelEn = "Default cluster transmission queue",
                keys = listOf("MQIA_DEF_CLUSTER_XMIT_Q_TYPE"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_repository",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_repository_name",
                descKey = "ibm_mq_qmgr_prop_desc_repository_name",
                labelEn = "Full repository for a cluster",
                keys = listOf("MQCA_REPOSITORY_NAME"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_repository_namelist",
                descKey = "ibm_mq_qmgr_prop_desc_repository_namelist",
                labelEn = "Full repository for a list of clusters",
                keys = listOf("MQCA_REPOSITORY_NAMELIST"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_communication",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_def_xmit_q_name",
                descKey = "ibm_mq_qmgr_prop_desc_def_xmit_q_name",
                labelEn = "Default transmission queue",
                keys = listOf("MQCA_DEF_XMIT_Q_NAME"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_channel_auto_def",
                descKey = "ibm_mq_qmgr_prop_desc_channel_auto_def",
                labelEn = "Channel auto definition",
                keys = listOf("MQIA_CHANNEL_AUTO_DEF"),
                valuePrefix = "MQCHAD",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_channel_auto_def_exit",
                descKey = "ibm_mq_qmgr_prop_desc_channel_auto_def_exit",
                labelEn = "Channel auto definition exit",
                keys = listOf("MQCA_CHANNEL_AUTO_DEF_EXIT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_chlauth_records",
                descKey = "ibm_mq_qmgr_prop_desc_chlauth_records",
                labelEn = "Channel authentication",
                keys = listOf("MQIA_CHLAUTH_RECORDS"),
                valuePrefix = "MQCHLA",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_reverse_dns_lookup",
                descKey = "ibm_mq_qmgr_prop_desc_reverse_dns_lookup",
                labelEn = "Reverse lookup of host name",
                keys = listOf("MQIA_REVERSE_DNS_LOOKUP"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_ip_address_version",
                descKey = "ibm_mq_qmgr_prop_desc_ip_address_version",
                labelEn = "IP address version",
                keys = listOf("MQIA_IP_ADDRESS_VERSION"),
                valuePrefix = "MQIPADDR",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_activity_recording",
                descKey = "ibm_mq_qmgr_prop_desc_activity_recording",
                labelEn = "Activity recording",
                keys = listOf("MQIA_ACTIVITY_RECORDING"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_trace_route_recording",
                descKey = "ibm_mq_qmgr_prop_desc_trace_route_recording",
                labelEn = "Trace-route recording",
                keys = listOf("MQIA_TRACE_ROUTE_RECORDING"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_events",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_authority_event",
                descKey = "ibm_mq_qmgr_prop_desc_authority_event",
                labelEn = "Authority events",
                keys = listOf("MQIA_AUTHORITY_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_inhibit_event",
                descKey = "ibm_mq_qmgr_prop_desc_inhibit_event",
                labelEn = "Inhibit events",
                keys = listOf("MQIA_INHIBIT_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_local_event",
                descKey = "ibm_mq_qmgr_prop_desc_local_event",
                labelEn = "Local events",
                keys = listOf("MQIA_LOCAL_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_remote_event",
                descKey = "ibm_mq_qmgr_prop_desc_remote_event",
                labelEn = "Remote events",
                keys = listOf("MQIA_REMOTE_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_start_stop_event",
                descKey = "ibm_mq_qmgr_prop_desc_start_stop_event",
                labelEn = "Start and stop events",
                keys = listOf("MQIA_START_STOP_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_performance_event",
                descKey = "ibm_mq_qmgr_prop_desc_performance_event",
                labelEn = "Performance events",
                keys = listOf("MQIA_PERFORMANCE_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_command_event",
                descKey = "ibm_mq_qmgr_prop_desc_command_event",
                labelEn = "Command events",
                keys = listOf("MQIA_COMMAND_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_channel_event",
                descKey = "ibm_mq_qmgr_prop_desc_channel_event",
                labelEn = "Channel events",
                keys = listOf("MQIA_CHANNEL_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_channel_auto_def_event",
                descKey = "ibm_mq_qmgr_prop_desc_channel_auto_def_event",
                labelEn = "Channel auto definition events",
                keys = listOf("MQIA_CHANNEL_AUTO_DEF_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_ssl_event",
                descKey = "ibm_mq_qmgr_prop_desc_ssl_event",
                labelEn = "SSL events",
                keys = listOf("MQIA_SSL_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_configuration_event",
                descKey = "ibm_mq_qmgr_prop_desc_configuration_event",
                labelEn = "Configuration events",
                keys = listOf("MQIA_CONFIGURATION_EVENT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_logger_event",
                descKey = "ibm_mq_qmgr_prop_desc_logger_event",
                labelEn = "Logger events",
                keys = listOf("MQIA_LOGGER_EVENT"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_ssl",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_ssl_key_repository",
                descKey = "ibm_mq_qmgr_prop_desc_ssl_key_repository",
                labelEn = "Key repository",
                keys = listOf("MQCA_SSL_KEY_REPOSITORY"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_cert_label",
                descKey = "ibm_mq_qmgr_prop_desc_cert_label",
                labelEn = "Certificate label",
                keys = listOf("MQCA_CERT_LABEL"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_ssl_crl_namelist",
                descKey = "ibm_mq_qmgr_prop_desc_ssl_crl_namelist",
                labelEn = "Revocation namelist",
                keys = listOf("MQCA_SSL_CRL_NAMELIST"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_ssl_crypto_hardware",
                descKey = "ibm_mq_qmgr_prop_desc_ssl_crypto_hardware",
                labelEn = "Cryptographic hardware",
                keys = listOf("MQCA_SSL_CRYPTO_HARDWARE"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_ssl_reset_count",
                descKey = "ibm_mq_qmgr_prop_desc_ssl_reset_count",
                labelEn = "SSL reset count",
                keys = listOf("MQIA_SSL_RESET_COUNT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_ssl_fips_required",
                descKey = "ibm_mq_qmgr_prop_desc_ssl_fips_required",
                labelEn = "SSL FIPS required",
                keys = listOf("MQIA_SSL_FIPS_REQUIRED"),
                valuePrefix = null,
            ),
            // Suite B 取值（MQSUITEB_*）allclient 未收录，策展映射；
            // IBM PCF：NONE=0 / 128=1 / 192=2 / 128+192=3。
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_suite_b_strength",
                descKey = "ibm_mq_qmgr_prop_desc_suite_b_strength",
                labelEn = "Suite B strength",
                keys = listOf("MQIA_SUITE_B_STRENGTH"),
                valuePrefix = null,
                valueConstMap = mapOf(
                    0 to "MQSUITEB_NONE",
                    1 to "MQSUITEB_128_BIT",
                    2 to "MQSUITEB_192_BIT",
                    3 to "MQSUITEB_128_192_BIT",
                ),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_cert_val_policy",
                descKey = "ibm_mq_qmgr_prop_desc_cert_val_policy",
                labelEn = "Certificate validation policy",
                keys = listOf("MQIA_CERT_VAL_POLICY"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_statistics",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_creation_date",
                descKey = "ibm_mq_qmgr_prop_desc_creation_date",
                labelEn = "Creation date",
                keys = listOf("MQCA_CREATION_DATE"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_creation_time",
                descKey = "ibm_mq_qmgr_prop_desc_creation_time",
                labelEn = "Creation time",
                keys = listOf("MQCA_CREATION_TIME"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_alteration_date",
                descKey = "ibm_mq_qmgr_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_alteration_time",
                descKey = "ibm_mq_qmgr_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_q_mgr_identifier",
                descKey = "ibm_mq_qmgr_prop_desc_q_mgr_identifier",
                labelEn = "QMID",
                keys = listOf("MQCA_Q_MGR_IDENTIFIER"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_online_monitoring",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_monitoring_channel",
                descKey = "ibm_mq_qmgr_prop_desc_monitoring_channel",
                labelEn = "Channel monitoring",
                keys = listOf("MQIA_MONITORING_CHANNEL"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_monitoring_q",
                descKey = "ibm_mq_qmgr_prop_desc_monitoring_q",
                labelEn = "Queue monitoring",
                keys = listOf("MQIA_MONITORING_Q"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_monitoring_auto_clussdr",
                descKey = "ibm_mq_qmgr_prop_desc_monitoring_auto_clussdr",
                labelEn = "Auto CLUSSDR monitoring",
                keys = listOf("MQIA_MONITORING_AUTO_CLUSSDR"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_activity_conn_override",
                descKey = "ibm_mq_qmgr_prop_desc_activity_conn_override",
                labelEn = "Activity conn override",
                keys = listOf("MQIA_ACTIVITY_CONN_OVERRIDE"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_activity_trace",
                descKey = "ibm_mq_qmgr_prop_desc_activity_trace",
                labelEn = "Activity trace",
                keys = listOf("MQIA_ACTIVITY_TRACE"),
                valuePrefix = "MQMON",
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_statistics_monitoring",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_statistics_mqi",
                descKey = "ibm_mq_qmgr_prop_desc_statistics_mqi",
                labelEn = "MQI statistics",
                keys = listOf("MQIA_STATISTICS_MQI"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_statistics_q",
                descKey = "ibm_mq_qmgr_prop_desc_statistics_q",
                labelEn = "Queue statistics",
                keys = listOf("MQIA_STATISTICS_Q"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_statistics_channel",
                descKey = "ibm_mq_qmgr_prop_desc_statistics_channel",
                labelEn = "Channel statistics",
                keys = listOf("MQIA_STATISTICS_CHANNEL"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_statistics_auto_clussdr",
                descKey = "ibm_mq_qmgr_prop_desc_statistics_auto_clussdr",
                labelEn = "Auto CLUSSDR statistics",
                keys = listOf("MQIA_STATISTICS_AUTO_CLUSSDR"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_statistics_interval",
                descKey = "ibm_mq_qmgr_prop_desc_statistics_interval",
                labelEn = "Statistics interval",
                keys = listOf("MQIA_STATISTICS_INTERVAL"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_accounting_monitoring",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_accounting_mqi",
                descKey = "ibm_mq_qmgr_prop_desc_accounting_mqi",
                labelEn = "MQI accounting",
                keys = listOf("MQIA_ACCOUNTING_MQI"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_accounting_q",
                descKey = "ibm_mq_qmgr_prop_desc_accounting_q",
                labelEn = "Queue accounting",
                keys = listOf("MQIA_ACCOUNTING_Q"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_accounting_interval",
                descKey = "ibm_mq_qmgr_prop_desc_accounting_interval",
                labelEn = "Accounting interval",
                keys = listOf("MQIA_ACCOUNTING_INTERVAL"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_accounting_conn_override",
                descKey = "ibm_mq_qmgr_prop_desc_accounting_conn_override",
                labelEn = "Accounting conn override",
                keys = listOf("MQIA_ACCOUNTING_CONN_OVERRIDE"),
                valuePrefix = "MQMON",
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_pubsub",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_pubsub_mode",
                descKey = "ibm_mq_qmgr_prop_desc_pubsub_mode",
                labelEn = "Publish/Subscribe mode",
                keys = listOf("MQIA_PUBSUB_MODE"),
                valuePrefix = "MQPSM",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_pubsub_maxmsg_retry_count",
                descKey = "ibm_mq_qmgr_prop_desc_pubsub_maxmsg_retry_count",
                labelEn = "Message retry count",
                keys = listOf("MQIA_PUBSUB_MAXMSG_RETRY_COUNT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_pubsub_sync_pt",
                descKey = "ibm_mq_qmgr_prop_desc_pubsub_sync_pt",
                labelEn = "Publish/Subscribe syncpoint",
                keys = listOf("MQIA_PUBSUB_SYNC_PT"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_pubsub_np_msg",
                descKey = "ibm_mq_qmgr_prop_desc_pubsub_np_msg",
                labelEn = "Undelivered non-persistent input message",
                keys = listOf("MQIA_PUBSUB_NP_MSG"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_pubsub_np_resp",
                descKey = "ibm_mq_qmgr_prop_desc_pubsub_np_resp",
                labelEn = "Undelivered non-persistent response",
                keys = listOf("MQIA_PUBSUB_NP_RESP"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_tree_life_time",
                descKey = "ibm_mq_qmgr_prop_desc_tree_life_time",
                labelEn = "Tree life extended",
                keys = listOf("MQIA_TREE_LIFE_TIME"),
                valuePrefix = null,
            ),
        ),
    ),
    ObjectPropertyGroup(
        titleKey = "ibm_mq_qmgr_props_group_channels",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_max_channels",
                descKey = "ibm_mq_qmgr_prop_desc_max_channels",
                labelEn = "Max channels",
                keys = listOf("MQIA_MAX_CHANNELS"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_active_channels",
                descKey = "ibm_mq_qmgr_prop_desc_active_channels",
                labelEn = "Active channels",
                keys = listOf("MQIA_ACTIVE_CHANNELS"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_tcp_channels",
                descKey = "ibm_mq_qmgr_prop_desc_tcp_channels",
                labelEn = "TCP channels",
                keys = listOf("MQIA_TCP_CHANNELS"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_tcp_keep_alive",
                descKey = "ibm_mq_qmgr_prop_desc_tcp_keep_alive",
                labelEn = "TCP keepalive",
                keys = listOf("MQIA_TCP_KEEP_ALIVE"),
                valuePrefix = "MQTCPKEEP",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_outbound_port_min",
                descKey = "ibm_mq_qmgr_prop_desc_outbound_port_min",
                labelEn = "Outbound port min",
                keys = listOf("MQIA_OUTBOUND_PORT_MIN"),
                valuePrefix = null,
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_qmgr_prop_name_outbound_port_max",
                descKey = "ibm_mq_qmgr_prop_desc_outbound_port_max",
                labelEn = "Outbound port max",
                keys = listOf("MQIA_OUTBOUND_PORT_MAX"),
                valuePrefix = null,
            ),
        ),
    ),
)

/** 快照属性 → 分组展示行（复用通用解析；枚举归属 DM 表优先，valuePrefix 兜底）。 */
internal fun resolveQmgrPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = qmgrPropertyGroups,
        allGroupTitleKey = "ibm_mq_qmgr_props_group_all",
        constNameOf = { _, key, value -> MqAttributeDisplay.constNameFor(key, value) },
    )
