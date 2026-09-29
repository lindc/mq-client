# Changelog

本文件记录 MQClient 的显著变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased] — 2026-09-29

### Added

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
- **旧通用拓扑管线整体下线**：删除 topology-ui、graph-core 模块与
  TopologyProvider SPI / IbmMQTopologyProvider / IbmMqTopologyStyle（附带删除
  一个在部分环境会无限挂死的 provider 单测）；拓扑渲染由 MQ 专属面板承担，
  TopologyFocusHolder 焦点机制保留。
- **拓扑入口走真正的 Tab**：MainWindow 焦点处理器 `onSelect` + `openTab`
  双调，侧栏选中 / Tab 条 / 内容区同步切换。
- **拓扑数据缓存优先**：进入面板只读慢扫缓存出图，缓存为空才隐式连接 / 扫描
  （CONNECTED 事件后补扫）；强制刷新走右上角「重新扫描」。

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
