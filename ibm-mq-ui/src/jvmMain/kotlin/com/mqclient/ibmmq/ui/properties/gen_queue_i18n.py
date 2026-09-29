# 队列属性 i18n 生成器：ibm_mq_*.json 的 queue_prop_name_* / queue_prop_desc_* /
# queue_value_* 键由本脚本生成（数据源 = 本脚本内置的 MQ Explorer 官方双语文案）。
# 字段变更后重跑：python <本文件>（cwd 任意）。
import json, io, collections, os

# (label_en, name_zh, desc_zh, pcf_key, value_prefix)
FIELDS = {
 "general": [
  ("Queue name", "队列名称", "对象的唯一名称，创建后不可更改。", "MQCA_Q_NAME", None),
  ("Queue type", "队列类型", "本地 / 别名 / 远程 / 模型 / 集群队列，创建后不可更改。", "MQIA_Q_TYPE", "MQQT"),
  ("QSG disposition", "QSG 处置", "z/OS 共享队列环境的对象处置方式（仅 z/OS）。", "MQIA_QSG_DISP", "MQQSGD"),
  ("Description", "描述", "队列用途的备注说明。", "MQCA_Q_DESC", None),
  ("Put messages", "放置消息", "是否允许应用向队列放入消息。", "MQIA_INHIBIT_PUT", "MQQA"),
  ("Get messages", "获取消息", "是否允许应用从队列取出消息。", "MQIA_INHIBIT_GET", "MQQA"),
  ("Default priority", "缺省优先级", "放入队列的消息的默认优先级（0-9）。", "MQIA_DEF_PRIORITY", None),
  ("Default persistence", "缺省持久性", "应用未指定时，新消息默认是否持久化。", "MQIA_DEF_PERSISTENCE", "MQPER"),
  ("Scope", "范围", "队列定义的作用范围。", "MQIA_SCOPE", "MQSCO"),
  ("Usage", "用法", "正常队列或传输队列（XMITQ）。", "MQIA_USAGE", "MQUS"),
  ("Base type", "基本类型", "别名队列解析到的目标类型（队列 / 主题）。", "MQIA_BASE_TYPE", "MQOT"),
  ("Remote queue", "远程队列", "远程队列定义指向的目标队列名。", "MQCA_REMOTE_Q_NAME", None),
  ("Remote queue manager", "远程队列管理器", "目标消息所在的队列管理器名。", "MQCA_REMOTE_Q_MGR_NAME", None),
  ("Transmission queue", "传输队列", "发往远程队列管理器所经的传输队列。", "MQCA_XMIT_Q_NAME", None),
 ],
 "extended": [
  ("Max queue depth", "最大队列深度", "队列可容纳的最大消息数。", "MQIA_MAX_Q_DEPTH", None),
  ("Max message length", "最大消息长度", "单条消息的最大长度（字节）。", "MQIA_MAX_MSG_LENGTH", None),
  ("Shareable", "可共享", "多个应用能否同时打开该队列读取。", "MQIA_SHAREABILITY", "MQSHA"),
  ("Default binding", "缺省绑定", "应用打开队列时的句柄绑定方式。", "MQIA_DEF_BIND", "MQBND"),
  ("Msg delivery sequence", "消息传递顺序", "按优先级或先进先出投递。", "MQIA_MSG_DELIVERY_SEQUENCE", "MQMDS"),
  ("Default open input option", "缺省打开输入选项", "缺省的独占 / 共享打开输入方式。", "MQIA_DEF_INPUT_OPEN_OPTION", "MQOO"),
  ("Property control", "属性控制", "消息属性随消息传递的方式。", "MQIA_PROPERTY_CONTROL", "MQPROP"),
  ("Custom", "定制", "按分号分隔的自定义属性。", "MQCA_CUSTOM", None),
  ("Retention interval", "保留时间间隔", "队列首次使用后保留的小时数。", "MQIA_RETENTION_INTERVAL", None),
 ],
 "cluster": [
  ("Cluster name", "集群名称", "队列所属的（手动定义）集群。", "MQCA_CLUSTER_NAME", None),
  ("Cluster namelist", "集群名称列表", "队列所属多个集群的名称列表。", "MQCA_CLUSTER_NAMELIST", None),
  ("Default binding", "缺省绑定", "集群队列的绑定方式。", "MQIA_DEF_BIND", "MQBND"),
  ("Cluster workload use queue", "集群工作负载使用队列", "远程放入时工作负载算法对本地队列的处理。", "MQIA_CLWL_USEQ", "MQCLWLUSEQ"),
 ],
 "triggering": [
  ("Trigger control", "触发控制", "是否启用触发。", "MQIA_TRIGGER_CONTROL", "MQTC"),
  ("Trigger type", "触发器类型", "产生触发事件的条件（首条 / 每条 / 深度 / 无）。", "MQIA_TRIGGER_TYPE", "MQTRG"),
  ("Trigger depth", "触发器深度", "触发事件所需的消息数（类型=深度时生效）。", "MQIA_TRIGGER_DEPTH", None),
  ("Trigger message priority", "触发器消息优先级", "计入触发事件的最小消息优先级。", "MQIA_TRIGGER_MSG_PRIORITY", None),
  ("Trigger data", "触发器数据", "插入触发消息的透传数据。", "MQCA_TRIGGER_DATA", None),
  ("Process name", "进程名称", "触发时启动的进程定义（本地队列）。", "MQCA_PROCESS_NAME", None),
  ("Initiation queue", "启动队列", "触发消息写入的启动队列。", "MQCA_INITIATION_Q_NAME", None),
 ],
 "events": [
  ("Queue full event", "队列满事件", "队列满时是否产生事件。", "MQIA_Q_DEPTH_MAX_EVENT", "MQEVO"),
  ("Queue depth high event", "队列深度高事件", "深度达到上限时是否产生事件。", "MQIA_Q_DEPTH_HIGH_EVENT", "MQEVO"),
  ("Queue depth high limit", "队列深度上限", "触发深度高事件的百分比。", "MQIA_Q_DEPTH_HIGH_LIMIT", None),
  ("Queue depth low event", "队列深度低事件", "深度降到下限时是否产生事件。", "MQIA_Q_DEPTH_LOW_EVENT", "MQEVO"),
  ("Queue depth low limit", "队列深度下限", "触发深度低事件的百分比。", "MQIA_Q_DEPTH_LOW_LIMIT", None),
 ],
 "storage": [
  ("Backout requeue queue", "回退重新排队队列", "超过回退阈值后消息转入的队列。", "MQCA_BACKOUT_REQ_Q_NAME", None),
  ("Backout threshold", "回退阈值", "消息回退次数达到该阈值后转投回退队列。", "MQIA_BACKOUT_THRESHOLD", None),
  ("Hardened backout", "强化回退", "回退计数是否在重启后保留（z/OS）。", "MQIA_HARDEN_GET_BACKOUT", "MQHA"),
  ("Non-persistent message class", "非持久消息类", "非持久消息的性能 / 可靠性级别。", "MQIA_NPM_CLASS", "MQNPM"),
  ("Storage class", "存储类", "z/OS 下队列使用的存储类。", "MQCA_STORAGE_CLASS", None),
 ],
 "statistics": [
  ("Creation date", "创建日期", "队列创建日期。", "MQCA_CREATION_DATE", None),
  ("Creation time", "创建时间", "队列创建时间。", "MQCA_CREATION_TIME", None),
  ("Alteration date", "变更日期", "属性最后一次修改的日期。", "MQCA_ALTERATION_DATE", None),
  ("Alteration time", "变更时间", "属性最后一次修改的时间。", "MQCA_ALTERATION_TIME", None),
  ("Current queue depth", "当前队列深度", "队列中当前的消息数。", "MQIA_CURRENT_Q_DEPTH", None),
  ("Open input count", "打开输入计数", "当前以输入方式打开该队列的句柄数。", "MQIA_OPEN_INPUT_COUNT", None),
  ("Open output count", "打开输出计数", "当前以输出方式打开该队列的句柄数。", "MQIA_OPEN_OUTPUT_COUNT", None),
  ("Monitoring", "监控", "在线监控数据的采集级别。", "MQIA_MONITORING_Q", "MQMON"),
  ("Statistics", "统计", "统计数据的采集级别。", "MQIA_STATISTICS_Q", "MQSTS"),
  ("Accounting", "记账", "记账数据的采集级别。", "MQIA_ACCOUNTING_Q", "MQACCTQ"),
 ],
}

