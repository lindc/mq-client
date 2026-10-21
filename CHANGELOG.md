# Changelog

本文件记录 MQClient 的显著变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased] — 2026-10-21

### Added

- **RocketMQ 主题消息对话框**（commit fbef9d9）：主题卡片 ✉ 打开「浏览 / 发送」
  双 Tab 对话框（外壳与 KafkaMessagesDialog 逐行同构）。浏览走临时
  `DefaultMQPullConsumer` 按 Queue + Offset 定点 pull，**只拉不提交位点**——
  5.3.x 的 AdminExt 已无 `viewMessage(topic, offset)`（解包 5.3.2/5.5.1 核对），
  精确浏览只能走 PullConsumer；表格展示 Queue/Offset/MsgId/Tags/Keys/Value/
  时间/大小， ⓘ 走公共 MessageDetailDialog（解释器区，body 全量随行无需二次
  现查）。发送走临时 `DefaultMQProducer`（Tags / Keys / 用户属性注入，同步等
  ack，finally 必 shutdown），单文件可编辑预览 + 多文件批量任务表与 Kafka
  同款；Kafka 的 HeadersEditor/HeaderDraft 上移 ui-common（文案参数化，
  hexText 传空隐藏 HEX 勾选），两模块发送页外观 100% 一致。
- **TLS start 窗口抽取**（同 commit）：AdminClient 的「构造前备证书 + start()
  窗口注入 `TlsSystemConfig` + 快照还原 + 互斥锁」抽为 `RocketMqTlsStart`，
  AdminExt / Producer / PullConsumer 三方共用，行为不变。
- **RocketMQ 卡片「询问 AI」入口**（commit f0bacbb）：Topic / 消费组卡片新增
  Android 图标（actions 首位，宿主未 Provide 时隐藏），经 `RocketMqAiChainBuilder`
  组装 chain 数组上下文（NAMESERVER —hosts→ TOPIC —consumed_by→ 组 /
  NAMESERVER —hosts→ GROUP），与 ibm-mq / kafka / rabbitmq 同契约。为此慢扫 /
  快扫顺手多采集（同一次 RPC 响应，零额外请求）：组的 consumeMode /
  subscribedTopics、逐队列 lag 行（降序截前 16 进缓存）。截断三层防 Token 爆炸：
  缓存每组前 16 → Group 链 consumerProgress 前 10 → Topic 链下游组前 4、单组
  queueLags 前 8；CODE 17 / 离线组的缺失字段整体省略不报错。
- **AI 连接级「忽略 SSL 证书校验」**（commit d9b1d32）：`AiConnection` 新增
  `ignoreSsl`（默认 false，旧 config.toml 向后兼容）。默认客户端（CIO 严格
  校验）行为零变化；开启的连接走惰性创建的 trust-all 客户端——**Java 引擎**
  而非 CIO：Ktor 3.2.3 的 CIO 主机名校验公开 API 关不掉（引擎强制
  `serverName = host`），IP 直连 + CN=localhost 的自签证书会握手失败，而 JDK
  HttpClient 默认不做主机名校验，`sslContext` 换成 trust-all 即链 + 主机名完整
  绕过。SSL 异常双层翻译：适配器层 `isAiSslFailure`（类型 + cause 链消息匹配）
  在 `testQuietly` 里重抛带前缀的 AiException（非 SSL 失败仍吞掉返回 false），
  ai-ui 的 `AiSslHint` 把面板错误与设置页测试失败统一翻成「开启忽略 SSL」指引
  （设置页测试失败从纯图标升级为图标 + 可读原因）。三处表单（设置页
  ConnectionCard + 两个新建向导）加同款 ToggleRow。
- **RocketMQ 客户端日志重定向**（commit 704103a）：5.x 客户端默认把
  `rocketmq_client.log` 写到 `~/logs/rocketmqlogs`，污染用户 HOME。
  `RocketMqClientLogConfigurator`（与 IBM 的同名机制同构）在
  `RocketMqModule.initialize` 第一行设系统属性——shaded logback 在首个日志类
  加载时定格配置，模块咽喉点保证早于一切实例化。属性名两套都设：5.x 的
  `rocketmq.log.root` / `rocketmq.log.file.maxIndex` / `rocketmq.log.level`
  （解包 rmq.client.logback.xml 实测；`rocketmq.client.logRoot` 是 4.x 老名）
  + 4.x 兼容名。目录走 `ConfigStore.configDir()`（跟随 `mqclient.config.dir`
  覆盖），绝对路径 + 自动 mkdirs；幂等，失败只 warn 回落默认目录。

