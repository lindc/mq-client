# 队列管理器属性 i18n 生成器：ibm_mq_*.json 的 qmgr_prop_name_* / qmgr_prop_desc_* /
# qmgr_props_group_* 键由本脚本生成（数据源 = 本脚本内置的 MQ Explorer 官方双语文案，
# 提取自本机 MQ Explorer 9.4.5 帮助文档 e_properties_qmanager.html 的 en / zh-CN 版本）。
# 字段变更后重跑：python <本文件>（cwd 任意）。
import json, io, collections, os

# 分组标题（顺序 = QmgrPropertyGroups.kt 的分组顺序，对齐 MQ Explorer 属性对话框分页）
GROUP_TITLES = [
    ("qmgr_props_group_general", "General", "常规"),
    ("qmgr_props_group_extended", "Extended", "扩展"),
    ("qmgr_props_group_cluster", "Cluster", "集群"),
    ("qmgr_props_group_repository", "Repository", "存储库"),
    ("qmgr_props_group_communication", "Communication", "通信"),
    ("qmgr_props_group_events", "Events", "事件"),
    ("qmgr_props_group_ssl", "SSL", "SSL"),
    ("qmgr_props_group_statistics", "Statistics", "统计信息"),
    ("qmgr_props_group_online_monitoring", "Online monitoring", "联机监控"),
    ("qmgr_props_group_statistics_monitoring", "Statistics monitoring", "统计监测"),
    ("qmgr_props_group_accounting_monitoring", "Accounting monitoring", "记账监控"),
    ("qmgr_props_group_pubsub", "Publish/Subscribe", "发布/预订"),
    ("qmgr_props_group_channels", "Channels", "通道"),
    ("qmgr_props_group_all", "All properties", "全部属性"),
]

