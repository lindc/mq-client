package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * process / namelist / auth info / comm info / service 五类对象属性分组表：
 * 对齐 MQ Explorer 对应 properties 对话框的 General 页（字段序按 IBM
 * INQUIRE_* 响应文档；**全部文案走 i18n 键**，枚举值走 `ibm_mq_attrs` 表的
 * `<常量>.title`）。
 *
 * 坑位同 listener / topic / subscription：不走 DM 枚举归属（按 Queue 模型
 * 生成，对其他对象键会误判），一律 valuePrefix / valueConstMap 反查。
 */

/* -------------------------------- 进程 -------------------------------- */

internal val processPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "ibm_mq_process_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_process_name",
                descKey = "ibm_mq_process_prop_desc_process_name",
                labelEn = "Process name",
                keys = listOf("MQCA_PROCESS_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_appl_type",
                descKey = "ibm_mq_process_prop_desc_appl_type",
                labelEn = "Application type",
                keys = listOf("MQIA_APPL_TYPE"),
                // MQAT_AIX = MQAT_UNIX = 6 同值碰撞，精确映射到 UNIX。
                valueConstMap = mapOf(6 to "MQAT_UNIX"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_appl_id",
                descKey = "ibm_mq_process_prop_desc_appl_id",
                labelEn = "Application identifier",
                keys = listOf("MQCA_APPL_ID"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_env_data",
                descKey = "ibm_mq_process_prop_desc_env_data",
                labelEn = "Environment data",
                keys = listOf("MQCA_ENV_DATA"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_user_data",
                descKey = "ibm_mq_process_prop_desc_user_data",
                labelEn = "User data",
                keys = listOf("MQCA_USER_DATA"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_process_desc",
                descKey = "ibm_mq_process_prop_desc_process_desc",
                labelEn = "Description",
                keys = listOf("MQCA_PROCESS_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_alteration_date",
                descKey = "ibm_mq_process_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_process_prop_name_alteration_time",
                descKey = "ibm_mq_process_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

internal fun resolveProcessPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = processPropertyGroups,
        allGroupTitleKey = "ibm_mq_process_props_group_all",
    )

/* ------------------------------ 名称列表 ------------------------------ */

internal val namelistPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "ibm_mq_namelist_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_namelist_prop_name_namelist_name",
                descKey = "ibm_mq_namelist_prop_desc_namelist_name",
                labelEn = "Namelist name",
                keys = listOf("MQCA_NAMELIST_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_namelist_prop_name_name_count",
                descKey = "ibm_mq_namelist_prop_desc_name_count",
                labelEn = "Name count",
                keys = listOf("MQIA_NAME_COUNT"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_namelist_prop_name_names",
                descKey = "ibm_mq_namelist_prop_desc_names",
                labelEn = "Names",
                keys = listOf("MQCA_NAMES"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_namelist_prop_name_namelist_desc",
                descKey = "ibm_mq_namelist_prop_desc_namelist_desc",
                labelEn = "Description",
                keys = listOf("MQCA_NAMELIST_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_namelist_prop_name_alteration_date",
                descKey = "ibm_mq_namelist_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_namelist_prop_name_alteration_time",
                descKey = "ibm_mq_namelist_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

internal fun resolveNamelistPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = namelistPropertyGroups,
        allGroupTitleKey = "ibm_mq_namelist_props_group_all",
    )

/* ----------------------------- 认证信息 ------------------------------ */

internal val authInfoPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "ibm_mq_authinfo_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_authinfo_prop_name_auth_info_name",
                descKey = "ibm_mq_authinfo_prop_desc_auth_info_name",
                labelEn = "Authentication information name",
                keys = listOf("MQCA_AUTH_INFO_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_authinfo_prop_name_auth_info_type",
                descKey = "ibm_mq_authinfo_prop_desc_auth_info_type",
                labelEn = "Authentication information type",
                keys = listOf("MQIA_AUTH_INFO_TYPE"),
                valuePrefix = "MQAIT",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_authinfo_prop_name_auth_info_desc",
                descKey = "ibm_mq_authinfo_prop_desc_auth_info_desc",
                labelEn = "Description",
                keys = listOf("MQCA_AUTH_INFO_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_authinfo_prop_name_auth_info_conn_name",
                descKey = "ibm_mq_authinfo_prop_desc_auth_info_conn_name",
                labelEn = "LDAP server name",
                keys = listOf("MQCA_AUTH_INFO_CONN_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_authinfo_prop_name_ldap_user_name",
                descKey = "ibm_mq_authinfo_prop_desc_ldap_user_name",
                labelEn = "LDAP user name",
                keys = listOf("MQCA_LDAP_USER_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_authinfo_prop_name_alteration_date",
                descKey = "ibm_mq_authinfo_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_authinfo_prop_name_alteration_time",
                descKey = "ibm_mq_authinfo_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

/** 展示为敏感信息 / 乱码的参数：LDAP 口令不显示（MQ Explorer 也不回显）。 */
private val AUTH_INFO_HIDDEN_KEYS = setOf("MQCA_LDAP_PASSWORD")

internal fun resolveAuthInfoPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = authInfoPropertyGroups,
        allGroupTitleKey = "ibm_mq_authinfo_props_group_all",
        hiddenLeftoverKeys = AUTH_INFO_HIDDEN_KEYS,
    )

/* ----------------------------- 通信信息 ------------------------------ */

internal val commInfoPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "ibm_mq_comminfo_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_comm_info_name",
                descKey = "ibm_mq_comminfo_prop_desc_comm_info_name",
                labelEn = "Communication information name",
                keys = listOf("MQCA_COMM_INFO_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_comm_info_type",
                descKey = "ibm_mq_comminfo_prop_desc_comm_info_type",
                labelEn = "Communication information type",
                keys = listOf("MQIA_COMM_INFO_TYPE"),
                valuePrefix = "MQCIT",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_comm_info_desc",
                descKey = "ibm_mq_comminfo_prop_desc_comm_info_desc",
                labelEn = "Description",
                keys = listOf("MQCA_COMM_INFO_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_group_address",
                descKey = "ibm_mq_comminfo_prop_desc_group_address",
                labelEn = "Group address",
                keys = listOf("MQCACH_GROUP_ADDRESS"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_port",
                descKey = "ibm_mq_comminfo_prop_desc_port",
                labelEn = "Port",
                keys = listOf("MQIACH_PORT"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_mcast_bridge",
                descKey = "ibm_mq_comminfo_prop_desc_mcast_bridge",
                labelEn = "Multicast bridge",
                keys = listOf("MQIA_MCAST_BRIDGE"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_coded_char_set_id",
                descKey = "ibm_mq_comminfo_prop_desc_coded_char_set_id",
                labelEn = "Coded character set ID",
                keys = listOf("MQIA_CODED_CHAR_SET_ID"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_comm_event",
                descKey = "ibm_mq_comminfo_prop_desc_comm_event",
                labelEn = "Communication event",
                keys = listOf("MQIA_COMM_EVENT"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_encoding",
                descKey = "ibm_mq_comminfo_prop_desc_encoding",
                labelEn = "Encoding",
                keys = listOf("MQIACF_ENCODING"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_mc_hb_interval",
                descKey = "ibm_mq_comminfo_prop_desc_mc_hb_interval",
                labelEn = "Multicast heartbeat interval",
                keys = listOf("MQIACH_MC_HB_INTERVAL"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_multicast_properties",
                descKey = "ibm_mq_comminfo_prop_desc_multicast_properties",
                labelEn = "Multicast properties control",
                keys = listOf("MQIACH_MULTICAST_PROPERTIES"),
                valuePrefix = "MQMCP",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_monitor_interval",
                descKey = "ibm_mq_comminfo_prop_desc_monitor_interval",
                labelEn = "Monitor interval",
                keys = listOf("MQIA_MONITOR_INTERVAL"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_msg_history",
                descKey = "ibm_mq_comminfo_prop_desc_msg_history",
                labelEn = "Message history",
                keys = listOf("MQIACH_MSG_HISTORY"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_new_subscriber_history",
                descKey = "ibm_mq_comminfo_prop_desc_new_subscriber_history",
                labelEn = "New subscriber history",
                keys = listOf("MQIACH_NEW_SUBSCRIBER_HISTORY"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_alteration_date",
                descKey = "ibm_mq_comminfo_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_comminfo_prop_name_alteration_time",
                descKey = "ibm_mq_comminfo_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

internal fun resolveCommInfoPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = commInfoPropertyGroups,
        allGroupTitleKey = "ibm_mq_comminfo_props_group_all",
    )

/* ------------------------------- 服务 -------------------------------- */

internal val servicePropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "ibm_mq_service_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_service_name",
                descKey = "ibm_mq_service_prop_desc_service_name",
                labelEn = "Service name",
                keys = listOf("MQCA_SERVICE_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_service_type",
                descKey = "ibm_mq_service_prop_desc_service_type",
                labelEn = "Service type",
                keys = listOf("MQIA_SERVICE_TYPE"),
                valuePrefix = "MQSVC_TYPE",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_service_desc",
                descKey = "ibm_mq_service_prop_desc_service_desc",
                labelEn = "Description",
                keys = listOf("MQCA_SERVICE_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_service_control",
                descKey = "ibm_mq_service_prop_desc_service_control",
                labelEn = "Service control",
                keys = listOf("MQIA_SERVICE_CONTROL"),
                valuePrefix = "MQSVC_CONTROL",
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_start_command",
                descKey = "ibm_mq_service_prop_desc_start_command",
                labelEn = "Start command",
                keys = listOf("MQCA_SERVICE_START_COMMAND"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_start_args",
                descKey = "ibm_mq_service_prop_desc_start_args",
                labelEn = "Start arguments",
                keys = listOf("MQCA_SERVICE_START_ARGS"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_stop_command",
                descKey = "ibm_mq_service_prop_desc_stop_command",
                labelEn = "Stop command",
                keys = listOf("MQCA_SERVICE_STOP_COMMAND"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_stop_args",
                descKey = "ibm_mq_service_prop_desc_stop_args",
                labelEn = "Stop arguments",
                keys = listOf("MQCA_SERVICE_STOP_ARGS"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_stdout_destination",
                descKey = "ibm_mq_service_prop_desc_stdout_destination",
                labelEn = "Standard output",
                keys = listOf("MQCA_STDOUT_DESTINATION"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_stderr_destination",
                descKey = "ibm_mq_service_prop_desc_stderr_destination",
                labelEn = "Standard error",
                keys = listOf("MQCA_STDERR_DESTINATION"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_alteration_date",
                descKey = "ibm_mq_service_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "ibm_mq_service_prop_name_alteration_time",
                descKey = "ibm_mq_service_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

internal fun resolveServicePropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = servicePropertyGroups,
        allGroupTitleKey = "ibm_mq_service_props_group_all",
    )