### Changed

- **链路分析系统提示词中立化**（commits f0bacbb / c4cbd27）：「IBM MQ 运维
  助手」→「MQ 中间件运维助手（IBM MQ / Kafka / RabbitMQ / RocketMQ 等）」，
  ui-common 两张语言表 + ai-ui 缺键回落文案同步。

### Tests

- `RocketMqAiChainBuilderTest`（11 例）：下游组挑选（订阅过滤 / lag 降序 /
  截断）+ 两链 JSON 结构（无下游无 consumed_by、routes、queueLags 过滤截断、
  lag 缓存缺失退化、离线组字段整体省略、Top 10 截断）。
- `RocketMqClientLogConfiguratorTest`（2 例）：临时 config.dir 下目录创建 +
  属性生效 / 重复调用幂等。
- 既有契约测试回归：openai / anthropic 适配器（MockEngine）、AI 面板、
  AI 连接仓储全绿。

## [Unreleased] — 2026-10-20

### Added

- **RocketMQ 连接表单 TLS 支持**（commit f3f09c9）：`RocketMqConfig` 新增
  `tlsEnabled` / `tlsVerifyServer` / `trustStorePath` / `trustStorePassword` /
  `keyStorePath` / `keyStorePassword` 六字段（两个密码键名命中
  `SENSITIVE_NAME_KEYWORDS` 落盘自动加密）。表单在「高级」上方加「TLS 安全认证」
  区块：启用与「校验服务器证书」两个开关（关闭校验时红字警示仅建议联调使用）、
  Truststore / Keystore 经 ui-common `PathRow` + `pickFile` 选文件
  （.p12/.pfx/.jks），密码掩码输入并沿用 secretKey 的「编辑不回填、留空沿用
  原值」语义。校验：启用校验时 Truststore 路径与密码必填，Keystore 路径与
  密码成对填写。
- **JKS/P12 → PEM 转换层**（rocketmq-service `TlsPemExport`）：RocketMQ 5.3.1
  Remoting 客户端（Netty `SslContextBuilder`）只吃 PEM，与 Kafka / IBM MQ 表单
  的 JKS/P12 证书库体系不一致 —— 按扩展名判型（.p12/.pfx → PKCS12，其余 → JKS，
  与 Kafka 同规则）加载证书库，导出全部受信证书与第一个私钥条目（证书链 +
  **未加密 PKCS#8** 私钥，keystore 密码只在加载时用，客户端侧
  `tls.client.keyPassword` 保持空）；临时 PEM 写系统临时目录、POSIX 600，
  close 即删。
- **RocketMQAdminClient TLS 注入**：`useTLS` 快照与 `SslContext` 都在
  `DefaultMQAdminExt` **构造时**定格到实例 —— 在模块级互斥锁窗口内「写
  `tls.enable` property + `TlsSystemConfig` 静态字段 → 构造 → 快照还原」，
  多连接不同证书并发构造互不踩；三个客户端坑位（拉 5.3.1 sources 核实）：
  `tlsTestModeEnable` 默认 true = 信任所有证书（Truststore 形同虚设）必须关、
  `buildSslContext` 开头会读机器全局 `/etc/rocketmq/tls.properties` 反向覆盖
  字段（窗口内把 `tlsConfigFile` 指向随机不存在路径屏蔽）、证书 IOException
  只记日志 **sslContext=null 静默降级明文** —— 构造前用 `KeyStore.load`
  预校验把错误提前转成可读文案。`mapError` 增加 SSL / certificate 关键词映射。

### Fixed

- **RocketMQ 测试连接抛「模块未初始化」**：`RocketMqServiceAdapter` 缺
  `initialize` / `dispose` override（`ServiceAdapter` 默认空实现静默跳过），
  `RocketMqModule.initialize` 从未被调用，`testConnection` 拿到的 manager 恒
  null —— 与 Kafka / RabbitMQ adapter 同构补上。
