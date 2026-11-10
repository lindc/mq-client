# 项目架构与功能盘点报告 — MQ-Client v0.1.0

> 本报告基于对工程的全量扫描（33 个 Gradle 模块、~619 个 Kotlin 文件、~99.5K 行源码含测试），所有类名/包路径均为实际扫描所得。数据基线：commit `caa768f4`。

---

## 一、项目整体概况

### 1. 项目核心定位

**一款本地优先（Local-First）、多协议统一的桌面型消息中间件运维工作台**：以插件化架构同时深度支持 IBM MQ / Kafka / RabbitMQ / RocketMQ 四大协议，内置 MQ 对象拓扑可视化、全链路性能打点（`[MQ-Perf]`）与 **AI 链路诊断引擎**（Prompt-Injection 加固），所有数据与密钥本地加密落盘，不依赖任何中心服务。

### 2. 核心技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 语言 | **Kotlin 2.x**（KMP 结构，jvmMain/commonMain 分层） | 512 个 `@Composable`、276 个 `suspend fun` |
| UI | **Compose Multiplatform (Desktop)** + 自研 nucleus 窗口框架（`nucleus.decorated-window-tao`） | 自绘标题栏、Tao 渲染管线（非 AWT EDT） |
| 持久化 | **SQLDelight**（4 张表：`mq_connection` / `mq_command` / `ai_connection` / `chat_history`） | 事件驱动落库 + 单槽串行 DB |
| 网络 | **Ktor CIO**（RabbitMQ 管理 API）、各 MQ 官方客户端（JMS/MQI、kafka-clients、amqp-client、rocketmq-client 5.3.1） | 自实现 `SseParser`（AI 流式） |
| AI | Provider-adapter 模式对接 OpenAI-compatible / Anthropic（SSE streaming） | SPI 注册表 `AiAdapterRegistry` |
| 构建 | **Gradle 9.7.1 + included build（`gradle/plugins`）**：版本目录 + 约定插件（`mq.kotlin-jvm` / `mq.compose`） | 33 模块单仓 |
| 运行时 | **JDK 25**（Temurin 25.0.5），jpackage 精简模块清单打包（174M→132M），免安装 zip 分发 | `jdk.charsets` / `jdk.crypto.ec` 等显式清单 |
| 安全 | `@Sensitive` 注解驱动的密钥加密（`EncryptionService`） | secretKey 命中关键词自动加密 |

### 3. 整体依赖关系

```text
app-ui (宿主, Main.kt, Compose Desktop 入口)
 ├── app-core        —— 平台底座：EventBus / ServiceContext / SQLDelight / Scheduler / EncryptionService / Log
 ├── ai-ui           —— AI 右侧诊断面板（Chat UI、系统提示词、不可信上下文包裹）
 ├── ui-common       —— 通用 Compose 组件库（对话框/表格/i18n/PathRow/PropertyTable）
 ├── mq-ui           —— MQ 通用 UI（对象列表面板 / 命令控制台 / 发送组件 / MetricCard）
 ├── topology-ui ←—— graph-core（纯图模型：TopologyGraph）
 ├── ai-core         —— AI 契约层（AiProvider / AiAdapter / SseParser / AiHttp）
 │    ├── openai-service + openai-ui        （Provider 插件对）
 │    └── anthropic-service + anthropic-ui  （Provider 插件对）
 ├── message-interpreter-registry ←— message-interpreter-api
 │    └── json / xml / hex / base64 / text / idoc   （解释器插件群）
 ├── ibm-mq-service ↔ ibm-mq-ui
 ├── kafka-service ↔ kafka-ui
 ├── rabbitmq-service ↔ rabbitmq-ui
 └── rocketmq-service ↔ rocketmq-ui
          ↑ 每对 MQ 模块都依赖 mq-base（AbstractConnectionManager / AbstractScanService /
            MQServiceAdapter 模板）与 mq-ui / ui-common
```

