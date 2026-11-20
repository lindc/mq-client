# 项目架构与功能盘点 — MQ Client Desktop v0.2.0

> 数据基线：commit `7ee52bd2`（2026-11-20）。对工作区全量扫描所得：35 个 Gradle 模块、753 个
> Kotlin 文件、119,328 行源码（含测试）。文中的类型名、文件名与包路径均为实际扫描结果；
> 以文件为单位的能力描述（如 `StarRocks*Panel.kt`）按文件名引用，不表示存在同名类型。

---

## 一、项目概况

### 1. 定位

面向消息中间件与 OLAP 的本地桌面客户端，单进程、单窗口，不依赖中心服务：

- **消息中间件**：IBM MQ、Kafka、RabbitMQ、RocketMQ。对象浏览器、消息浏览与发送、属性面板、
  拓扑视图、AI 辅助诊断。
- **OLAP**：StarRocks。数据目录、SQL 控制台、查询 Profile、集群与系统页。

每种协议以一对 `service` / `ui` 模块接入，经 SPI 注册到宿主；协议模块之间无依赖。所有连接
配置、命令审计、查询历史与会话保存在本机 `~/.mqclient/` 目录，敏感字段加密落盘。

### 2. 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 语言 | Kotlin 2.4.10（KMP 结构，`commonMain` / `jvmMain` 分层） | 522 个 `@Composable` 声明、527 处 `suspend fun` 声明（含 `expect` / `actual` 对） |
| UI | Compose Multiplatform 1.12.0（Desktop）+ 自绘窗口框架（`nucleus.decorated-window-tao`） | 自绘标题栏、Tao 渲染管线 |
| 持久化 | SQLDelight（6 张表：`mq_connection` / `mq_command` / `ai_connection` / `chat_history` / `olap_query_history` / `olap_draft`） | 事件驱动落库 + 单槽串行 DB |
| 网络 | Ktor 3.2.3（RabbitMQ 管理 API 走 Java 引擎；AI 适配器默认 CIO）+ JDK `HttpURLConnection`（FE 日志）、各中间件官方客户端 | 自实现 `SseParser`（AI 流式解析） |
| OLAP 驱动 | MySQL JDBC（FE query port 9030）+ JSch 2.28.8（SSH 隧道） | 主机密钥按 TOFU 校验 |
| AI | Provider-adapter 模式对接 OpenAI-compatible / Anthropic（SSE streaming） | SPI 注册表 `AiAdapterRegistry` |
| 构建 | Gradle 9.7.1 + included build（`gradle/plugins`）：版本目录 + 约定插件 | 35 模块单仓 |
| 运行时 | JDK 25，jpackage 精简模块清单打包，免安装 zip | 显式模块清单（`jdk.charsets` / `jdk.crypto.ec` 等） |
| 安全 | `@Sensitive` 注解驱动的本地加密（`EncryptionService`） | 命中关键词的字段自动加密 |

### 3. 模块依赖关系

```text
app-ui (宿主, Main.kt, Compose Desktop 入口)
 ├── app-core        —— 平台底座：EventBus / ServiceContext / SQLDelight / Scheduler / Encryption / Log
 ├── ai-ui           —— AI 右侧诊断面板（Chat UI、系统提示词、不可信上下文包裹）
 ├── ui-common       —— 通用 Compose 组件库（对话框 / 表格 / i18n / PropertyTable / DataGrid）
 ├── mq-ui           —— MQ 通用 UI（对象列表面板 / 命令控制台 / 发送组件 / MetricCard）
 ├── topology-ui ←—— graph-core（纯图模型：TopologyData / TopologyNode）
 ├── ai-core         —— AI 契约层（AiProvider / AiAdapter / SseParser / AiHttp）
 │    ├── openai-service + openai-ui        （Provider 模块对）
 │    └── anthropic-service + anthropic-ui  （Provider 模块对）
 ├── message-interpreter-registry ←— message-interpreter-api
 │    └── json / xml / hex / base64 / text / idoc   （解释器模块群）
 ├── ibm-mq-service  ↔ ibm-mq-ui
 ├── kafka-service   ↔ kafka-ui
 ├── rabbitmq-service ↔ rabbitmq-ui
 ├── rocketmq-service ↔ rocketmq-ui
 └── starrocks-service ↔ starrocks-ui
          ↑ MQ 协议对依赖 mq-base（AbstractConnectionManager / AbstractScanService /
            MQServiceAdapter 模板）；OLAP 协议对依赖 olap-base（当前接入的是
            AbstractAnalyticsConnectionManager 模板，AbstractCatalogScanService /
            AbstractQueryExecutor 已在 olap-base 提供但尚未接入 StarRocks）；
            所有协议对共用 mq-ui / ui-common / app-core
```