- **RocketMQ 表单 ACL 输入框无占位符**：AccessKey 加「AccessKey（未开启 ACL
  可留空）」；SecretKey 占位区分新建 / 编辑两种语义（新建「SecretKey（未开启
  ACL 可留空）」、编辑维持「已加密保存，留空沿用原值」）。

### Tests

- `RocketMqConfigTest`（6 例）：options 往返（全字段 / 默认值）、布尔解析只认
  true/false 字面量、路径反斜杠归一、keystore 扩展名判型、PEM 编码头尾标签与
  64 列 Base64。KeyStore 全链路加载靠真机冒烟（`:app-ui:run`）。

## [Unreleased] — 2026-09-29

### Added

- **通道属性对话框新增「运行状态」分组 + 序列号重置**（commit 6186970）：
  第三分组（常规 / 全部属性之间）实时 INQUIRE_CHANNEL_STATUS，展示序列号 /
  in-doubt / 消息数 / 收发字节 / 最后消息时间，与 DISPLAY CHANNEL 缓存严格分层；
  PropertyGroupsPane 加 `customPanes` 扩展点（其余 9 类对象行为不变）。序列号
  重置走二次确认对话框（当前值 + 新值输入）经 MqscExecutor 发
  `RESET CHANNEL … SEQNUM(n)`。坑位（反编译 allclient 10.0.0.5 + 真机实测）：
  序列号 wire 名是 `MQIACH_CURRENT/LAST_SEQ_NUMBER`（经典 `*_SEQ_NO` 常量在
  该 jar 不存在）；按名查已停通道回 3065 MQRCCF_CHL_STATUS_NOT_FOUND（非空
  结果，与监听器 2085 同款坑）；9.2.4 容器 QM 的 CURRENT 状态行流量计数恒 0，
  真实序列号在 SAVED 行（1012 实例类型），读取做 SAVED 回落。
- **消息详情对话框新增 dmpmqmsg 导出/导入**（commit 3de8a16）：底部按钮栏改
  「下载消息体 | 导出 dmpmqmsg | 导入 dmpmqmsg | 关闭」。`DmpmqmsgFormat` 按
  真实 dmpmqmsg 9.2.4.0 导出样例逐行校准（`A` 属性行 / `X` hex 数据行
  25 字节每行 / 空行分隔消息 / 字符域带 MQMD padding 输出、字节域转 hex，
  经典 `*_SEQ_NO` 常量名陷阱均已实证）；宽松行解析兼容 `-dH` 无文件头产物，
  解析出 0 条消息即判定非 dmpmqmsg 格式走 fallback（取消 / 按普通文件发送）。
  导入预览对话框支持多消息选择，属性还原策略：MessageId 不还原（MQ 重新
  生成），CorrelId（hex→24 字节）/ Persistence / Priority / Expiry / ReplyTo /
  Format / CCSID 还原；MessageDetail 扩展 18 个导出所需 MQMD 原始字段，
  `sendMessageToQueue` 加可选参数（向后兼容）。**官方工具回灌验收通过**
  （www.chillbear.top 容器 ibmmq / QM1 9.2.4.0 实测）：mq-client 导出文件经
  官方 `dmpmqmsg -m QM1 -o <队列> -f` 成功回灌，`Read 1 message 43 bytes →
  Written 43 bytes`、`CURDEPTH(1)`，且回灌消息与原文件 **MQMD 全字段逐字段
  一致（含 MessageId / AccountingToken / ApplIdentityData / GroupId）**、消息体
  43 字节逐字节一致；官方 `dmpmqmsg -i` 导出与 `DmpmqmsgFormat.export` 输出
  逐行一致。单测 6 例（fixture = 真实导出样例）。
- **属性对话框「全部属性」对齐 MQ Explorer 文案**：新增 `scripts/gen_mq_attr_display.py`，
  从本机 MQ Explorer（`com.ibm.mq.explorer.core` 插件）提取 `MQXXX.title`
  友好名（属性名 + 枚举常量值，en / zh-CN 各 1728 条，生成 ibm-mq-ui 模块
  i18n 表 `ibm_mq_attrs_<lang>.json`）与 `DmQueue.xml` 枚举归属表（生成
  ibm-mq-service `MqQueueAttrEnums.kt`）。未分组的兜底属性名/整型枚举值经
  `MqAttributeDisplay` 归属判定后走 `<RAW>.title` 查表显示（如
  `MQIA_DEF_PUT_RESPONSE_TYPE=1` → 「同步」），非枚举 / RANGE 型回落原始值。