依赖方向铁律：**UI → Service → mq-base/app-core，单向；反向只通过 EventBus 事件与 SPI 回调**。每个 MQ 的 service/ui 平级成对，删除任一协议不影响宿主编译。

---

## 二、模块划分与职责（33 个模块）

### 平台层

| 模块 | 职责 | 对外暴露 | 交互 |
|---|---|---|---|
| **`app-core`**（68 文件 / 8.2K 行） | 平台底座：`EventBus`（`onEventSuffix` 通配订阅）、`ServiceContext`（SPI 入口 + `moduleScope` 协程作用域）、`DatabaseManager`/`DriverFactory`（SQLDelight 单槽串行）、`JobScheduler`、`CacheManager`/`ScannedCache`、`EncryptionService`、`Log`、`ServiceAdapter`/`AdapterUI` 两个 SPI 接口、`TopologyFocusHolder` | SPI 契约 + 基础设施单例 | 所有模块的依赖根 |
| **`mq-base`**（8 文件 / 1.3K 行） | MQ 抽象模板：`AbstractConnectionManager`（显式连接状态机）、`AbstractScanService`（慢扫/快扫双循环）、`MQServiceAdapter`、`MQConstants` | 模板方法基类，消除四协议 80% 样板 | 四个 mq-service 的父类 |
| **`ui-common`**（36 文件 / 4.3K 行） | Compose 组件库：`DecoratedDialog` 系（`ObjectDetailDialog`/`MessageDetailDialog`）、`PropertyTable`、`PressableButton`（danger 变体）、`LanguageResources`（i18n 整表加载 + `userMessage()` 按错误键翻译）、`FilePickerUtil`（FileKit 封装）、`PartialScanBanner`、消息详情管线 | 跨协议 UI 词汇表 | 所有 UI 模块共享 |
| **`app-ui`**（34 文件 / 7.8K 行） | 宿主：`Main.kt` 启动、模块注册、`SettingsDialog`、连接总览、语言切换时合并各解释器 i18n 私有表 | — | 装配全部 SPI 实现 |
| `ui-test-kit` / `test-support` | Compose 画布手势测试工具 / LiveTest `assumeLive` SKIP 包装 | — | 各模块 test |

### 四大 MQ 协议对（service ↔ ui 成对）

| 模块 | 行数 | 职责与关键类 |
|---|---|---|
| **`ibm-mq-service`** | 9.1K | `IbmMQConnectionManager`（MQQueueManager + TLS/SLLCipherSuite）、`PcfObjectScanner`（PCF 全量对象扫描 + `MqAttributeNameMapper`/枚举翻译表）、**MQSC 控制台后端**（`MqscParser`/`MqscCommandExpander`/`MqscPcfSender`/`MqrcTranslator`，命令历史落库 `mq_command`）、`MessageBrowseSession`（每次 `accessQueue` 独立句柄 + finally close）、`DmpmqmsgFormat`（dmpmqmsg 搬移文件导入） |
| **`ibm-mq-ui`**（最大 UI 模块） | 13.2K | 队列/通道/监听器列表面板、`MessageBrowseTab`/`MessageSendTab`/`MessageDetailDialog`、**MQ 对象属性对话框**（`MqObjectPropertiesDialog` + 分组表 + MQ Explorer 级 attrs 提取管线）、`TopologyPanel` + `ClusterRelationsPanel`（集群边关系） |
| **`kafka-service`** | 5.2K | `KafkaConnectionManager`（AdminClient + 共享 Producer）、`KafkaScanService`（topic/consumer-group lag）、`KafkaMessageService`（`assign+seek+poll` 直连实时查询模型，Consumer 每次操作独立 + finally close） |
| **`kafka-ui`** | 8.0K | Topic 面板、ConsumerGroup lag 详情、消息浏览（多起点：earliest/latest/offset 回退 N 条）、`KafkaTopologyPanel` |
| **`rabbitmq-service`** | 6.5K | `RabbitMqConnectionManager`（自愈半开连接 `invalidateAmqpConnection`）、`RabbitMqAdminClient`（Ktor CIO，**双 HttpClient 连接池**：扫描池 vs 详情池，坏 socket 自动重试）、`RabbitMqMessageSession`（会话级 channel + `withOperationChannel` 一次性 channel 发布）、`RabbitMqScanService`（对象慢扫/状态快扫/缓存投影 `cachedQueueDetail`） |
| **`rabbitmq-ui`** | 8.3K | 队列/交换机面板、拓扑（Exchange→Queue）、消息对话框（浏览/发送/批量文件 `FileListItem` 四态统一）、Stream 队列前置拦截、队列属性**缓存优先秒开** |
| **`rocketmq-service`** | 5.4K | `RocketMqConnectionManager`（`DefaultMQAdminExt` + `examineBrokerClusterInfo` 验证）、`RocketMqAdminClient`、`RocketMqScanService`（含 consumer progress 分级容错：CODE 17/206 预期态单行 INFO）、`RocketMqMessageService`（**队列数自适应并行拉取**：≥64 队列 → 8 个临时 PullConsumer 分片 + 动态超时） |
| **`rocketmq-ui`** | 7.6K | 概览/Broker/Topic/Group 面板、消息浏览（多 Queue 并行）、`RocketMqTopologyPanel` |