依赖方向约束：UI → service → `mq-base` / `olap-base` / `app-core`，单向。反向只通过 EventBus
事件与 SPI 回调。每种协议的 `service` / `ui` 平级成对，删除任一协议对不影响宿主编译。

---

## 二、模块划分与职责（35 个模块）

行数为该模块 `src/` 下 Kotlin 源码（主代码 + 测试）。

### 平台层

| 模块 | 规模 | 职责 | 交互 |
|---|---|---|---|
| **`app-core`** | 74 文件 / 8.6K 行 | 平台底座：`EventBus`（`onEventSuffix` 通配订阅）、`ServiceContext`（SPI 入口 + `moduleScope`）、`DatabaseManager` / `DriverFactory`（SQLDelight 单槽串行）、`JobScheduler`、`CacheManager` / `ScannedCache`、`EncryptionService`、`Log`、`ServiceAdapter` / `AdapterUI` 两个 SPI 接口、`TopologyFocusHolder`、OLAP 审计事件 | 所有模块的依赖根 |
| **`mq-base`** | 8 文件 / 1.3K 行 | MQ 抽象模板：`AbstractConnectionManager`（显式连接状态机）、`AbstractScanService`（慢扫 / 快扫双循环）、`MQServiceAdapter`、`MQConstants` | 四个 mq-service 的父类 |
| **`olap-base`** | 19 文件 / 1.7K 行 | OLAP 抽象模板：`AbstractAnalyticsConnectionManager`、`AbstractCatalogScanService`、`AbstractQueryExecutor`、`AnalyticsConnectionConfig` / `AnalyticsSession` 契约、`SqlDialect`（错误分类与方言）、`OlapErrorCode`、`OlapPerfMarker`、`QueryAuditSink`、`OlapCategory`。当前 StarRocks 接入的是 `AbstractAnalyticsConnectionManager` 与 `SqlDialect` / `OlapErrorCode`，其余模板尚未使用 | OLAP 协议的父类与契约层 |
| **`ui-common`** | 40 文件 / 4.9K 行 | Compose 组件库：`ObjectDetailDialog` / `MessageDetailDialog`、`PropertyTable`、`DataGrid`（列定义 + 排序 + 斑马纹 + 悬浮高亮）、`TableVisuals`（表格视觉标尺）、`PrimaryButton` / `SecondaryButton` / `DangerButton`、`LanguageResources`（i18n 整表加载 + `userMessage()`）、`FilePickerUtil`、`PartialScanBanner` | 所有 UI 模块共享 |
| **`mq-ui`** | 43 文件 / 3.6K 行 | MQ 共享 UI：命令控制台、对象列表与发送组件、`MetricCard`、跨平台文件读取（`PlatformFile.readCommandText`） | 四个 MQ 协议对的共用件 |
| **`app-ui`** | 35 文件 / 7.9K 行 | 宿主：`Main.kt` 启动、模块注册、`SettingsDialog`、连接总览、语言切换时合并各模块私有 i18n 表 | 装配全部 SPI 实现 |
| `ui-test-kit` / `test-support` | 3 文件 / 287 行 | Compose 画布手势测试工具 / LiveTest `assumeLive` SKIP 包装 | 各模块 test |

### MQ 协议对（service ↔ ui 成对）