- **IBM MQ 专属拓扑面板**（ibm-mq-ui `ui.topology` 新包）：固定列序布局
  （alias→topic→sub→remote→xmitq→channel→远程QMGR→alias→local，alias 链按
  深度展开）、并查集数据流泳道 + 重心法排序、kuiver `EdgeShape.CURVED`
  贝塞尔曲线边（BACK 环橙色）、0.2x–2.0x 缩放 + 右上角控件（重新扫描 /
  放大 / 缩小 / 适应 / 重置）、节点悬停与点击选中（图内描边高亮 + 底部详情卡）。
- **REMOTE_QMGR 虚拟节点**：优先通道 RQMNAME；实测部分 MQ 的 INQUIRE_CHANNEL
  对空 RQMNAME 不回该参数，此时经「共享 XMITQ 的 QREMOTE 的 RQMNAME」兜底
  推导（真机探针实测结论）。仅画 SENDER/REQUESTER 通道。
- **节点告警着色**：local queue 消息积压（CURDEPTH/MAXDEPTH ≥ 连接阈值，与
  队列列表页同源）→ 警告橙；SENDER/REQUESTER 通道 STOPPED/RETRYING → 主题红。
- **选中链路高亮**：上下游有向闭包内节点原样、边加粗提亮 + 虚线沿消息方向
  流动动画（循环递增 gapLength 等价相位位移）；闭包外节点压暗、无关边近隐。
- **拓扑面板 i18n**：`topology_*` 键进 zh-CN / en-US 模块表，消除硬编码中文。
- **列表卡片「在拓扑图中查看」图标前置**：挪到操作位首位，属性按钮恒在最后。
- **属性对话框「常规」分组新增「访问进程」**：扫描加一条 `INQUIRE_Q_STATUS`
  `StatusType=MQIACF_Q_HANDLE`（句柄状态，每打开句柄一条响应），取
  `MQIACF_PROCESS_ID` 按队列名聚合成 PID 列表（合成键
  `MQIACF_OPEN_PROCESS_IDS`）塞进快照；无人打开显示「无」，remote / model
  队列（无句柄概念）不显示。真机验证：AMQ.* 队列拿到 `openPids=[110, 130]`。
- **监听器属性对话框实装**（对照 Queue properties）：「常规」单页 +「全部属性」
  兜底，字段序按 MQ Explorer DmListener.xml grpseq（名称 / 描述 / 控制 / 传输
  协议 / 端口 / IP 地址 / 积压 / 运行状态 / 启动日期时间 / 进程号 / 变更日期时间）。
  布局抽成共用 PropertyGroupsPane，属性类型改通用命名 ObjectPropertyField /
  ObjectPropertyGroup / ResolvedObjectProperty。坑位记录：传输协议参数（1501）
  Java 客户端反查成别名键 `MQIACH_FIRST`；DM 枚举归属表按 Queue 模型生成、对
  listener 键误判（CONTROL=0 被归属成 MQSVC_TYPE_COMMAND「命令」），listener
  一律走 valuePrefix 前缀反查，且 MQSVC_ 族内 CONTROL / STATUS / TYPE 同值
  碰撞，前缀取到子族（MQSVC_CONTROL / MQSVC_STATUS）消歧。离线回归用真机
  SYSTEM.LISTENER.TCP.1 快照形状断言显示文本；停止的监听器隐藏运行状态行。
- **主题属性对话框实装**（对照 Topic properties）：「常规」单页 +「全部属性」
  兜底，字段按 DmTopic.xml（名称 / 类型 / 主题字符串 / 描述 / 持久预订 /
  通配符操作 / NPM·PM 传送 / 发布·预订及其作用域 / 代理预订行为 / 集群名称·
  路由 / 多点广播 / 模型队列 / 通信信息 / DLQ / 缺省持久性·优先级·PUT 响应 /
  定制 / 变更日期时间），中英 i18n 56 键。坑位：`MQTA_*` 一族常量服务 4 个
  属性（同值碰撞，前缀取到子族或精确常量）；通配符操作是单常量枚举
  （MQTA_PASSTHRU 无子族后缀），`MqAttributeNameMapper.lookupName` 加精确
  匹配分支。离线回归用真机 CUSTOMER.TOPIC 快照形状断言显示文本。