# (label_en, name_zh, desc_zh, pcf_key, value_prefix)
FIELDS = {
 "general": [
  ("Queue manager name", "队列管理器名称", "队列管理器的唯一名称，创建后不可更改。", "MQCA_Q_MGR_NAME", None),
  ("Platform", "平台", "队列管理器运行所在的平台体系结构。", "MQIA_PLATFORM", "MQPL"),
  ("Queue manager status", "队列管理器状态", "队列管理器的运行状态：正在运行 / 正在启动 / 正在停止 / 备用。", "MQIACF_Q_MGR_STATUS", None),
  ("Coded character set ID", "编码字符集标识", "队列管理器使用的编码字符集标识（CCSID）。", "MQIA_CODED_CHAR_SET_ID", None),
  ("Description", "描述", "队列管理器用途的备注说明。", "MQCA_Q_MGR_DESC", None),
  ("Command level", "命令级别", "队列管理器支持的功能级别。", "MQIA_COMMAND_LEVEL", None),
  ("Version", "版本", "已安装的 IBM MQ 版本（VVRRMMFF）。", "MQCA_VERSION", None),
  ("Command server control", "命令服务器控制", "队列管理器启动时是否自动启动命令服务器。", "MQIA_CMD_SERVER_CONTROL", "MQSVC"),
  ("Channel initiator control", "通道启动程序控制", "队列管理器启动时是否自动启动通道启动程序。", "MQIA_CHINIT_CONTROL", "MQSVC"),
 ],
 "extended": [
  ("Dead-letter queue", "死信队列", "无法投递的消息转入的队列。", "MQCA_DEAD_LETTER_Q_NAME", None),
  ("Trigger interval", "触发器时间间隔", "相邻两次触发消息之间必须等待的毫秒数。", "MQIA_TRIGGER_INTERVAL", None),
  ("Max uncommitted messages", "最大未落实消息数", "单个同步点内可检索和放置的最大未落实消息数。", "MQIA_MAX_UNCOMMITTED_MSGS", None),
  ("Max handle limit", "最大句柄数", "任一任务可同时拥有的最大开放句柄数。", "MQIA_MAX_HANDLES", None),
  ("Max message length", "最大消息长度", "队列管理器上队列允许的最大消息长度（字节）。", "MQIA_MAX_MSG_LENGTH", None),
  ("Max properties length", "最大属性长度", "可随消息一起流动的属性数据的最大字节大小。", "MQIA_MAX_PROPERTIES_LENGTH", None),
  ("Max priority", "最大优先级", "队列管理器支持的最大消息优先级。", "MQIA_MAX_PRIORITY", None),
  ("Command input queue", "命令输入队列", "系统命令输入队列的名称。", "MQCA_COMMAND_INPUT_Q_NAME", None),
  ("Custom", "定制", "按分号分隔的自定义属性。", "MQCA_CUSTOM", None),
  ("Expiry interval", "到期时间间隔", "容量到期扫描的时间间隔（分钟），IBM MQ 9.2+。", "MQIA_CAP_EXPIRY", None),
  ("Group unit of recovery", "恢复组单元", "是否启用恢复组单元（GROUPUR）。", "MQIA_GROUP_UR", None),
 ],
 "cluster": [
  ("Cluster workload exit", "集群工作负载出口", "放置集群队列消息时调用的出口名称。", "MQCA_CLUSTER_WORKLOAD_EXIT", None),
  ("Cluster workload data", "集群工作负载数据", "调用集群工作负载出口时传递的数据。", "MQCA_CLUSTER_WORKLOAD_DATA", None),
  ("Cluster workload length", "集群工作负载长度", "传递给集群工作负载出口的最大消息数据字节数。", "MQIA_CLUSTER_WORKLOAD_LENGTH", None),
  ("Max outbound cluster channels", "最大出站集群通道", "最大出站集群通道数。", "MQIA_CLWL_MRU_CHANNELS", None),
  ("CLWL use queue", "CLWL 使用队列", "是否可从集群队列的远程实例选择目标。", "MQIA_CLWL_USEQ", None),
  ("Default cluster transmission queue", "缺省集群传输队列", "集群消息使用的缺省传输队列类型。", "MQIA_DEF_CLUSTER_XMIT_Q_TYPE", None),
 ],
 "repository": [
  ("Full repository for a cluster", "单个集群的完整存储库", "此队列管理器作为完整存储库的集群名称。", "MQCA_REPOSITORY_NAME", None),
  ("Full repository for a list of clusters", "多个集群的完整存储库", "此队列管理器作为完整存储库的多个集群的名称列表。", "MQCA_REPOSITORY_NAMELIST", None),
 ],
 "communication": [
  ("Default transmission queue", "缺省传输队列", "发往远程队列管理器且无其他合适传输队列时使用的队列。", "MQCA_DEF_XMIT_Q_NAME", None),
  ("Channel auto definition", "通道自动定义", "接收方 / 服务器连接通道是否自动定义。", "MQIA_CHANNEL_AUTO_DEF", "MQCHAD"),
  ("Channel auto definition exit", "通道自动定义出口", "自动定义通道时调用的出口名称。", "MQCA_CHANNEL_AUTO_DEF_EXIT", None),
  ("Channel authentication", "通道认证", "是否启用通道认证记录（CHLAUTH）。", "MQIA_CHLAUTH_RECORDS", "MQCHLA"),
  ("Reverse lookup of host name", "主机名的逆向查找", "是否对连接通道的 IP 地址做 DNS 逆向查找。", "MQIA_REVERSE_DNS_LOOKUP", None),
  ("IP address version", "IP 地址版本", "队列管理器使用的 IP 协议版本。", "MQIA_IP_ADDRESS_VERSION", "MQIPADDR"),
  ("Activity recording", "活动记录", "是否为请求活动报告的消息生成活动报告。", "MQIA_ACTIVITY_RECORDING", None),
  ("Trace-route recording", "跟踪路由消息记录", "是否在跟踪路由消息中累积路由信息。", "MQIA_TRACE_ROUTE_RECORDING", None),
 ],
 "events": [
  ("Authority events", "权限事件", "应用程序无权打开队列时是否产生事件消息。", "MQIA_AUTHORITY_EVENT", None),
  ("Inhibit events", "禁止事件", "对禁止放入 / 获取的队列操作时是否产生事件消息。", "MQIA_INHIBIT_EVENT", None),
  ("Local events", "本地事件", "无法访问本地对象时是否产生事件消息。", "MQIA_LOCAL_EVENT", None),
  ("Remote events", "远程事件", "无法访问其他队列管理器上的队列时是否产生事件消息。", "MQIA_REMOTE_EVENT", None),
  ("Start and stop events", "启动和停止事件", "队列管理器启动 / 停止 / 静止时是否产生事件消息。", "MQIA_START_STOP_EVENT", None),
  ("Performance events", "性能事件", "资源达到阈值条件时是否产生事件消息。", "MQIA_PERFORMANCE_EVENT", None),
  ("Command events", "命令事件", "执行 MQSC / PCF 命令时是否产生事件消息。", "MQIA_COMMAND_EVENT", None),
  ("Channel events", "通道事件", "通道出现特定情况（启动 / 停止等）时是否产生事件消息。", "MQIA_CHANNEL_EVENT", None),
  ("Channel auto definition events", "通道自动定义事件", "自动定义通道时是否产生事件消息。", "MQIA_CHANNEL_AUTO_DEF_EVENT", None),
  ("SSL events", "SSL 事件", "TLS 连接建立失败时是否产生事件消息。", "MQIA_SSL_EVENT", None),
  ("Configuration events", "配置事件", "创建 / 修改对象时是否产生事件消息。", "MQIA_CONFIGURATION_EVENT", None),
  ("Logger events", "记录器事件", "恢复日志写入更改时是否产生事件消息。", "MQIA_LOGGER_EVENT", None),
 ],
 "ssl": [
  ("Key repository", "密钥存储库", "队列管理器密钥存储库的完整路径。", "MQCA_SSL_KEY_REPOSITORY", None),
  ("Certificate label", "证书标签", "队列管理器使用的证书标签。", "MQCA_CERT_LABEL", None),
  ("Revocation namelist", "撤销名称列表", "证书撤销信息（CRL / OCSP）认证信息对象的名称列表。", "MQCA_SSL_CRL_NAMELIST", None),
  ("Cryptographic hardware", "加密硬件", "加密硬件的配置参数。", "MQCA_SSL_CRYPTO_HARDWARE", None),
  ("SSL reset count", "SSL 复位计数", "密钥重新协商前发送 / 接收的未加密字节数。", "MQIA_SSL_RESET_COUNT", None),
  ("SSL FIPS required", "需要 SSL FIPS", "是否仅使用 FIPS 认证的加密算法。", "MQIA_SSL_FIPS_REQUIRED", None),
  ("Suite B strength", "Suite B 强度", "是否使用 Suite B 加密及其强度。", "MQIA_SUITE_B_STRENGTH", None),
  ("Certificate validation policy", "证书验证策略", "TLS 证书验证使用的策略。", "MQIA_CERT_VAL_POLICY", None),
 ],
 "statistics": [
  ("Creation date", "创建日期", "队列管理器的创建日期。", "MQCA_CREATION_DATE", None),
  ("Creation time", "创建时间", "队列管理器的创建时间。", "MQCA_CREATION_TIME", None),
  ("Alteration date", "变更日期", "属性最后一次修改的日期。", "MQCA_ALTERATION_DATE", None),
  ("Alteration time", "变更时间", "属性最后一次修改的时间。", "MQCA_ALTERATION_TIME", None),
  ("QMID", "QMID", "队列管理器内部生成的唯一标识。", "MQCA_Q_MGR_IDENTIFIER", None),
 ],
 "online_monitoring": [
  ("Channel monitoring", "通道监视", "托管通道的联机监控数据采集级别。", "MQIA_MONITORING_CHANNEL", "MQMON"),
  ("Queue monitoring", "队列监视", "托管队列的联机监控数据采集级别。", "MQIA_MONITORING_Q", "MQMON"),
  ("Auto CLUSSDR monitoring", "自动 CLUSSDR 监视", "自动定义的集群发送方通道的监控数据采集级别。", "MQIA_MONITORING_AUTO_CLUSSDR", "MQMON"),
  ("Activity conn override", "活动跟踪覆盖", "应用能否用 MQCONNX 覆盖活动跟踪设置。", "MQIA_ACTIVITY_CONN_OVERRIDE", "MQMON"),
  ("Activity trace", "活动跟踪", "是否采集 MQI 应用活动跟踪信息。", "MQIA_ACTIVITY_TRACE", "MQMON"),
 ],
 "statistics_monitoring": [
  ("MQI statistics", "MQI 统计信息", "是否采集队列管理器的 MQI 统计数据。", "MQIA_STATISTICS_MQI", "MQMON"),
  ("Queue statistics", "队列统计", "托管队列的统计数据采集级别。", "MQIA_STATISTICS_Q", "MQMON"),
  ("Channel statistics", "通道统计", "托管通道的统计数据采集级别。", "MQIA_STATISTICS_CHANNEL", "MQMON"),
  ("Auto CLUSSDR statistics", "自动 CLUSSDR 统计信息", "自动定义的集群发送方通道的统计数据采集级别。", "MQIA_STATISTICS_AUTO_CLUSSDR", "MQMON"),
  ("Statistics interval", "统计时间间隔", "统计数据写入监控队列的时间间隔（秒）。", "MQIA_STATISTICS_INTERVAL", None),
 ],
 "accounting_monitoring": [
  ("MQI accounting", "MQI 记账", "是否采集队列管理器的 MQI 记账数据。", "MQIA_ACCOUNTING_MQI", "MQMON"),
  ("Queue accounting", "队列记账", "托管队列的记账数据采集级别。", "MQIA_ACCOUNTING_Q", "MQMON"),
  ("Accounting interval", "记账时间间隔", "记账数据写入监控队列的时间间隔（秒）。", "MQIA_ACCOUNTING_INTERVAL", None),
  ("Accounting conn override", "记账连接覆盖", "应用能否用 MQCONNX 覆盖记账设置。", "MQIA_ACCOUNTING_CONN_OVERRIDE", "MQMON"),
 ],
 "pubsub": [
  ("Publish/Subscribe mode", "发布/预订方式", "V7 发布/预订引擎与排队式发布/预订接口的启用方式。", "MQIA_PUBSUB_MODE", "MQPSM"),
  ("Message retry count", "消息重试次数", "发布/预订引擎处理失败消息的重试次数。", "MQIA_PUBSUB_MAXMSG_RETRY_COUNT", None),
  ("Publish/Subscribe syncpoint", "发布/预订同步点", "消息是否在同步点下处理。", "MQIA_PUBSUB_SYNC_PT", None),
  ("Undelivered non-persistent input message", "未发送的非持久输入消息", "无法投递的非持久输入消息的处理方式。", "MQIA_PUBSUB_NP_MSG", None),
  ("Undelivered non-persistent response", "未发送的非持久响应", "无法投递的非持久响应的处理方式。", "MQIA_PUBSUB_NP_RESP", None),
  ("Tree life extended", "树生存期", "发布/预订树的生存期扩展（分钟）。", "MQIA_TREE_LIFE_TIME", None),
 ],
 "channels": [
  ("Max channels", "最大通道数", "允许的最大通道数。", "MQIA_MAX_CHANNELS", None),
  ("Active channels", "活动通道", "当前可处于活动状态的最大通道数。", "MQIA_ACTIVE_CHANNELS", None),
  ("TCP channels", "使用 TCP 的通道", "使用 TCP/IP 传输协议的最大通道数。", "MQIA_TCP_CHANNELS", None),
  ("TCP keepalive", "TCP 保持活动", "是否用 Keepalive 设施检查连接另一端可用。", "MQIA_TCP_KEEP_ALIVE", "MQTCPKEEP"),
  ("Outbound port min", "最低端口地址", "绑定出站通道使用的最低端口号。", "MQIA_OUTBOUND_PORT_MIN", None),
  ("Outbound port max", "最高端口地址", "绑定出站通道使用的最高端口号。", "MQIA_OUTBOUND_PORT_MAX", None),
 ],
}