| 模块 | 规模 | 职责与关键类 |
|---|---|---|
| **`ibm-mq-service`** | 55 文件 / 9.1K 行 | `IbmMQConnectionManager`（MQQueueManager + TLS，密码套件经 `MQConstants.SSL_CIPHER_SUITE_PROPERTY`，UI 侧选项见 `CipherSuiteOptions`）、`PcfObjectScanner`（PCF 全量对象扫描 + `MqAttributeNameMapper` / 枚举翻译表）、MQSC 控制台后端（`MqscParser` / `MqscCommandExpander` / `MqscPcfSender` / `MqrcTranslator`，命令历史落 `mq_command`）、`MessageBrowseSession`（会话级常驻一个 `MQQueue` 浏览句柄以保住服务端光标，「加载更多」走 `BROWSE_NEXT`；删除 / 重入队等一次性操作另开 `accessQueue` 句柄并在 `finally` 关闭）、`DmpmqmsgFormat`（dmpmqmsg 搬移文件导入） |
| **`ibm-mq-ui`** | 74 文件 / 13.3K 行 | 队列 / 通道 / 监听器列表面板、`MessageBrowseTab` / `MessageSendTab` / `MessageDetailDialog`、`MqObjectPropertiesDialog`（分组属性表 + 属性提取管线）、`TopologyPanel` + `ClusterRelationsPanel`（集群边关系） |
| **`kafka-service`** | 31 文件 / 5.2K 行 | `KafkaConnectionManager`（AdminClient + 共享 Producer）、`KafkaScanService`（topic / consumer-group lag）、`KafkaMessageService`（`assign + seek + poll` 直读模型，Consumer 每次操作独立 + `finally` 关闭） |
| **`kafka-ui`** | 40 文件 / 8.1K 行 | Topic 面板、Consumer Group lag 详情、消息浏览（多起点：earliest / latest / offset 回退 N 条）、`KafkaTopologyPanel` |
| **`rabbitmq-service`** | 29 文件 / 6.7K 行 | `RabbitMqConnectionManager`（半开连接自愈 `invalidateAmqpConnection`）、`RabbitMqAdminClient`（Ktor + Java 引擎，扫描池与详情池分离，坏 socket 重试）、`RabbitMqMessageSession`（会话级 channel + `withOperationChannel` 一次性 channel 发布）、`RabbitMqScanService`（对象慢扫 / 状态快扫 / 缓存投影 `cachedQueueDetail`） |
| **`rabbitmq-ui`** | 37 文件 / 8.4K 行 | 队列 / 交换机面板、拓扑（Exchange → Queue）、消息对话框（浏览 / 发送 / 批量文件）、Stream 队列前置拦截、队列属性缓存优先渲染 |
| **`rocketmq-service`** | 33 文件 / 5.4K 行 | `RocketMqConnectionManager`（`DefaultMQAdminExt` + `examineBrokerClusterInfo` 校验）、`RocketMqAdminClient`、`RocketMqScanService`（consumer progress 分级容错：CODE 17/206 记为预期态）、`RocketMqMessageService`（队列数自适应并行拉取：≥64 队列 → 8 个临时 PullConsumer 分片 + 动态超时） |
| **`rocketmq-ui`** | 40 文件 / 7.6K 行 | 概览 / Broker / Topic / Group 面板、消息浏览（多 Queue 并行）、`RocketMqTopologyPanel` |

### OLAP 协议对

| 模块 | 规模 | 职责与关键类 |
|---|---|---|
| **`starrocks-service`** | 46 文件 / 6.5K 行 | `StarRocksConnectionManager`（共享会话 + `SELECT 1` 探针 + 半开检测）、`MysqlJdbcConnector`（JDBC → `JdbcConnector` 平台中立接口，`SQLTimeoutException` 归类为连接 / 查询超时）、`catalog` 包（`StarRocksCatalogSql` / `StarRocksCatalogParsers` / `StarRocksCatalogModels`）、`cluster` 包（`StarRocksClusterService` + 解析器：FE / BE / CN / Catalog / Routine Load 快照与逐块降级）、`system` 包（变量、FE 配置、FE 日志解析与 `HttpURLConnection` 取数）、`ssh` 包（`SshTunnel` + `KnownHostsStore`，JSch `HostKeyRepository` 实现 + `主机 密钥类型 SHA-256 指纹` 格式的 `known_hosts`）、`jdbc/StarRocksErrors`（错误分类 + i18n `errorKey` + 英文兜底）、`log/StarRocksLog`（`[Conn]` / `[SQL]` / `[Meta]` / `[Profile]` 标签与截断）、`ai/StarRocksAiChainBuilder`（`EXPLAIN` + 字段结构 → 纯数据上下文） |
| **`starrocks-ui`** | 52 文件 / 9.9K 行 | `StarRocksCatalogPanel` + `catalog` 相关文件（左树 + 右侧详情双 Tab：字段二维表 / DDL）、`StarRocksSqlConsolePanel` + `StarRocksSqlConsoleState` + `StarRocksSqlConsoleResult.kt` / `StarRocksSqlConsoleHistory.kt` / `StarRocksCsvExport.kt`（标签栏共用编辑器与结果区，流式结果 + 排序 + 长值详情 + CSV 导出 + 历史落库）、`StarRocksProcessListPanel` + `StarRocksProfileSupport.kt` / `StarRocksProfileDialogs.kt`（`SHOW PROFILELIST` + Profile 取数）、`StarRocksOverviewPanel` / `StarRocksBackendsPanel` / `StarRocksHaPanel` / `StarRocksVariablesPanel` / `StarRocksLogsPanel`（对齐 FE Web 控制台四个系统页）、`StarRocksTopologyPanel` + `StarRocksTopologyGraphBuilder` + `StarRocksNodeDetailDialog`（三层拓扑 + 节点属性对话框）、`StarRocksConnectionForm` + `StarRocksConnectionFormState`（含 SSH 隧道与主机指纹确认卡）、`CodeBlock`（`StarRocksCodeBlock.kt`，DDL 与 Profile 共用的代码块）、`StarRocksAiDiagnosisDialog` |