VALUES = {
 "get_allowed": ("Allowed", "允许"), "put_allowed": ("Allowed", "允许"),
 "get_inhibited": ("Inhibited", "阻止"), "put_inhibited": ("Inhibited", "阻止"),
 "not_persistent": ("Not persistent", "非持久"), "persistent": ("Persistent", "持久"),
 "local": ("Local queue", "本地队列"), "alias": ("Alias queue", "别名队列"),
 "remote": ("Remote queue", "远程队列"), "model": ("Model queue", "模型队列"),
 "cluster_queue": ("Cluster queue", "集群队列"),
 "normal": ("Normal", "正常"), "transmission": ("Transmission", "传输"),
 "q": ("Queue", "队列"), "topic": ("Topic", "主题"),
 "version_1": ("Version 1", "版本 1"),
 "bind_on_open": ("On open", "打开时绑定"), "bind_not_fixed": ("Not fixed", "不固定"),
 "priority": ("Priority", "按优先级"), "fifo": ("FIFO", "先进先出"),
 "on": ("Enabled", "已启用"), "off": ("Disabled", "已禁用"),
 "first": ("First message", "首条消息"), "every": ("Every message", "每条消息"),
 "trigger_depth": ("Queue depth", "达到深度"),
 "evo_enabled": ("Enabled", "已启用"), "evo_disabled": ("Disabled", "已禁用"),
 "shareable": ("Shareable", "可共享"), "not_shareable": ("Not shareable", "不可共享"),
 "clwluseq_local": ("When possible", "优先本地队列"), "clwluseq_any": ("Any", "任意队列管理器"),
 "q_mgr": ("Queue manager", "队列管理器"), "group": ("Group", "组"),
 "copy": ("Copy", "副本"), "shared": ("Shared", "共享"),
 "hardened": ("Hardened", "已强化"), "not_hardened": ("Not hardened", "未强化"),
 "npm_normal": ("Normal", "常规"), "npm_high": ("High", "高"),
 "prop_compatibility": ("Compatibility", "兼容"), "prop_none": ("None", "不传递"),
 "prop_all": ("All", "全部传递"), "prop_force_mqrfh2": ("Force MQRFH2", "强制 MQRFH2"),
 "oo_input_exclusive": ("Exclusive", "独占"), "oo_input_shared": ("Shared", "共享"),
 "mds_priority": ("Priority", "按优先级"), "mds_fifo": ("FIFO", "先进先出"),
 "bnd_on_open": ("On open", "打开时绑定"), "bnd_not_fixed": ("Not fixed", "不固定"),
 "sha_shareable": ("Shareable", "可共享"), "sha_not_shareable": ("Not shareable", "不可共享"),
 "scope_q_mgr": ("Queue manager", "队列管理器"),
 "ot_local": ("Local queue", "本地队列"), "ot_alias": ("Alias queue", "别名队列"),
 "ot_remote": ("Remote queue", "远程队列"), "ot_model": ("Model queue", "模型队列"),
 "ot_cluster": ("Cluster queue", "集群队列"), "ot_topic": ("Topic", "主题"),
 "us_normal": ("Normal", "正常"), "us_xmission": ("Transmission", "传输"),
 "qt_local": ("Local queue", "本地队列"), "qt_alias": ("Alias queue", "别名队列"),
 "qt_remote": ("Remote queue", "远程队列"), "qt_model": ("Model queue", "模型队列"),
 "qt_cluster": ("Cluster queue", "集群队列"),
 "per_not_persistent": ("Not persistent", "非持久"), "per_persistent": ("Persistent", "持久"),
 "tc_on": ("Enabled", "已启用"), "tc_off": ("Disabled", "已禁用"),
 "trg_first": ("First message", "首条消息"), "trg_every": ("Every message", "每条消息"),
 "trg_depth": ("Queue depth", "达到深度"),
 "qsgd_q_mgr": ("Queue manager", "队列管理器"), "qsgd_group": ("Group", "组"),
 "qsgd_copy": ("Copy", "副本"), "qsgd_shared": ("Shared", "共享"),
 "bnd_yes": ("On open", "打开时绑定"), "bnd_no": ("Not fixed", "不固定"),
 "clwluseq_qmgr": ("When possible", "优先本地队列"), "clwluseq_std": ("Any", "任意队列管理器"),
 "ha_hardened": ("Hardened", "已强化"), "ha_not_hardened": ("Not hardened", "未强化"),
 "npm_class_normal": ("Normal", "常规"), "npm_class_high": ("High", "高"),
 "prop_compat": ("Compatibility", "兼容"), "prop_none": ("None", "不传递"),
 "prop_all": ("All", "全部传递"), "prop_force_rfh2": ("Force MQRFH2", "强制 MQRFH2"),
 "oo_exclusive": ("Exclusive", "独占"), "oo_shared": ("Shared", "共享"),
 "evo_en": ("Enabled", "已启用"), "evo_dis": ("Disabled", "已禁用"),
 "sha_yes": ("Shareable", "可共享"), "sha_no": ("Not shareable", "不可共享"),
 "clwl_local": ("When possible", "优先本地队列"), "clwl_any": ("Any", "任意队列管理器"),
 "qmgr": ("Queue manager", "队列管理器"), "qsg_group": ("Group", "组"),
 "qsg_copy": ("Copy", "副本"), "qsg_shared": ("Shared", "共享"),
 "ha_yes": ("Hardened", "已强化"), "ha_no": ("Not hardened", "未强化"),
 "npm_normal2": ("Normal", "常规"), "npm_high2": ("High", "高"),
}