- **订阅属性对话框实装**（对照 Subscription properties）：「常规」单页 +
  「全部属性」兜底，字段按 INQUIRE_SUBSCRIPTION 响应文档（预订名称 / 类型 /
  主题名称·字符串 / 用户 / 目标类·名称·队列管理器 / 持久 / 到期 / 选择器
  类型·选择器 / 发布优先级 / 属性 / 仅请求 / 作用域 / 预订级别 / 可变用户 /
  通配符用途 / 身份数据·用户数据 / 创建·变更日期时间），中英 i18n 54 键。
  坑位：`MqAttributeNameMapper` ID 前缀族补 `MQBACF_`（SUB_ID 等字节 ID 不再
  落成 ATTR_7016）；客户端缺 `MQSAT_/MQDUR_/MQEXPIRY_/MQSCOPE_SUBSCRIPTION`
  枚举常量，IBM 真名是 MQSELTYPE_* / MQSUB_DURABLE_* / MQTSCOPE_* / MQRU_*；
  `MQSUB_DURABLE` 族内 YES=ALLOWED=1 同值碰撞，Durable 用 valueConstMap 精确
  映射；到期 -1 = MQEXPIRY_NOLIMIT 走 valueConstMap + 手补 title（无限制 /
  Unlimited）。字节 ID 类参数不进「全部属性」兜底（展示即乱码）。
- **其余 6 类对象属性对话框全部实装**（channel / process / namelist /
  auth info / comm info / service，对照各自 properties 的 General 页）：
  ChannelPropertyGroups（57 字段，定义 + 运行状态两大块）与
  MiscObjectPropertyGroups（process 8 / namelist 6 / authinfo 7 / comminfo 16 /
  service 12 字段）均走新抽的通用解析 `resolveGroupedObjectProperties`
  （listener / topic / subscription 同步切换，消除 5 份重复 resolve）。
  坑位：通道名（1501 字符串空间）与传输协议（1501 整型空间）Java 客户端分别
  反查成别名键 `MQCACH_FIRST` / `MQIACH_FIRST`（正名 MQCACH_CHANNEL_NAME /
  MQIACH_XMIT_PROTOCOL_TYPE 列在候选键首位）；`MQSCA_REQUIRED=0`、
  `MQOT_CURRENT_CHANNEL=1011`、`MQMCP_ALL=-1`、`MQMON_OFF=0` 均反射核实；
  `MQAT_AIX=MQAT_UNIX=6` 同值碰撞用 valueConstMap 消歧；auth info 的
  LDAP 口令属敏感信息，与订阅字节 ID 一样不进任何行。中英 i18n 各 224 键。
  离线回归：6 类真机快照形状（DEV.ADMIN.SVRCONN / ORDER.PROCESS /
  NL.BUSINESS / SYSTEM.DEFAULT.AUTHINFO.CRLLDAP / SYSTEM.DEFAULT.COMMINFO /
  SYSTEM.AMQP.SERVICE）断言枚举显示与敏感信息过滤。
- **旧通用拓扑管线整体下线**：删除 topology-ui、graph-core 模块与
  TopologyProvider SPI / IbmMQTopologyProvider / IbmMqTopologyStyle（附带删除
  一个在部分环境会无限挂死的 provider 单测）；拓扑渲染由 MQ 专属面板承担，
  TopologyFocusHolder 焦点机制保留。
- **拓扑入口走真正的 Tab**：MainWindow 焦点处理器 `onSelect` + `openTab`
  双调，侧栏选中 / Tab 条 / 内容区同步切换。
- **拓扑数据缓存优先**：进入面板只读慢扫缓存出图，缓存为空才隐式连接 / 扫描
  （CONNECTED 事件后补扫）；强制刷新走右上角「重新扫描」。

### Changed

- **属性窗口默认尺寸改为屏幕的 2/3**（宽高各 2/3）：新增 ui-common
  `rememberTwoThirdsScreenSize()`（`Toolkit.screenSize` 物理像素经
  `LocalDensity` 换算成 dp），`ObjectDetailDialog`（队列/通道/主题等 10 类
  对象属性窗口的通用外壳）`initialWidth/Height` 默认走它，`maxWidth/Height`
  默认改 `Dp.Infinity`（原 1400×1000 钳制在大屏上会截掉 2/3；调用方仍可显式
  覆盖）；队列管理器属性窗口 `ConnectionPropertiesDialog` 初始尺寸从固定
  560×640dp 同步改为屏幕 2/3。