### AI 栈与解释器

| 模块 | 规模 | 职责与关键类 |
|---|---|---|
| **`ai-core`** | 13 文件 / 736 行 | 协议无关契约：`AiProvider`、`AiAdapter`、`AiAdapterRegistry`（SPI 聚合）、`AiChatMessage` / `AiStreamChunk`、`adapter/AiHttp` + `SseParser`（SSE 增量解析） |
| **`ai-service`** | 11 文件 / 1.2K 行 | 落库：`ChatHistoryRepository`、`AiConnectionRepository`、`AiConfigMigration`（配置版本迁移）、事件驱动 `*Handler` |
| **`ai-ui`** | 19 文件 / 2.6K 行 | 右侧诊断面板：`AiPanel` / `AiPanelState`、`ChatService` / `AdapterChatService`（流式 + `StreamingReplyBuffer`）、`AiSystemPrompts`（不可信上下文包裹）、`MockChatService`（离线演示） |
| `openai-service`+`openai-ui` / `anthropic-service`+`anthropic-ui` | 各 8 文件 / 644 与 726 行 | 每个 Provider 一对模块：`AiServiceAdapter` / `AnthropicServiceAdapter`，UI 侧仅注册 branding 与表单 |
| **`message-interpreter-api`** + 6 个实现（`-json` / `-xml` / `-hex` / `-base64` / `-text` / `-idoc`）+ **`message-interpreter-registry`** | 20 文件 / 2.4K 行 | 消息体解释器 SPI：`MessageInterpreter` 契约 + `MessageBodyDisplay`；实现：JSON、XML、Hex dump、Base64、纯文本、SAP IDoc；`MessageInterpreters` 聚合注册 |

### 拓扑引擎

| 模块 | 规模 | 职责与关键类 |
|---|---|---|
| **`graph-core`** | 2 文件 / 162 行 | 纯图模型：`TopologyNodeType` / `NodeSeverity` / `TopologyNode` / `TopologyData`（统一节点词汇、不可变数据结构） |
| **`topology-ui`** | 13 文件 / 2.3K 行 | 通用渲染：`TopologyCanvas`（Canvas 手绘）、`TopologyLayoutProvider`、`TopologyNodeCard` / `TopologyNodeDetailCard`、拖拽与缩放（kuiver `KuiverViewerState`）、`TopologyImageExporter`（PNG 导出）。节点类型自 0.2.0 起由各业务模块提供（`topology-ui` 不再持有中间件语义） |

---

## 三、功能清单（按业务域）

### 1. 平台宿主与通用底座

- **连接管理**：显式状态机（`AbstractConnectionManager`：Disconnected → Connecting → Connected，
  验证式建连），连接快照落 `mq_connection`。
- **事件总线**：`EventBus` + 命名约定 `<serviceId>.connection.*`（`EventNames`），`onEventSuffix`
  后缀通配订阅，模块协程作用域 `moduleScope(parent, moduleId)` 随模块卸载取消。
- **双速扫描调度**：慢扫（全量对象拓扑，受限并发）+ 快扫（状态 / 计数保活），
  `AbstractScanService` 模板 + `JobScheduler` 定时 + `ScannedCache` 内存快照。
- **持久化**：SQLDelight 6 表，事件驱动落库（`*SnapshotHandler` 订阅 EventBus），敏感字段经
  `@Sensitive` + `EncryptionService` 加密。