### AI 栈与解释器

| 模块 | 行数 | 职责与关键类 |
|---|---|---|
| **`ai-core`** | 736 | 协议无关契约：`AiProvider`、`AiAdapter`、`AiAdapterRegistry`（SPI 聚合）、`AiChatMessage`/`AiStreamChunk`、`adapter/AiHttp` + **`SseParser`**（手写 SSE 增量解析） |
| **`ai-service`** | 1.2K | 落库：`ChatHistoryRepository`（chat_history 单表承载全部审计）、`AiConnectionRepository`、`AiConfigMigration`（配置版本迁移）、事件驱动 `*Handler` |
| **`ai-ui`** | 2.6K | 右侧诊断面板：`AiPanel`/`AiPanelState`、`ChatService`/`AdapterChatService`（流式 + `StreamingReplyBuffer`）、`AiSystemPrompts`（**不可信上下文包裹防注入**）、`MockChatService`（离线演示） |
| `openai-service`+`openai-ui` / `anthropic-service`+`anthropic-ui` | 643+110 | 每个 Provider 一对模块：`OpenAiServiceAdapter` / `AnthropicServiceAdapter`，UI 仅注册 branding + 表单 |
| **`message-interpreter-api` + 6 个实现 + `registry`** | 2.4K | 消息体解释器 SPI：`MessageInterpreter` 契约 + `MessageBodyDisplay`；实现：JSON（pretty/高亮）、XML、Hex dump、Base64、纯文本、**SAP IDoc**（`idoc`，行业深度场景）；`MessageInterpreters` 聚合注册 |

### 拓扑引擎

| 模块 | 行数 | 职责与关键类 |
|---|---|---|
| **`graph-core`** | 159 | 纯图模型 `TopologyGraph`（统一节点词汇、不可变数据结构） |
| **`topology-ui`** | 2.3K | 通用渲染引擎：`TopologyCanvas`（Canvas 手绘）、`TopologyLayout`、`TopologyNodeCard`/`NodeDetailCard`、节点拖拽（`NodeDragSession`）、缩放/滚动条、**`TopologyImageExporter`**（导出 PNG） |

---

## 三、详细功能清单（按业务域）

### 1. 平台宿主与通用底座