- **ui-common 新增统一悬停提示组件 `AppTooltip`**：收敛全项目 5 处各自手写的
  `TooltipArea + Surface`（4dp 圆角 / surfaceVariant / 4dp 投影）实现——ObjectCard
  类型图标与操作按钮提示（×2）、IBM MQ 浏览/发送页 `HoverTooltip`（改为委托）、
  Kafka 消息浏览页 `HoverTooltip`（改为委托）、RabbitMQ 概览页集群节点提示
  （`NodeTooltip` 拆为内容 + 外壳）。节点提示原用 `inverseSurface` 反色对——固定
  中性色在 MaterialKolor TonalSpot 彩色主题下近黑、与全局观感脱节（Snackbar 换
  secondaryContainer 的同因先例），迁移后统一走 `surfaceVariant` 跟主题色相：
  浅色 = 色调浅灰底深字、深色 = 深灰底浅字。`TooltipArea` 为
  ExperimentalFoundationApi，组件文件级 `@file:OptIn`。

### Fixed

- **属性对话框「队列时间」显示 `[I@41cf4272`**：PCF 列表参数（如
  `MQIACF_Q_TIME_INDICATOR`）的底层形态是 Java `int[]`，Kotlin 里不是
  `Array<*>`，`readableValue` 没接住。补 `IntArray` / `LongArray` /
  `ShortArray` 分支转逗号分隔列表（对齐 MQ Explorer 的 `QTIME(2, 106)` 形式）。
- **队列属性窗口塌缩成几行**：INQUIRE_Q 一旦带 `MQIACF_Q_ATTRS`，QMGR 只回
  点名的 attr（省略才是全量应答）——上一条修复把点名参数混进了全量扫描，
  快照只剩 4 个 attr。改为全量 INQUIRE_Q 不带 `MQIACF_Q_ATTRS`，alias 缺的
  `MQIA_DEF_READ_AHEAD` / `MQIA_DEF_PUT_RESPONSE_TYPE` 用单独一条点名查询
  按队列名合并进快照；真机回归加守卫：本地队列快照必须仍含 `MQIA_MAX_Q_DEPTH`。
- **alias 队列「扩展」分组对不齐 MQ Explorer**：INQUIRE_Q 显式带
  `MQIACF_Q_ATTRS` 请求 `MQIA_DEF_READ_AHEAD` / `MQIA_DEF_PUT_RESPONSE_TYPE`
  （QMGR 对 alias 的默认应答不含），补两行 i18n；值解析加 `<常量>.title`
  兜底键（修 `属性控制` 显示原始 0——`queue_value_compatibility` 键缺失）；
  字段表加 `notForQTypes`（alias / remote 不再显示 MQ Explorer 没有的
  Default binding）。真机回归：alias 快照必须含这两个 attr。
- **列表页丢扫描事件**：MqObjectListState 改为先订阅后加载，消除卡 Loading
  的事件窗口。
- **测试连接吞取消**：testConnection 的超时包装改为只转超时 / 普通异常，
  协程取消原样上抛（shutdown 不再被当成「测试失败」）。
- **SSL 全局属性串台**：测试连接改写 `javax.net.ssl.*` 前先快照旧值、finally
  恢复，并以 Mutex 串行化并发测试。
- **连接超时句柄泄漏**：connect 超时后兜底异步 disconnect 迟到的底层连接。
- **拓扑焦点选中复活**：focus 应用一次即清空，窗口 resize 不再复活已取消的
  选中；焦点消费移入副作用上下文。
- **扫描失败状态竞态**：转 Failed 加 `status == Connected` 守卫，不踩并发
  connect() 刚建立的新连接。
- **系统订阅漏网**：QMGR 前缀的内部订阅（`QM1 SYSTEM.BROKER…`）按空白分词
  过滤，`$` 开头的内部命名一并挡掉。
- **组合期开销**：AiPanelState 默认参数不再同步读盘解密配置（调用方必须显式
  传入）；流动动画仅选中时创建；队列列表过滤 / 建卡数据 remember 缓存。