- **i18n**：整表加载 `LanguageResources`，模块私有表经 `AdapterUI.i18nTables` 合并（重名检测
  测试 `ModuleI18nMergeTest`），中英双语；`userMessage()` 在渲染期按 `errorKey` 翻译。
- **性能打点**：统一 `[MQ-Perf]` 前缀，UI 侧记录触发 / Loading / 数据返回 / 退出，Service 侧
  按段记录开始处理、客户端实例获取、`ioMs`、`parseMs` 与总耗时 `totalMs`。
- **文件选择**：FileKit 封装（`FilePickerUtil` / `PathRow` / `PathNormalize`），消息批量发送
  受 `MAX_MESSAGE_FILE_MB` 限制。
- **表格组件**：`ui-common` 的 `PropertyTable`（键值属性）与 `DataGrid`（列定义 + 排序 +
  斑马纹 + 悬浮高亮），视觉标尺收敛在 `TableVisuals`；MQ 属性面板与 StarRocks 字段表、变量表、
  结果集共用同一套实现。

### 2. IBM MQ

- 对象：Queue / Channel / Listener 全量 PCF 扫描 + 属性对话框（`MqAttributeNameMapper` +
  枚举表 `MqQMgrAttrEnums`，valueConstMap 兜底）。
- 消息：浏览（独立 `MQQueue` 句柄 + `BrowseCursor` 身份化游标等价翻页）、发送（`MessageSender`）、
  删除 / 重入队（`MessageAccess`）、dmpmqmsg 搬移文件导入（`DmpmqmsgFormat`）。
- MQSC 控制台：多命令解析（`MqscParser`）、`MqscCommandExpander`（escape 双参数格式）、经
  PCF 发送（`MqscPcfSender`）、`MqrcTranslator` 错误码翻译、命令历史落库。
- 集群拓扑：`ClusterRelationsPanel`（`DISPLAY CLUSQMGR` 取成员关系，按 CLUSSDR / CLUSRCVR 通道对展示）、按消息流向的固定列布局。
- TLS：`IbmMqTls`（truststore p12 / pfx / jks + `CipherSuiteOptions`）。
- 连接表单：保存前试连；失败时提供 `SaveAnywayDialog`。

### 3. Kafka

- Topic 管理（config 修改）、Consumer Group lag 详情（`KafkaGroupDetailDialog`）、分区拓扑。
- 消息直读：`assign + seek + poll`，不 join group、不提交位移；多起点（Earliest / Latest /
  Offset / 最新回退 N 条）、分区级游标续读（`nextCursor`）。
- 句柄隔离：Consumer 每次读取独立实例 + `finally close(5s)`；AdminClient / Producer 连接级共享。
- TLS：SASL_SSL + 自签 CA truststore。

### 4. RabbitMQ

- 管理 API（Ktor + Java 引擎，Basic Auth）：exchanges / queues / bindings / nodes 读取与交换机创建
  删除（默认与 `amq.*` 受保护）、绑定 e → q。
- 消息：`basicGet` 浏览（消息保持未确认、关闭自动重新入队、计数展示）、`basicPublish`
  （default-exchange 发送前 `queueDeclarePassive` 存在性检查，避免 unroutable 静默丢弃）、
  测试发布（routed 标志回读）、消息转发。
- 连接韧性：Stream 队列前置拦截（管理 API 查 `x-queue-type`，不发起 AMQP 请求）、半开连接
  自愈（`invalidateAmqpConnection` + `if (!isOpen)` 重建）、540 NOT_IMPLEMENTED 转为可读文案。
- 队列属性缓存优先渲染（慢扫 + 快扫投影 + 后台刷新）+ 双 HttpClient 连接池（扫描 / 详情隔离）。

### 5. RocketMQ

- 集群连接（NameServer 多地址、ACL accessKey / secretKey、TLS + 自签 CA）、Broker / Topic /
  Group 面板、consumer progress 分级容错（CODE 17/206 → 单行 INFO）。
- 消息：多队列并行拉取（8 个临时 PullConsumer 分片 + 动态超时 `15s + 队列数 × 50ms ÷ 并发`）、
  组内 offset 进度（`examineConsumeStats`）。
- Remoting 调用可打断（`runInterruptible` + 取消语义经 `ensureActive` 恢复）。

### 6. StarRocks