- **连接管理**：显式状态机（`AbstractConnectionManager`：Disconnected → Connecting → Connected，验证式建连），连接快照落库 `mq_connection`（每次保存新 `connection_id`，update 校验后 copy）
- **事件总线**：`EventBus` + 命名事件约定 `<serviceId>.connection.*`（`EventNames`），`onEventSuffix` 后缀通配订阅，模块级协程作用域 `moduleScope(parent, moduleId)` 随模块卸载取消
- **双速扫描调度**：慢扫（全量对象拓扑，受限并发）+ 快扫（状态/计数保活），`AbstractScanService` 统一模板 + `JobScheduler` 定时 + `ScannedCache` 内存快照缓存
- **持久化**：SQLDelight 4 表（`mq_connection` / `mq_command` / `ai_connection` / `chat_history`），事件驱动落库（`*SnapshotHandler` 订阅 EventBus），敏感字段 `@Sensitive` 注解 + `EncryptionService` 加密
- **i18n**：整表加载 `LanguageResources`，模块私有表经 `AdapterUI.i18nTables` SPI 合并（重名检测测试 `ModuleI18nMergeTest`），中英双语全覆盖；`userMessage()` 将 `ServiceException` 按 `errorKey` 渲染期翻译
- **全链路性能打点**：统一 `[MQ-Perf]` 前缀，UI 四点（触发/Loading/数据返回/退出）+ Service 五段（enter/instance/io/parse/done），日志即火焰图定位法
- **命令控制台**：`mq-ui/command`（跨协议），命令历史 `mq_command` 表持久化
- **文件基建**：FileKit 统一文件选择（`FilePickerUtil`/`PathRow`/`PathNormalize`），消息批量发送（限 `MAX_MESSAGE_FILE_MB`）

### 2. IBM MQ 深度支持

- 对象：Queue / Channel / Listener 全量 PCF 扫描 + 属性对话框（MQ Explorer 级 attrs 提取管线，`MqAttributeNameMapper` + 枚举表 `MqQMgrAttrEnums`，valueConstMap 兜底）
- 消息：浏览（独立 `MQQueue` 句柄 + `browsedCount` 光标等价翻页）、发送（`MessageSender`）、删除/重入队（`MessageAccess`）、**dmpmqmsg 搬移文件导入**（`DmpmqmsgFormat`）
- **MQSC 命令控制台**：多命令解析 `MqscParser`、`MqscCommandExpander`（escape 双参数格式）、经 PCF 发送（`MqscPcfSender`）、`MqrcTranslator` MQRC 错误码中文化、命令历史落库
- 集群拓扑：`ClusterRelationsPanel`（CLUSRCHR 送/收通道对）、固定列拓扑面板
- TLS：`IbmMqTls`（truststore p12/pfx/jks + `CipherSuiteOptions`）
- 连接表单：保存前试连 + `SaveAnywayDialog`（失败可强存）

### 3. Kafka 支持

- Topic 管理（config 修改）、Consumer Group lag 详情（`KafkaGroupDetailDialog`）、分区拓扑
- 消息直连实时查询模型（无定时任务无缓存，`assign + seek + poll`，不 commit 不 join group）：多起点拉取（Earliest / Latest / Offset / 最新回退 N 条）、分区级游标续读（`nextCursor`）、内部 topic 锁图标
- 句柄隔离：Consumer 每次读取独立实例 + `finally close(5s)`；AdminClient/Producer 连接级共享
- TLS：SASL_SSL + 自签 CA truststore

### 4. RabbitMQ 支持

- 管理 API 全套（Ktor CIO + Basic Auth）：exchanges / queues / bindings / nodes 5 GET + 4 写端点，交换机创建删除（守卫默认与 `amq.*`）、绑定 e→q
- 消息：`basicGet` 浏览（unacked 持有 + 关闭自动重入队 + 计数显示）、`basicPublish`（default-exchange 发送前 `queueDeclarePassive` 存在性检查防 unroutable 静默丢弃）、测试发布（routed 标志回读）、消息转发
- **连接韧性三层**：Stream 队列前置拦截（管理 API 查 `x-queue-type`，零 AMQP 请求）、半开连接自愈（`invalidateAmqpConnection` + `if (!isOpen)` 重建）、540 NOT_IMPLEMENTED 翻译为友好文案
- 队列属性**缓存优先秒开**（慢扫+快扫投影 + 后台静默刷新）+ 双 HttpClient 连接池（扫描/详情隔离）

### 5. RocketMQ 支持