- **secret.key 逐字段读盘**：密钥首读后进程内缓存。
- **IbmMQConfig 默认值收口**：ccsid / 连接超时 / 扫描间隔常量化，构造器与
  fromOptions 同源。

## [Unreleased] — 2026-09-24

### Added

- **mq-ui 通用对象列表组件**（新模块，包 `com.mqclient.mqui.objectlist`）：
  `ObjectListPanel`（工具栏 Slot + 加载/空态）+ `ObjectCardGrid`（LazyVerticalGrid
  自适应列数，卡宽 240–280dp 居中）+ `ObjectCard`（严格 2 行，64dp 定高）+
  `CompactSearchField`（32dp 紧凑搜索框，BasicTextField 自绘，聚焦过渡 + 清除按钮）。
- **卡片操作按钮化**：`CardAction`（id / icon / tooltip i18n key / enabled /
  isDangerous / onClick）由各 MQ 模块构造，`ObjectCardData.actions` 内嵌渲染
  （24dp 按钮 + 16dp 图标 + TooltipArea 悬停提示，禁用降透明、危险红色）。
- **IBM MQ 队列列表**（QueueListPanel）：搜索 / 刷新 / 系统队列显隐切换
  （SYSTEM.* 与 AMQ.* 前缀，默认隐藏），卡片类型图标按 MQQT/MQUS 解析
  （本地 Inbox / 传输 LocalShipping / 远程 CloudUpload / 别名 AltRoute /
  模型 FileCopy，未知 HelpOutline）。
- **PCF 全对象扫描**（ibm-mq-service `pcf/PcfObjectScanner`）：11 类 MQ 对象
  INQUIRE 通配符查询 + 属性/状态响应合并成 `MqObjectSnapshot`，单类 60s 超时、
  2035 无权限跳过，慢扫 60 分钟全量替换、心跳 10 秒 add-or-update 刷新
  队列 curDepth 与通道运行状态。替换原 mock `fetchQueues/fetchTopics/fetchListeners`。
- **队列深度告警阈值**：连接配置新增 `queueDepthWarningThreshold`（默认 85，
  范围 50-100 校验），队列卡片使用率（CURDEPTH/MAXDEPTH）达到阈值时显示红色
  Warning 图标 + "接近满"，否则第 2 行左侧留空。
- **连接阶段超时**：`IbmMQConnectionManager.connect` 与真机验证用
  `connectionTimeout` 配置（默认 30s）罩住 MQQueueManager 连接——掉网不再挂十几分钟。

### Changed

- **队列缓存收敛为单一快照**：删除 `QueueSnapshot` / `KEY_QUEUES` /
  `queue.<name>.messageCount` 等双份缓存，心跳与慢扫统一读写
  `objects.<type>`（`MqObjectSnapshot`，TTL 90 分钟）；心跳 add-or-update 刷
  curDepth，慢扫全量替换（顺带清理 QMGR 上已删除的对象）。
- **ObjectCard 操作改内联按钮**：删除右键菜单与 "..." 按钮
  （`ObjectCardContextMenu` 下线），`ObjectCardGrid` / `ObjectListPanel`
  移除 `contextMenuProvider`；`ContextMenuItem` 保留供侧边栏连接菜单。
- **mq-base 模块**（新增）：`MQServiceAdapter` 统一 `category = "MQ"`，
  六个 MQ service adapter 换基类；`MQConstants` 收纳通用常量。

### Fixed

- `INQUIRE_Q_MGR` 通配符参数回 3097（MQRCCF_Q_MGR_NAME_ERROR）——省略名字参数
  即返回本机队列管理器。
- MQ 连接阶段无短超时：QMGR 掉线时 TCP 握手挂 11 分钟——连接与验证统一套
  connectionTimeout。
- PCF 定长字符串解码尾部残留填充区垃圾字节（主题名出现 `TOPICX` 之类尾渣）——
  解码后同时 trim 空格与 NUL。

### Tests

- `PcfObjectScannerConversionTest`（8 例）：属性提取 / 名称映射 / 快照合并
  离线验证（PCFMessage 可无连接构造），并据此修正了 MQConstants.lookup 不可用、
  legacy 与 headers 两套 PCF 类层次混用两个问题。