- **连接**：`StarRocksConnectionConfig` 具名类型字段（host / port / user / password / database /
  jdbcUrlOverride / SSH 六字段），`options` 由字段派生并与 app-core `ConnectionInfo.options`
  同形，`password` 命中敏感关键字自动加密。
- **SSH 隧道**：`SshTunnel` 实现 `ConnectionTunnel`，支持私钥（含口令）与密码两种认证；
  主机密钥 TOFU —— 未知主机抛 `SshUnknownHostException`（附 SHA256 指纹）由 UI 确认卡处理，
  已知且一致则放行，已记录但不一致抛 `SshHostKeyChangedException` 并拒绝连接。指纹来源为 JSch
  `HostKeyRepository.check()` 的对端公钥；文件记录 `主机 密钥类型 SHA-256 指纹` 三列，主机标识
  归一化为 `[host]:port` 形式。`testConnection` 与 `openSession` 走同一条隧道路径。
- **数据目录**：左树为库 → 表 / 视图 / 物化视图（不展开字段与分区），选中表后右侧加载字段二维表
  （列名 / 类型 / 可空 / 键 / 默认值 / Extra / 注释）与 DDL 双 Tab；分区在概览底部折叠小节中
  给出摘要。物化视图识别使用 `SHOW MATERIALIZED VIEWS`（3.4 实测 `table_type=VIEW` 不可靠）。
- **SQL 控制台**：标签栏共用一份编辑器与结果区（各标签不维护独立文档；草稿按连接持久化
  `olap_draft`，一个连接一份）、执行选区或全文、按分号切分多语句顺序执行（单条失败即停，
  错误进结果区且上一条结果一并清空）、随时中断、流式渲染（上限 `MAX_ROWS = 50_000`，超出
  横幅提示）、列排序、长值悬停提示与点击详情、复制、CSV 导出（UTF-8 + BOM，临时文件 +
  原子替换）、执行历史（`olap_query_history`，最近 50 条）。
- **会话与慢查询**：`SHOW PROFILELIST` 列表；Profile 取数为
  `ANALYZE PROFILE FROM '<id>'`（3.1+，单语句），语句报错或返回空文本时抛
  `KEY_PROFILE_NOT_COLLECTED` 业务异常，UI 显示「Profile 未采集」指引。Profile 文本经 ANSI
  清洗后以等宽代码块展示，与 DDL 共用 `CodeBlock`（`StarRocksCodeBlock.kt`）主题。
- **集群页**：概览（规模 / 资源 / 导入任务三类指标 + 节点表）、三层拓扑（外部 Catalog → FE →
  BE/CN）、BE/CN 容量与心跳、高可用（主节点、BDB JE 多数派、可选举 / 观察者节点、Replayed
  Journal）、变量（会话变量 / 全局变量 / FE 配置三 Tab，前端过滤）、FE 日志（`fe.log` /
  `fe.warn.log` / `fe.audit.log`，经 FE Web 端口 Basic Auth 读取，10 秒自动刷新）。
  取数失败逐块降级：普通用户看不到 BACKENDS 时只画 FE，不报错。
- **节点属性对话框**：复用 `ui-common` 的 `ObjectDetailDialog` 外壳，先以拓扑快照渲染（无额外
  请求）、后台刷新该节点；刷新失败标记 `stale` 而不弹错误框。
- **AI 慢查询诊断**：`StarRocksAiChainBuilder` 执行 `EXPLAIN <sql>` 并解析 FROM / JOIN 涉及表
  的字段结构，产出纯数据上下文，由发送侧统一包裹不可信边界；任一步失败只减少上下文，不阻断
  诊断。
- **日志**：`StarRocksLog` 提供 `[StarRocks][Conn]` / `[SQL]` / `[Meta]` / `[Profile]` 四个
  标签，SQL 与原始返回超 500 字符截断，凭据不入日志；连接、SQL 执行、元数据解析、Profile
  降级链路均有打点。

### 7. AI 链路诊断引擎

- Provider SPI：`AiAdapterRegistry` 聚合，OpenAI-compatible / Anthropic 平级模块对。
- 自实现 HTTP + SSE 增量解析（`AiHttp` / `SseParser`），流式回复缓冲
  （`StreamingReplyBuffer`）+ 事件驱动落库（`chat_history` 全量审计）。