- 集群连接（NameServer 多地址、ACL accessKey/secretKey、TLS + 自签 CA）、Broker/Topic/Group 面板、consumer progress 分级容错（CODE 17/206 → 单行 INFO）
- 消息：多队列并行拉取（8 临时 PullConsumer 分片 + 动态超时 `15s + 队列数×50ms÷并发`）、组内 offset 进度（`examineConsumeStats`）
- Remoting 协议全链路可打断（`runInterruptible` + 取消语义经 `ensureActive` 恢复）

### 6. AI 链路诊断引擎

- Provider SPI：`AiAdapterRegistry` 聚合，OpenAI-compatible / Anthropic 平级插件对（删除任一不影响宿主）
- 自实现 HTTP + **SSE 增量解析**（`AiHttp`/`SseParser`），流式回复缓冲（`StreamingReplyBuffer`）+ 事件驱动落库（chat_history 全审计）
- **上下文组装**：四个 MQ 模块各有 `*AiChainBuilder` 产出链路 JSON（节点 + `relationToNext` 消息流关系），经 `buildChainContextJson` → `wrapUntrustedContext` 统一进入面板
- **Prompt-Injection 加固**：`[BEGIN/END UNTRUSTED CONTEXT]` 边界标记 + 系统提示词安全规则 + 数据内伪造边界全角中和（`NEUTRALIZED_BEGIN`）
- 配置迁移 `AiConfigMigration`、连接快照复用 `mq_connection` 形态（`ai_connection`）、TLS 提示（`AiSslHint`）、离线 `MockChatService`

### 7. 拓扑图与可视化

- `graph-core.TopologyGraph` 纯模型 + `topology-ui` Canvas 渲染引擎：节点卡片、拖拽会话、缩放控制、滚动条几何、节点双击（`NodeDoubleClick`）
- **PNG 图片导出**（`TopologyImageExporter`，几何测试覆盖）
- 四协议接入：`ibm-mq-ui/topology`（集群边关系）、`kafka/rabbitmq/rocketmq-ui/topology` 各自 Panel；焦点选中经 `app-core.TopologyFocusHolder`（列表页"在拓扑图中查看"直达）

### 8. 消息解释器 SPI

- 契约：`MessageInterpreter`（`MessageBodyDisplay`/`MessagePreviewText`/`MessageCharsets` 编码检测）
- 内置 6 个：**JSON**（格式化）、**XML**、**Hex dump**、**Base64**、**Text**、**SAP IDoc**（行业级：段结构解析）
- `message-interpreter-registry.MessageInterpreters` 聚合，新增解释器 = 一个模块 + SPI 注册，UI 零改动

---

## 四、核心架构设计亮点

### 1. 三层 SPI 插件化，协议彻底解耦

- **服务侧**：`ServiceAdapter`（app-core）→ `MQServiceAdapter`（mq-base 模板：category/连接/扫描）→ 各协议 `*ServiceAdapter`；宿主 `app-ui` 仅声明 `implementation(projects.*Service)`，运行时 SPI 发现注册
- **UI 侧**：`AdapterUI` 平行 SPI（i18n 私有表合并、连接表单、属性对话框扩展点 `ConnectionPropertiesUi`）
- **AI 侧**：`AiAdapter`/Provider 平级插件对；**解释器侧**：`MessageInterpreter` 六实现
- 收益可量化：新增一个 MQ 协议 = 一对 service/ui 模块继承 `AbstractConnectionManager`/`AbstractScanService` 模板，平台层零改动

### 2. 并发与状态管理：故障隔离的演进式打磨