- `QueueIconResolverTest`（5 例）：MQQT/MQUS 常量值经 javap 核实后的类型解析回归。
- `PcfObjectScannerLiveTest`：真机端到端验证（读 config.toml 解密链路 → 连接 →
  实扫 QM1 全 11 类对象），QMGR 掉线时自动 SKIP 不污染 CI。

### Notes

- `MQConstants.lookup(value, prefix)` 对 PCF 参数 ID 实测返回 null；值→名映射用
  反射枚举 CMQ* 接口常量实现（`MqAttributeNameMapper`），同值常量跨族碰撞需按
  "前缀_" 过滤。
- legacy `com.ibm.mq.pcf` 与 `com.ibm.mq.headers.pcf` 两套 PCF 类层次不可混用：
  legacy `PCFMessage.getParameters()` 的元素必须强转 legacy `PCFParameter`。

## [Unreleased] — 2026-09-23

### Added

- **mq_connection 连接历史快照表**（app-core，CoreDatabase / SQLDelight）：
  config.toml 是权威数据源，该表只做追加式历史快照，供 operation_history 按
  `connection_id` 关联查询；不存密码等敏感字段。写入走事件
  `core.mq.connection.snapshot.created`（payload 由各 MQ 模块组装、
  `MqConnectionSnapshotHandler` 统一落库），读经 `MqConnectionSnapshotRepository`。
  建表惰性（首次访问时幂等建表），`DatabaseManager` 未初始化时整块缺席并上报错误通道。
- **IBM MQ 二级导航菜单**（SPI 驱动）：`ServiceAdapter.navigationItems()` 提供
  纯数据菜单项（对齐 `ContextMenuItem` 模式），ibm-mq 贡献 13 项 —— 工具类
  （拓扑图 / 命令控制台）+ 分隔线 + 11 个对象类型（队列 / 主题 / 订阅 / 通道 /
  监听器 / 服务 / 进程定义 / 名称列表 / 认证信息 / 通信信息 / 安全策略）。
  点击打开占位 Tab（"功能开发中..."）：已打开即切换；未连接 / 失败时隐式发
  `<serviceId>.connection.connect`；连接中禁用。树形侧栏与窄窗口 Rail 菜单行为一致。
- **模块私有 i18n 表**：`AdapterUI.i18nTables` 声明模块自己的字符串表
  （`composeResources/files/i18n/<base>_<lang>.json`，模块用自己的 `Res` 读取），
  `LanguageResources.load` 与公共表合并进同一张运行时表，回落链
  模块英文 → 模块中文 → 公共英文 → 公共中文 → key。新模块加文案不动宿主与公共文件。
- **证书路径选择接入 FileKit**（filekit-dialogs 0.16.0）：IBM MQ 连接表单的
  信任库 / 密钥库「浏览…」从 JFileChooser 换为 FileKit `openFilePicker`
  （Windows 走 JNA 原生 IFileDialog），headless 守卫与异常兜底保留。

### Changed

- **编辑连接换发新 connection_id**：`ConnectionStore.update` 校验（旧 id 存在、
  serviceId 不变）通过后生成新 UUID 落盘 —— mq_connection 的 connection_id 与快照行
  一一对应，操作历史按 connection_id 关联不会重复放大。事件由差异广播自然得出
  `core.connection.deleted`(旧 id) + `core.connection.added`(新 id)，模块现有
  清理 / 登记路径原样生效；导航快照订阅只挂 `CONNECTION_ADDED`。

### Tests

- `ConnectionStoreTest` 补齐 `update` 用例：全字段覆盖落盘 / 新 id 合法性、
  别的连接不受影响、无变化也换新 id、失败路径零事件不落盘。
- 新增 `ModuleI18nMergeTest`：模块私有表经 SPI 发现 → 合并 → 键解析的端到端回归
  （防"界面上出现 key 本身"的回归）。

### Notes

- Compose 运行时资源按库的包名隔离（`composeResources/<库包名>/...`），跨库按路径
  读资源必失败 —— 模块资源必须由模块自己生成的 `Res` 读取（`ModuleI18nTable` 的
  设计缘由）。
- FileKit 在 JVM 端使用前需 `FileKit.init(appId)`，当前在 ibm-mq-ui 的
  `pickFile` actual 内惰性初始化；其他模块要用 FileKit 时再考虑上移到 main.kt。