- 上下文组装：四个 MQ 模块各有 `*AiChainBuilder` 产出链路 JSON（节点 + `relationToNext`），
  经 `buildChainContextJson` → `wrapUntrustedContext` 统一进入面板；StarRocks 走
  `StarRocksAiChainBuilder`（SQL + EXPLAIN + 表结构）。
- 注入防御：`[BEGIN/END UNTRUSTED CONTEXT]` 边界标记 + 系统提示词安全规则 + 数据内伪造边界
  全角中和（`NEUTRALIZED_BEGIN`）。
- 配置迁移 `AiConfigMigration`、连接快照复用 `mq_connection` 形态（`ai_connection`）、离线
  `MockChatService`。

### 8. 拓扑图

- `graph-core` 纯模型（`TopologyData` / `TopologyNode` / `NodeSeverity` / `TopologyNodeType`）
  + `topology-ui` Canvas 渲染：节点卡片、节点详情卡、拖拽与缩放、节点双击。
- PNG 导出（`TopologyImageExporter`，几何测试覆盖）。
- 协议接入：`ibm-mq-ui`（集群边关系）、`kafka` / `rabbitmq` / `rocketmq` / `starrocks` 各自
  Panel；节点类型由各业务模块定义（`OlapNodeType` 等），焦点选中经
  `app-core.TopologyFocusHolder` 支持「在拓扑图中查看」直达。

### 9. 消息解释器

- 契约：`MessageInterpreter`（`MessageBodyDisplay` / `MessagePreviewText` / `MessageCharsets`
  编码检测）。
- 内置 6 个：JSON（格式化）、XML、Hex dump、Base64、Text、SAP IDoc（段结构解析）。
- `message-interpreter-registry.MessageInterpreters` 聚合；新增解释器 = 一个模块 + SPI 注册，
  UI 层无需改动。

---

## 四、关键设计

### 1. 三层 SPI 插件化

- **服务侧**：`ServiceAdapter`（app-core）→ `MQServiceAdapter`（mq-base 模板）→ 各 MQ 协议
  `*ServiceAdapter`；StarRocks 的连接层复用 olap-base 的
  `AbstractAnalyticsConnectionManager`，但其 `ServiceAdapter` 直接实现 app-core 的接口
  （olap-base 目前没有服务侧 SPI）；宿主 `app-ui` 声明各协议的 `*Service` 与 `*Ui` 模块
  （`ui` 侧带 `META-INF/services` 注册文件，两者都需在宿主 classpath 上），运行时
  ServiceLoader 发现注册。
- **UI 侧**：`AdapterUI` 平行 SPI（i18n 私有表合并、连接表单 `ConnectionFormUi`、属性对话框
  扩展点 `ConnectionPropertiesUi`）。
- **AI / 解释器侧**：`AiAdapter` Provider 模块对；`MessageInterpreter` 六个实现。
- 新增一种协议 = 一对 service / ui 模块继承相应模板 + SPI 注册，平台层无改动。

### 2. 并发与状态管理

- **句柄生命周期**：重量级句柄（AdminClient / MQQueueManager / Producer / JDBC 连接）连接级
  共享 + 显式锁串行；轻量句柄（Consumer / Channel / JDBC Statement）每操作独立 + `finally`
  释放。`MQQueue` 按用途分开：浏览会话常驻一个句柄以保住服务端光标（会话关闭时释放），
  删除 / 重入队 / 发送则每次新开并在 `finally` 关闭。RabbitMQ 另有一次性
  `withOperationChannel`、半开连接自愈、Stream 队列前置拦截三层处理。
- **可打断性**：阻塞 IO 经 `runInterruptible`，协程取消能调用 `Thread.interrupt()` 断开阻塞
  socket；`ensureActive` 处理被框架包装的取消异常（如 RocketMQ 将 `InterruptedException` 包成
  `MQClientException`）。
- **事件驱动落库 + 单槽串行 DB**：SQLDelight 写入经 EventBus handler，避免 UI 线程阻塞与
  并发写冲突。
- **队列规模自适应**：RocketMQ 全 Queue 拉取按队列数从单实例顺序切换为 8 分片并行（临时
  Consumer + `AtomicLong` / `ConcurrentHashMap` / `synchronizedList` 聚合），动态超时兜底。
- **OLAP 单槽会话**：StarRocks 一个连接同一时刻只跑一条语句（UI 侧以 `exportingRows` /
  `phase` 互斥），多条语句在同一会话上按顺序执行。