- **句柄生命周期规范**（"底层连接共享，业务句柄用完即毁"）：重量级（AdminClient/MQQueueManager/Producer）连接级共享 + 显式锁串行化；轻量（Consumer/MQQueue/Channel）每操作独立 + `finally` 释放。RabbitMQ 侧额外三层：一次性 `withOperationChannel`、半开连接自愈、Stream 队列前置拦截
- **可打断性贯穿**：所有阻塞 IO 经 `runInterruptible`，协程取消能真正 `Thread.interrupt()` 断开阻塞 socket；`ensureActive` 翻译"被框架包装的取消异常"（RocketMQ 把 InterruptedException 包成 `MQClientException` 的案例有专项处理），杜绝"关闭无响应/吞取消"
- **事件驱动落库 + 单槽串行 DB**：SQLDelight 写入全部经 EventBus handler，避免 UI 线程阻塞与并发写冲突
- **队列规模自适应**：RocketMQ 全 Queue 拉取按队列数自动从单实例顺序切换到 8 分片并行（临时 Consumer + `AtomicLong`/`ConcurrentHashMap`/`synchronizedList` 聚合），动态超时公式兜底

### 3. AI 上下文构建与 Prompt-Injection 防御

- 不可信数据**最外层单层包裹**（`[BEGIN UNTRUSTED CONTEXT]`），而非字段级标记——保留值语义、控制 token 体积
- 伪造边界**全角中和**（`［BEGIN…］`），保留可读性但失效为标记
- 安全规则与提示词全部进 i18n 表（`ai_untrusted_context_rule`），缺键走英文兜底链——**规则不是装饰文案，是安全边界**，缺键时兜底文案与 en 表逐字一致
- 四协议 `*AiChainBuilder` → `buildChainContextJson` → `wrapUntrustedContext` 单一管线，新 Provider 零重复实现

### 4. 可观测性内建

- `[MQ-Perf]` 结构化耗时日志（UI 四点 + Service 五段：instance/io/parse/totalMs），配合"判定口诀"（instanceMs>100=重复新建 / ioMs 上千=网络 / done 早出 UI 拖满=帧调度）实现日志级性能定位
- 预期态 vs 真异常日志分级（CODE 17/206 单行 INFO，真异常保留堆栈），生产日志不被噪音淹没

---

## 五、代码分布与量化指标

### 1. 总量

| 指标 | 数值 |
|---|---|
| Gradle 模块 | **33 个**（+ included build `gradle/plugins`） |
| Kotlin 文件 | **~619**（含 85 个测试文件 / 12.1K 行测试） |
| Kotlin 总行数 | **~99.5K**（主代码 ~87.5K） |
| `@Composable` | **512** |
| `suspend fun` | **276** |
| Service/Manager/AdminClient 类 | **21**（四协议 ConnectionManager×4、ScanService×4、MessageService×2、AdminClient×2、Adapter×7、IbmMQService 等） |
| SQLDelight 表 | 4（`mq_connection`/`mq_command`/`ai_connection`/`chat_history`） |

### 2. 模块行数占比（占 ~99.5K 总量）

```text
IBM MQ 协议对     22.5%  ██████████████████████▌   (ibm-mq-ui 13.3% + ibm-mq-service 9.2%)
app-core+app-ui   16.1%  ████████████████          (8.3% + 7.8%)
RabbitMQ 协议对   14.9%  ██████████████▉           (8.3% + 6.5%)
Kafka 协议对      13.3%  █████████████▎            (8.1% + 5.2%)
RocketMQ 协议对   13.1%  █████████████             (7.6% + 5.5%)
AI 栈              5.9%  █████▉                    (ai-ui 2.6% + ai-service 1.2% + ai-core 0.7% + providers 0.7%)
ui-common         4.3%  ████▎
mq-ui             3.6%  ███▌
拓扑引擎          2.5%  ██▌                       (topology-ui 2.3% + graph-core 0.2%)
消息解释器群      2.4%  ██▍
mq-base           1.3%  █▎
其余(test-kit等)  0.3%
```

**结构观察**：四大协议对合计 **63.8%**——功能主体在协议深度；平台/AI/通用件合计 **~19%**——抽象层克制（模板消除样板后 mq-base 仅 1.3K 行却承载四个协议的连接/扫描骨架），符合"抽象按需生长"的工程审美。

---

*数据来源：`settings.gradle.kts` include 清单、`find/wc -l` 全量扫描、`grep @Composable/suspend fun` 统计。*