zh, en = collections.OrderedDict(), collections.OrderedDict()
groups_kt = []
group_titles = {
 "general": "queue_props_group_general|General|常规",
 "extended": "queue_props_group_extended|Extended|扩展",
 "cluster": "queue_props_group_cluster|Cluster|集群",
 "triggering": "queue_props_group_triggering|Triggering|触发",
 "events": "queue_props_group_events|Events|事件",
 "storage": "queue_props_group_storage|Storage|存储",
 "statistics": "queue_props_group_statistics|Statistics|统计信息",
}
for g, spec in group_titles.items():
    tk, en_t, zh_t = spec.split("|")
    zh[tk] = zh_t
    en[tk] = en_t
    fields_kt = []
    for label_en, name_zh, desc_zh, pcf, prefix in FIELDS[g]:
        suffix = pcf.lower().removeprefix("mqca_").removeprefix("mqia_")
        name_key = "queue_prop_name_" + suffix
        desc_key = "queue_prop_desc_" + suffix
        zh[name_key] = name_zh
        en[name_key] = label_en
        zh[desc_key] = desc_zh
        prefix_lit = ('"%s"' % prefix) if prefix else "null"
        fields_kt.append(
            "            QueuePropertyField(\n"
            '                nameKey = "%s",\n'
            '                descKey = "%s",\n'
            '                labelEn = "%s",\n'
            '                keys = listOf("%s"),\n'
            "                valuePrefix = %s,\n"
            "            )," % (name_key, desc_key, label_en, pcf, prefix_lit))

    groups_kt.append(
        "    QueuePropertyGroup(\n"
        '        titleKey = "%s",\n'
        "        fields = listOf(\n"
        "%s\n"
        "        ),\n"
        "    )," % (tk, "\n".join(fields_kt)))

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
    print(p, len(d), "keys")

io.open("/tmp/groups-kt.txt", "w", encoding="utf-8").write("\n".join(groups_kt))
print("kt fragment written:", len(groups_kt), "groups")