### 3. AI 上下文构建与注入防御

- 不可信数据在最外层单层包裹（`[BEGIN UNTRUSTED CONTEXT]`），而非字段级标记：保留值语义、
  控制 token 体积。
- 伪造边界全角中和（`［BEGIN…］`）：保留可读性但不再构成标记。
- 安全规则与提示词全部进 i18n 表（`ai_untrusted_context_rule`），缺键走英文兜底链。
- 四个 MQ 模块的 `*AiChainBuilder` 与 StarRocks 的 `StarRocksAiChainBuilder` 均只产出纯数据，
  包裹由单一发送侧完成，新 Provider 无需重复实现。

### 4. 可观测性

- `[MQ-Perf]` 结构化耗时日志（UI 四点 + Service 五段：instance / io / parse / totalMs），
  用于按字段定位瓶颈段（instanceMs 偏大 → 重复新建；ioMs 偏大 → 网络；done 早于 UI 退出 →
  帧调度）。
- 预期态与真异常分级：RocketMQ CODE 17/206 记为单行 INFO，真异常保留堆栈。
- StarRocks 独立 `[StarRocks][Conn|SQL|Meta|Profile]` 标签，Profile 取数链路记录
  `SHOW PROFILELIST` 原始内容摘要与失败原因。

### 5. OLAP 错误与降级

- `SqlDialect` + `OlapErrorCode`：JDBC 错误 → 平台中立分类（连接 / 认证 / 权限 / 对象不存在 /
  语法 / 超时等），带 i18n `errorKey` 与英文兜底 `fallbackMessage`。
- **SQL 错误不断连**：普通 SQL 报错保留会话，只有半开 / 关闭的连接才触发重建
  （`isSessionBroken`）。
- **逐块降级**：集群快照按 FE / BE / CN / Catalog / Routine Load 分块取数，单块失败只减少
  展示内容；全部失败才进入错误态。

---

## 五、代码分布与量化指标

### 1. 总量

| 指标 | 数值 |
|---|---|
| Gradle 模块 | **35 个**（+ included build `gradle/plugins`） |
| Kotlin 文件 | **753**（主代码 532，测试 221） |
| Kotlin 行数 | **119,328**（主代码 90,104，测试 29,224） |
| `@Composable` 声明 | **522** |
| `suspend fun` 声明 | **527**（含 `expect` / `actual` 对） |
| SQLDelight 表 | 6（`mq_connection` / `mq_command` / `ai_connection` / `chat_history` / `olap_query_history` / `olap_draft`） |

### 2. 模块行数占比（占 119,328 行）

```text
IBM MQ 协议对     18.8%  ██████████████████▊      (ibm-mq-ui 11.2% + ibm-mq-service 7.6%)
StarRocks 协议对  13.8%  █████████████▊            (starrocks-ui 8.3% + starrocks-service 5.4%)
app-core + app-ui 13.8%  █████████████▊            (app-core 7.2% + app-ui 6.6%)
RabbitMQ 协议对   12.6%  ████████████▌            (rabbitmq-ui 7.0% + rabbitmq-service 5.6%)
Kafka 协议对      11.1%  ██████████▉              (kafka-ui 6.8% + kafka-service 4.3%)
RocketMQ 协议对   11.0%  ██████████▊              (rocketmq-ui 6.4% + rocketmq-service 4.6%)
AI 栈              5.0%  █████                    (ai-ui / ai-service / ai-core / providers)
ui-common          4.1%  ████
mq-ui              3.0%  ███
拓扑引擎           2.1%  ██
消息解释器群       2.0%  ██
olap-base          1.4%  █▍
mq-base            1.1%  █▏
其余(test-kit 等)  0.2%
```

结构观察：四个 MQ 协议对合计 **53.5%**，StarRocks 协议对 **13.8%**。抽象层的规模与其覆盖面
的比例可见于 `mq-base`（1.3K 行承载四个协议的连接 / 扫描骨架）与 `olap-base`（1.7K 行承载
OLAP 连接 / 目录 / 查询模板）。

---

*数据来源：`settings.gradle.kts` include 清单；`find` + `wc -l` 全量扫描 `src/**/*.kt`（排除
`build/`）；`grep` 统计 `@Composable` 与 `suspend fun` 声明；`*.sq` 文件统计 SQLDelight 表。*
