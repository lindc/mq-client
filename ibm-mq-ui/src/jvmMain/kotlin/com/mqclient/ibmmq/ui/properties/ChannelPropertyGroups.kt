package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * 通道属性分组表：对齐 MQ Explorer 的 Channel properties 对话框（单页「常规」
 * 收录定义 + 运行状态两大块，字段序按 DmChannel / IBM 文档；**全部文案走 i18n
 * 键**，枚举值走 `ibm_mq_attrs` 表的 `<常量>.title`）。
 *
 * 坑位（同 listener / topic / subscription）：
 * - 不走 DM 枚举归属（按 Queue 模型生成，对通道键会误判）；
 * - 通道名参数（1501）Java 客户端反查成别名键 `MQCACH_FIRST`、传输协议
 *   （1501 的整型空间）反查成 `MQIACH_FIRST`，两个键都兜（MQIACH_XMIT_
 *   PROTOCOL_TYPE / MQCACH_CHANNEL_NAME 为正名）。
 */
internal val channelPropertyGroups: List<ObjectPropertyGroup> = listOf(
    ObjectPropertyGroup(
        titleKey = "channel_props_group_general",
        fields = listOf(
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_name",
                descKey = "channel_prop_desc_channel_name",
                labelEn = "Channel name",
                keys = listOf("MQCACH_CHANNEL_NAME", "MQCACH_FIRST"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_type",
                descKey = "channel_prop_desc_channel_type",
                labelEn = "Channel type",
                keys = listOf("MQIACH_CHANNEL_TYPE"),
                valuePrefix = "MQCHT",
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_desc",
                descKey = "channel_prop_desc_channel_desc",
                labelEn = "Description",
                keys = listOf("MQCACH_DESC"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_transport_type",
                descKey = "channel_prop_desc_transport_type",
                labelEn = "Transmission protocol",
                keys = listOf("MQIACH_XMIT_PROTOCOL_TYPE", "MQIACH_FIRST"),
                valuePrefix = "MQXPT",
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_connection_name",
                descKey = "channel_prop_desc_connection_name",
                labelEn = "Connection name",
                keys = listOf("MQCACH_CONNECTION_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_local_address",
                descKey = "channel_prop_desc_local_address",
                labelEn = "Local address",
                keys = listOf("MQCACH_LOCAL_ADDRESS"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_mca_user_id",
                descKey = "channel_prop_desc_mca_user_id",
                labelEn = "MCA user ID",
                keys = listOf("MQCACH_MCA_USER_ID"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_max_msg_length",
                descKey = "channel_prop_desc_max_msg_length",
                labelEn = "Max message length",
                keys = listOf("MQIACH_MAX_MSG_LENGTH"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_hb_interval",
                descKey = "channel_prop_desc_hb_interval",
                labelEn = "Heartbeat interval",
                keys = listOf("MQIACH_HB_INTERVAL"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_disc_interval",
                descKey = "channel_prop_desc_disc_interval",
                labelEn = "Disconnection interval",
                keys = listOf("MQIACH_DISC_INTERVAL"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_keep_alive_interval",
                descKey = "channel_prop_desc_keep_alive_interval",
                labelEn = "Keep-alive interval",
                keys = listOf("MQIACH_KEEP_ALIVE_INTERVAL"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_max_instances",
                descKey = "channel_prop_desc_max_instances",
                labelEn = "Max instances",
                keys = listOf("MQIACH_MAX_INSTANCES"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_max_insts_per_client",
                descKey = "channel_prop_desc_max_insts_per_client",
                labelEn = "Max instances per client",
                keys = listOf("MQIACH_MAX_INSTS_PER_CLIENT"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_sharing_conversations",
                descKey = "channel_prop_desc_sharing_conversations",
                labelEn = "Sharing conversations",
                keys = listOf("MQIACH_SHARING_CONVERSATIONS"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_hdr_compression",
                descKey = "channel_prop_desc_hdr_compression",
                labelEn = "Header compression",
                keys = listOf("MQIACH_HDR_COMPRESSION"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_msg_compression",
                descKey = "channel_prop_desc_msg_compression",
                labelEn = "Message compression",
                keys = listOf("MQIACH_MSG_COMPRESSION"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_cipher_spec",
                descKey = "channel_prop_desc_ssl_cipher_spec",
                labelEn = "SSL CipherSpec",
                keys = listOf("MQCACH_SSL_CIPHER_SPEC"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_client_auth",
                descKey = "channel_prop_desc_ssl_client_auth",
                labelEn = "SSL client authentication",
                keys = listOf("MQIACH_SSL_CLIENT_AUTH"),
                valuePrefix = "MQSCA",
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_peer_name",
                descKey = "channel_prop_desc_ssl_peer_name",
                labelEn = "SSL peer name",
                keys = listOf("MQCACH_SSL_PEER_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_cert_label",
                descKey = "channel_prop_desc_cert_label",
                labelEn = "Certificate label",
                keys = listOf("MQCA_CERT_LABEL"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_sec_exit_name",
                descKey = "channel_prop_desc_sec_exit_name",
                labelEn = "Security exit",
                keys = listOf("MQCACH_SEC_EXIT_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_sec_exit_user_data",
                descKey = "channel_prop_desc_sec_exit_user_data",
                labelEn = "Security exit user data",
                keys = listOf("MQCACH_SEC_EXIT_USER_DATA"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_send_exit_name",
                descKey = "channel_prop_desc_send_exit_name",
                labelEn = "Send exit",
                keys = listOf("MQCACH_SEND_EXIT_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_send_exit_user_data",
                descKey = "channel_prop_desc_send_exit_user_data",
                labelEn = "Send exit user data",
                keys = listOf("MQCACH_SEND_EXIT_USER_DATA"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_rcv_exit_name",
                descKey = "channel_prop_desc_rcv_exit_name",
                labelEn = "Receive exit",
                keys = listOf("MQCACH_RCV_EXIT_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_rcv_exit_user_data",
                descKey = "channel_prop_desc_rcv_exit_user_data",
                labelEn = "Receive exit user data",
                keys = listOf("MQCACH_RCV_EXIT_USER_DATA"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_monitoring_channel",
                descKey = "channel_prop_desc_monitoring_channel",
                labelEn = "Monitoring",
                keys = listOf("MQIA_MONITORING_CHANNEL"),
                valuePrefix = "MQMON",
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_status",
                descKey = "channel_prop_desc_channel_status",
                labelEn = "Channel status",
                keys = listOf("MQIACH_CHANNEL_STATUS"),
                valuePrefix = "MQCHS",
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_mca_status",
                descKey = "channel_prop_desc_mca_status",
                labelEn = "MCA status",
                keys = listOf("MQIACH_MCA_STATUS"),
                valuePrefix = "MQCHS",
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_instance_type",
                descKey = "channel_prop_desc_channel_instance_type",
                labelEn = "Channel instance type",
                keys = listOf("MQIACH_CHANNEL_INSTANCE_TYPE"),
                valuePrefix = "MQOT",
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_start_date",
                descKey = "channel_prop_desc_channel_start_date",
                labelEn = "Start date",
                keys = listOf("MQCACH_CHANNEL_START_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_start_time",
                descKey = "channel_prop_desc_channel_start_time",
                labelEn = "Start time",
                keys = listOf("MQCACH_CHANNEL_START_TIME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_last_msg_date",
                descKey = "channel_prop_desc_last_msg_date",
                labelEn = "Last message date",
                keys = listOf("MQCACH_LAST_MSG_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_last_msg_time",
                descKey = "channel_prop_desc_last_msg_time",
                labelEn = "Last message time",
                keys = listOf("MQCACH_LAST_MSG_TIME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_msgs",
                descKey = "channel_prop_desc_msgs",
                labelEn = "Messages",
                keys = listOf("MQIACH_MSGS"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_bytes_rcvd",
                descKey = "channel_prop_desc_bytes_rcvd",
                labelEn = "Bytes received",
                keys = listOf("MQIACH_BYTES_RCVD"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_bytes_sent",
                descKey = "channel_prop_desc_bytes_sent",
                labelEn = "Bytes sent",
                keys = listOf("MQIACH_BYTES_SENT"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_buffers_rcvd",
                descKey = "channel_prop_desc_buffers_rcvd",
                labelEn = "Buffers received",
                keys = listOf("MQIACH_BUFFERS_RCVD"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_buffers_sent",
                descKey = "channel_prop_desc_buffers_sent",
                labelEn = "Buffers sent",
                keys = listOf("MQIACH_BUFFERS_SENT"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_compression_rate",
                descKey = "channel_prop_desc_compression_rate",
                labelEn = "Compression rate",
                keys = listOf("MQIACH_COMPRESSION_RATE"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_compression_time",
                descKey = "channel_prop_desc_compression_time",
                labelEn = "Compression time",
                keys = listOf("MQIACH_COMPRESSION_TIME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_exit_time_indicator",
                descKey = "channel_prop_desc_exit_time_indicator",
                labelEn = "Exit time",
                keys = listOf("MQIACH_EXIT_TIME_INDICATOR"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_mca_job_name",
                descKey = "channel_prop_desc_mca_job_name",
                labelEn = "MCA job name",
                keys = listOf("MQCACH_MCA_JOB_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_remote_appl_tag",
                descKey = "channel_prop_desc_remote_appl_tag",
                labelEn = "Remote application name",
                keys = listOf("MQCACH_REMOTE_APPL_TAG"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_remote_product",
                descKey = "channel_prop_desc_remote_product",
                labelEn = "Remote product",
                keys = listOf("MQCACH_REMOTE_PRODUCT"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_remote_version",
                descKey = "channel_prop_desc_remote_version",
                labelEn = "Remote version",
                keys = listOf("MQCACH_REMOTE_VERSION"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_current_sharing_convs",
                descKey = "channel_prop_desc_current_sharing_convs",
                labelEn = "Current conversations",
                keys = listOf("MQIACH_CURRENT_SHARING_CONVS"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_max_sharing_convs",
                descKey = "channel_prop_desc_max_sharing_convs",
                labelEn = "Max sharing conversations",
                keys = listOf("MQIACH_MAX_SHARING_CONVS"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_stop_requested",
                descKey = "channel_prop_desc_stop_requested",
                labelEn = "Stop requested",
                keys = listOf("MQIACH_STOP_REQUESTED"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_channel_substate",
                descKey = "channel_prop_desc_channel_substate",
                labelEn = "Channel substate",
                keys = listOf("MQIACH_CHANNEL_SUBSTATE"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_key_resets",
                descKey = "channel_prop_desc_ssl_key_resets",
                labelEn = "SSL key resets",
                keys = listOf("MQIACH_SSL_KEY_RESETS"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_key_reset_date",
                descKey = "channel_prop_desc_ssl_key_reset_date",
                labelEn = "SSL key reset date",
                keys = listOf("MQCACH_SSL_KEY_RESET_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_key_reset_time",
                descKey = "channel_prop_desc_ssl_key_reset_time",
                labelEn = "SSL key reset time",
                keys = listOf("MQCACH_SSL_KEY_RESET_TIME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_cert_issuer_name",
                descKey = "channel_prop_desc_ssl_cert_issuer_name",
                labelEn = "SSL certificate issuer name",
                keys = listOf("MQCACH_SSL_CERT_ISSUER_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_ssl_short_peer_name",
                descKey = "channel_prop_desc_ssl_short_peer_name",
                labelEn = "SSL short peer name",
                keys = listOf("MQCACH_SSL_SHORT_PEER_NAME"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_alteration_date",
                descKey = "channel_prop_desc_alteration_date",
                labelEn = "Alteration date",
                keys = listOf("MQCA_ALTERATION_DATE"),
            ),
            ObjectPropertyField(
                nameKey = "channel_prop_name_alteration_time",
                descKey = "channel_prop_desc_alteration_time",
                labelEn = "Alteration time",
                keys = listOf("MQCA_ALTERATION_TIME"),
            ),
        ),
    ),
)

/**
 * 通道快照 →（分组字段表内命中 + 「全部属性」兜底）的展示行。
 */
internal fun resolveChannelPropertyGroups(
    snapshot: MqObjectSnapshot,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> =
    resolveGroupedObjectProperties(
        snapshot = snapshot,
        groups = channelPropertyGroups,
        allGroupTitleKey = "channel_props_group_all",
    )