# 客户端常量表 / MQ Explorer common.properties 都没收录的枚举值，用策展键兜底
# （KT 侧走 ObjectPropertyField.valueConstMap → curatedValueKey → queue_value_*）。
VALUES = {
 "iasy_status_starting": ("Starting", "正在启动"),
 "iasy_status_running": ("Running", "正在运行"),
 "iasy_status_quiescing": ("Quiescing", "正在停止"),
 "iasy_status_standby": ("Standby", "备用"),
 "suiteb_none": ("None", "无"),
 "suiteb_128_bit": ("128-bit", "128 位"),
 "suiteb_192_bit": ("192-bit", "192 位"),
 "suiteb_128_192_bit": ("128-bit and 192-bit", "128 位和 192 位"),
}

zh, en = collections.OrderedDict(), collections.OrderedDict()
for tk, en_t, zh_t in GROUP_TITLES:
    zh[tk] = zh_t
    en[tk] = en_t
for g, fields in FIELDS.items():
    for label_en, name_zh, desc_zh, pcf, prefix in fields:
        suffix = pcf.lower().removeprefix("mqca_").removeprefix("mqia_")
        name_key = "qmgr_prop_name_" + suffix
        desc_key = "qmgr_prop_desc_" + suffix
        zh[name_key] = name_zh
        en[name_key] = label_en
        zh[desc_key] = desc_zh
for suffix, (ven, vzh) in VALUES.items():
    key = "queue_value_" + suffix
    zh[key] = vzh
    en[key] = ven

root = r"c:\workspace\code\git\mq-client"
for lang, table in (("zh-CN", zh), ("en-US", en)):
    p = os.path.join(root, "ibm-mq-ui", "src", "commonMain", "composeResources", "files", "i18n", "ibm_mq_%s.json" % lang)
    d = json.load(io.open(p, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)
    d.update(table)
    io.open(p, "w", encoding="utf-8", newline="\n").write(json.dumps(d, ensure_ascii=False, indent=2) + "\n")
    print(p, "updated, %d keys total, +%d" % (len(d), len(table)))
