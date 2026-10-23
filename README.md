# MQ Client Desktop

> **本地优先 · 多协议统一管理 · AI 智能诊断工作台**
> 基于 Kotlin Multiplatform + Compose Desktop 构建，一套 UI 同时管理 IBM MQ、Kafka、RabbitMQ、RocketMQ。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose%20Desktop-Material%203-4285F4)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![JDK](https://img.shields.io/badge/JDK-25-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[简体中文](README.md) · **English**

---

## 🎯 为什么是它？

运维中间件的人都经历过：IBM MQ 用 MQ Explorer、Kafka 用 kafka-tool、RabbitMQ 开浏览器、RocketMQ 再装一套控制台。四个工具、四套心智模型、四份散落的连接配置 —— 而当你终于看到「队列在堆积」时，真正的问题往往在**另一端的通道**上。

MQ Client Desktop 把这些收进一个本地桌面应用，并且做了一件别处没有的事：**把整条消息链路交给 AI 看**。

---

## 🔥 核心杀手锏

### 🧠 AI 链路诊断 —— 「图即上下文」

这不是「给 AI 一个聊天框」。点击任意对象页的「询问 AI」，应用会把**当前连接该对象的完整图景**打包成结构化上下文：

```
对象属性 + 上下游拓扑（谁生产、谁消费、经哪条通道）
        + 实时积压数据（队列深度 / 消费者数 / 通道状态）
        + 聚类关系（是否集群成员、仓库角色）
                    ↓
      OpenAI 兼容 / Anthropic 适配器
                    ↓
   「XMITQ 堆积 3200 条，DOWNSTREAM.CHL 状态 RETRYING，
     对端 QM2 集群接收通道未运行」← 根因定位
```

四个 MQ 模块各有专属的链路构建器（`IbmMqAiChainBuilder` / `KafkaAiChainBuilder` / …），把「表格里看不出来的东西」变成 AI 能推理的文本。**秒级定位消息堆积、通道断裂的根因。**

### 🚀 多协议深度抽象 —— SPI 零耦合

基于 `ServiceAdapter` SPI 机制，新增一个 MQ 协议只需实现接口并注册，**不需要改动主程序任何一行**：

```kotlin
interface ServiceAdapter {
    val id: String                          // 模块标识，事件名前缀
    fun initialize(context: ServiceContext) // 拿到总线 + 调度器 + 自己的 scope
}
```

每个 `*-service` 模块只依赖 `app-core` 的接口，每个 `*-ui` 模块只依赖 `ui-common` 的组件 —— **业务模块之间零互相依赖**。

### 🕸️ 交互式拓扑图

基于 `graph-core` 的关系模型与 Kuiver 渲染引擎，自动发现对象关系：

- **自动布局**：IBM MQ 按消息流向固定列（alias → topic → sub → remote → xmitq → channel → 远程 QMGR），Kafka / RabbitMQ / RocketMQ 各有专属布局策略
- **节点拖拽**：按住节点拖动，不干扰其他节点；关闭拖拽则整画布平移
- **双击弹窗**：直接打开该节点的属性面板，含实时运行状态
- **高清导出**：2x 离屏渲染导出 PNG，可以直接贴进故障报告

### 🛠️ 硬核原生协议 —— 拒绝阉割

| 协议 | 底层实现 | 不妥协的地方 |
| :--- | :--- | :--- |
| IBM MQ | **PCF + MQSC**（`com.ibm.mq.allclient`） | 直接发 PCF INQUIRE，拿全量属性；MQSC 控制台走 `MQCMD_ESCAPE` |
| Kafka | **AdminClient + Consumer/Producer** | `listOffsets` 多起点定位、`alterConsumerGroupOffsets` 重置位移 |
| RabbitMQ | **AMQP 0.9.1 + 管理 HTTP API** | `basicGet` 只读浏览 + `basicNack(requeue)` 防吞机制 |
| RocketMQ | **Remoting 协议**（`DefaultMQAdminExt`） | `examineConsumeStats` 组级 Lag、Tag/Keys 过滤 |

没有一层「我们帮你简化了」的 REST 中间层 —— **看到的就是 broker 说的**。

---

## 📸 界面预览

![拓扑图](docs/images/topology.png)
*交互式拓扑图：自动布局 + 节点拖拽 + 双击查看属性*

![队列列表](docs/images/queues.png)
*队列列表：深度/消费者实时徽章 + 状态色点 + 一键 AI 诊断*

![命令控制台](docs/images/console.png)
*MQSC 控制台：类 DBeaver 体验，缩写展开 + 危险命令确认 + 逐行状态*

---

## 📊 核心功能矩阵

| 核心模块 | IBM MQ | Kafka | RabbitMQ | RocketMQ |
| :--- | :---: | :---: | :---: | :---: |
| 连接管理 | ✅ (PCF/MQSC) | ✅ (SASL/SSL) | ✅ (AMQP/HTTP) | ✅ (TLS/ACL) |
| 对象管理 | ✅ | ✅ | ✅ | ✅ |
| 消息浏览与发送 | ✅ (原生浏览游标) | ✅ (多起点定位) | ✅ (防吞机制) | ✅ (Tag/Keys) |
| 交互式拓扑图 | ✅ | ✅ | ✅ | ✅ |
| AI 链路诊断 | ✅ | ✅ | ✅ | ✅ |

---

## 🏗️ 系统架构

```mermaid
graph TB
    subgraph HOST["🖥️ 宿主层"]
        APPUI["app-ui<br/>主窗口 · 侧栏 · Tab 导航"]
    end

    subgraph COMMON["📦 公共层"]
        UIC["ui-common<br/>PropertyTable · Snackbar<br/>FailureText · 主题"]
        CORE["app-core<br/>EventBus · JobScheduler<br/>SQLite · SPI 定义<br/>CacheManager · 加密"]
    end

    subgraph BIZ["🔌 业务模块层"]
        subgraph IBMMQ["IBM MQ"]
            IBM_S["ibm-mq-service<br/>PCF · MQSC · PCF Agent"]
            IBM_U["ibm-mq-ui<br/>队列/通道/订阅/拓扑"]
        end
        subgraph KAFKA["Kafka"]
            KAF_S["kafka-service<br/>AdminClient · Consumer"]
            KAF_U["kafka-ui<br/>主题/消费组/集群"]
        end
        subgraph RMQ["RabbitMQ"]
            RAB_S["rabbitmq-service<br/>AMQP · 管理 API"]
            RAB_U["rabbitmq-ui<br/>队列/交换机/绑定"]
        end
        subgraph ROCKET["RocketMQ"]
            ROC_S["rocketmq-service<br/>Remoting · AdminExt"]
            ROC_U["rocketmq-ui<br/>主题/消费组/路由"]
        end
    end

    subgraph AI["🧠 AI 栈"]
        AICORE["ai-core<br/>适配器契约 · HTTP 基建"]
        AIPROV["openai-service · anthropic-service<br/>OpenAI 兼容 · Anthropic"]
        AIUI["ai-ui<br/>会话面板 · 多连接切换"]
    end

    subgraph GRAPH["🕸️ 图能力"]
        GCORE["graph-core<br/>节点/边模型 · 严重度"]
        TUI["topology-ui<br/>TopologyCanvas · 拖拽 · 导出"]
    end

    subgraph PERSIST["💾 本地存储"]
        TOML["config.toml<br/>加密连接配置"]
        SQLITE[("SQLite<br/>会话历史 · 命令审计")]
        LOGS["logs/<br/>统一日志"]
    end

    APPUI --> UIC
    APPUI --> CORE
    APPUI -->|SPI 加载| BIZ
    APPUI -->|SPI 加载| AIUI

    IBM_U --> IBM_S
    KAF_U --> KAF_S
    RAB_U --> RAB_S
    ROC_U --> ROC_S

    IBM_S --> CORE
    KAF_S --> CORE
    RAB_S --> CORE
    ROC_S --> CORE

    IBM_U --> TUI
    KAF_U --> TUI
    RAB_U --> TUI
    ROC_U --> TUI
    TUI --> GCORE
    TUI --> UIC

    IBM_S --> AICORE
    KAF_S --> AICORE
    RAB_S --> AICORE
    ROC_S --> AICORE
    AICORE --> AIPROV
    AIUI --> AICORE
    AIUI --> UIC

    CORE --> TOML
    CORE --> SQLITE
    CORE --> LOGS

    classDef host fill:#4285F4,stroke:#1a73e8,color:#fff
    classDef common fill:#34A853,stroke:#1e8e3e,color:#fff
    classDef biz fill:#FBBC04,stroke:#f9ab00,color:#000
    classDef ai fill:#EA4335,stroke:#c5221f,color:#fff
    classDef graph fill:#9334E6,stroke:#7627bb,color:#fff
    classDef persist fill:#5F6368,stroke:#3c4043,color:#fff

    class APPUI host
    class UIC,CORE common
    class IBM_S,IBM_U,KAF_S,KAF_U,RAB_S,RAB_U,ROC_S,ROC_U biz
    class AICORE,AIPROV,AIUI ai
    class GCORE,TUI graph
    class TOML,SQLITE,LOGS persist
```

**分层原则**：`*-service` 不依赖 Compose，`*-ui` 不依赖具体协议实现，两者只通过 `app-core` 的 SPI 契约与事件总线通信。**删除任意一个 MQ 模块，其余部分照常启动。**

---

## ✨ 工程亮点

### 统一视觉规范

全模块采用无边框斑马纹 `PropertyTable`，Key 定宽 120dp、Value 左对齐可拖选，超长两行截断，空值显示 `-` 且不渲染复制按钮。**13 个对话框的 `failureText` 已合并为一份实现**，顺带解包 `CompletionException`（`CompletableFuture` 异步链抛的就是它）。

### 类 DBeaver 的 MQSC 控制台

```
dis q(*)                   →  DISPLAY QLOCAL(*)          ✅ 12ms
def ql(Q1) MAXDEPTH(5000)  →  自动展开                    ⚠️ 确认
del chl(CH1)               →  DELETE CHANNEL(CH1)         ⏸️ 等待确认
```

- 缩写按 runmqsc 习惯自动展开（`dis`/`disp`、`def`、`del`、`alt`、`clr`、`sta`、`sto`、`res`、`ref`、`sus`）
- 危险动词（`DELETE`/`CLEAR`/`ALTER`/`STOP`/`RESET`）**在展开之后**判定，缩写绕不过去
- 逐行状态：未执行（灰线）/ 执行中（进度圈）/ 成功 / 失败，MQRC 原因码已翻译成中文

### 安全至上

- **敏感数据 AES-256-GCM 加密落盘**，密钥独立存于 `~/.mqclient/secret.key`，按 `password` / `secret` 等键名自动识别
- **自签 CA 完美适配**：JKS / PKCS12 / PEM 三种信任库格式，RabbitMQ 的 AMQP 与管理 API 双端口各自挂定制信任链
- **UI 层严格隔离原始异常码**：service 层把 `CODE: 17 DESC: ...` 翻译成友好文案，原始异常只进日志

### 数据管理

统一存储于 `~/.mqclient/`：

```
~/.mqclient/
├── config.toml      # 连接配置（敏感字段加密）
├── secret.key       # AES 密钥
├── logs/            # 统一日志（自动重定向 RocketMQ 等散落日志）
└── *.db             # SQLite（会话历史 / 命令审计 / 连接快照）
```

---

## 🚦 快速开始

### 环境要求

- **JDK 25**（`jvmToolchain(25)`）
- 无需预装 Gradle，仓库自带 wrapper

### 运行

```bash
./gradlew run
```

### 测试

```bash
./gradlew jvmTest                    # 全量单元测试
./gradlew compileKotlinJvm           # 仅编译
./gradlew :ibm-mq-service:jvmTest    # 单个模块
```

### 真机诊断测试（可选）

部分测试会连真实 broker，无环境时自动 SKIP：

```bash
# IBM MQ：走 ~/.mqclient/config.toml 里第一条 IBM MQ 连接
./gradlew :ibm-mq-service:jvmTest --tests "*PcfScanBudgetLiveTest"

# RabbitMQ：传受限账号可验证部分失败分支
./gradlew :rabbitmq-service:jvmTest \
  --tests "*RabbitMqScanPartialLiveTest" \
  "-Drabbitmqdiag.user=your_user" \
  "-Drabbitmqdiag.pass=your_password"
```

### 首次使用

1. 启动后从侧栏右键 → **新建连接**
2. 选择协议、填写地址与凭据（密码落盘自动加密）
3. 右键 → **连接**（显式连接模型，不会自动建连）
4. 展开连接树，浏览队列 / 主题 / 拓扑

> 💡 **AI 诊断**需要先在「设置 → AI」中配置一个 provider（OpenAI 兼容或 Anthropic）。

---

## 🧭 设计决策备忘

有些选择看起来反直觉，但都有原因：

| 决策 | 原因 |
| :--- | :--- |
| **显式连接模型** | 启动时不主动连任何 broker。侧栏的连接是「配置」，点「连接」才建 TCP。避免开机瞬间大量连接打向生产环境。 |
| **缓存优先渲染** | 进页面先读缓存（零延迟），后台扫描完成后经事件驱动刷新。切换 Tab 不转圈。 |
| **部分扫描失败不清空缓存** | 单类查询失败（权限 / 超时）时保留上一轮数据 + 顶部提示条，而不是写空表。用户能区分「这里本来就没有」和「这次没读到」。 |
| **单类失败不判断连** | RabbitMQ 的 `/api/nodes` 要 `monitor` tag，只读账号读不到是常态。四类全失败才标连接断开。 |
| **协作式超时** | `PCFMessageAgent.send` 与 `disconnect` 同锁、`interrupt()` 对 socket 等待无效 —— 所以超时只拒绝**下一次**请求，从不试图打断在飞的那次。 |

---

## 📁 模块地图

```
mq-client/
├── app-ui/              宿主：主窗口、侧栏、Tab 路由
├── app-core/            公共内核：SPI · EventBus · JobScheduler · SQLite · 加密 · Cache
├── ui-common/           通用组件：PropertyTable · 按钮 · FailureText · i18n
├── ui-test-kit/         Compose UI 测试替身（UiThreadHarness / RecordingPersistence）
├── mq-base/ · mq-ui/    MQ 公共基类与命令控制台框架
├── graph-core/ · topology-ui/   拓扑模型与画布渲染
├── ai-core/             AI 适配器契约 + HTTP 基建
├── openai-service/ · anthropic-service/ · ai-ui/
├── ibm-mq-service/ · ibm-mq-ui/
├── kafka-service/ · kafka-ui/
├── rabbitmq-service/ · rabbitmq-ui/
├── rocketmq-service/ · rocketmq-ui/
└── scripts/             i18n 引用检查 · MQ 属性表生成 · SSL 诊断
```

---

## 🤝 参与贡献

```bash
# 提交前跑一遍
./gradlew assemble jvmTest
python scripts/check_i18n_refs.py   # 新增文案键必须中英文都补
```

**约定**：

- `*-service` 模块**不得**依赖 Compose
- 新增 i18n 键必须同时补 `zh-CN` 与 `en-US`（脚本会失败）
- UI 层不展示原始错误码，service 层负责翻译
- 挂起调用不要用 `runCatching`（用 `runCatchingCancellable`）

📐 **UI 组件契约**：属性表与按钮的视觉 / 语义规范见 [doc/ui-conventions.md](doc/ui-conventions.md)。

---

## 📄 License

[Apache-2.0](LICENSE)

---

<div align="center">

**如果这个项目帮你少装了一个客户端，给个 ⭐ 吧**

</div>
