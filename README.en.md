# MQ Client Desktop

> **Local-first · Unified multi-protocol management · AI-powered diagnosis workbench**
> Built with Kotlin Multiplatform + Compose Desktop. One UI for IBM MQ, Kafka, RabbitMQ and RocketMQ.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose%20Desktop-Material%203-4285F4)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![JDK](https://img.shields.io/badge/JDK-25-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**English** · [简体中文](README.md)

---

## 🎯 Why this exists

If you operate messaging middleware, you know the drill: MQ Explorer for IBM MQ, kafka-tool for Kafka, a browser tab for RabbitMQ, another console for RocketMQ. Four tools, four mental models, four sets of connection configs scattered around — and by the time you finally notice "the queue is backing up", the actual problem is usually **at the other end of the channel**.

MQ Client Desktop folds all of that into one local desktop app, and does something you won't find elsewhere: **it hands the entire message path to an AI to look at**.

---

## 🔥 Killer features

### 🧠 AI chain diagnosis — "the graph is the context"

This is not "an AI with a chat box". Click **Ask AI** on any object page, and the app packages the **complete picture of that object on that connection** into structured context:

```
object attributes + upstream/downstream topology (who produces, who consumes, via which channel)
                 + live backlog data (queue depth / consumer count / channel status)
                 + cluster relations (cluster member? repository role?)
                    ↓
      OpenAI-compatible / Anthropic adapters
                    ↓
   "XMITQ backed up at 3200 messages, DOWNSTREAM.CHL is RETRYING,
    the peer QM2 cluster receiver channel is down" ← root cause located
```

Each MQ module ships its own chain builder (`IbmMqAiChainBuilder` / `KafkaAiChainBuilder` / …) that turns "the stuff you can't see in a table" into text the model can reason over. **Locate backlog and channel breakage in seconds.**

### 🚀 Deep multi-protocol abstraction — zero coupling via SPI

Built on the `ServiceAdapter` SPI. Adding a new MQ protocol means implementing the interface and registering it — **not a single line of the host application changes**:

```kotlin
interface ServiceAdapter {
    val id: String                          // module id, also the event-name prefix
    fun initialize(context: ServiceContext) // event bus + scheduler + its own scope
}
```

Every `*-service` module depends only on `app-core` interfaces; every `*-ui` module depends only on `ui-common` components — **business modules never depend on each other**.

### 🕸️ Interactive topology graph

Relationship discovery on top of the `graph-core` model and the Kuiver renderer:

- **Automatic layout** — IBM MQ uses fixed columns following message flow (alias → topic → sub → remote → xmitq → channel → remote QMGR); Kafka / RabbitMQ / RocketMQ each have their own layout strategy
- **Node dragging** — drag a node without disturbing its neighbours; disable dragging and the whole canvas pans instead
- **Double-click** — opens that node's property panel with live runtime status
- **High-resolution export** — 2x offscreen render to PNG, ready to paste into an incident report

### 🛠️ Native protocols — no watered-down wrappers

| Protocol | Underlying implementation | Where it refuses to compromise |
| :--- | :--- | :--- |
| IBM MQ | **PCF + MQSC** (`com.ibm.mq.allclient`) | Raw PCF INQUIRE for the full attribute set; the console sends MQSC via `MQCMD_ESCAPE` |
| Kafka | **AdminClient + Consumer/Producer** | `listOffsets` with multiple starting points, `alterConsumerGroupOffsets` for offset resets |
| RabbitMQ | **AMQP 0.9.1 + Management HTTP API** | `basicGet` read-only browsing with a `basicNack(requeue)` no-swallow guarantee |
| RocketMQ | **Remoting** (`DefaultMQAdminExt`) | Group-level lag via `examineConsumeStats`, Tag/Keys filtering |

There is no "we simplified it for you" REST layer in between — **what you see is what the broker said**.

---

## 📸 Screenshots

![Topology](docs/images/topology.png)
*Interactive topology: automatic layout, node dragging, double-click for properties*

![Queue list](docs/images/queues.png)
*Queue list: live depth/consumer badges, status dots, one-click AI diagnosis*

![Command console](docs/images/console.png)
*MQSC console: DBeaver-like experience — abbreviation expansion, destructive-command confirmation, per-line status*

---

## 📊 Feature matrix

| Core capability | IBM MQ | Kafka | RabbitMQ | RocketMQ |
| :--- | :---: | :---: | :---: | :---: |
| Connection management | ✅ (PCF/MQSC) | ✅ (SASL/SSL) | ✅ (AMQP/HTTP) | ✅ (TLS/ACL) |
| Object management | ✅ | ✅ | ✅ | ✅ |
| Message browse & send | ✅ (native browse cursor) | ✅ (multi-start offsets) | ✅ (no-swallow ack) | ✅ (Tag/Keys) |
| Interactive topology | ✅ | ✅ | ✅ | ✅ |
| AI chain diagnosis | ✅ | ✅ | ✅ | ✅ |

---

## 🏗️ Architecture

```mermaid
graph TB
    subgraph HOST["🖥️ Host layer"]
        APPUI["app-ui<br/>main window · sidebar · tab routing"]
    end

    subgraph COMMON["📦 Shared layer"]
        UIC["ui-common<br/>PropertyTable · Snackbar<br/>FailureText · theme"]
        CORE["app-core<br/>EventBus · JobScheduler<br/>SQLite · SPI contracts<br/>CacheManager · encryption"]
    end

    subgraph BIZ["🔌 Business modules"]
        subgraph IBMMQ["IBM MQ"]
            IBM_S["ibm-mq-service<br/>PCF · MQSC · PCF Agent"]
            IBM_U["ibm-mq-ui<br/>queues/channels/subs/topology"]
        end
        subgraph KAFKA["Kafka"]
            KAF_S["kafka-service<br/>AdminClient · Consumer"]
            KAF_U["kafka-ui<br/>topics/groups/cluster"]
        end
        subgraph RMQ["RabbitMQ"]
            RAB_S["rabbitmq-service<br/>AMQP · Management API"]
            RAB_U["rabbitmq-ui<br/>queues/exchanges/bindings"]
        end
        subgraph ROCKET["RocketMQ"]
            ROC_S["rocketmq-service<br/>Remoting · AdminExt"]
            ROC_U["rocketmq-ui<br/>topics/groups/routes"]
        end
    end

    subgraph AI["🧠 AI stack"]
        AICORE["ai-core<br/>adapter contracts · HTTP plumbing"]
        AIPROV["openai-service · anthropic-service<br/>OpenAI-compatible · Anthropic"]
        AIUI["ai-ui<br/>chat panel · multi-connection"]
    end

    subgraph GRAPH["🕸️ Graph capability"]
        GCORE["graph-core<br/>node/edge model · severity"]
        TUI["topology-ui<br/>TopologyCanvas · drag · export"]
    end

    subgraph PERSIST["💾 Local storage"]
        TOML["config.toml<br/>encrypted connection config"]
        SQLITE[("SQLite<br/>chat history · command audit")]
        LOGS["logs/<br/>unified logging"]
    end

    APPUI --> UIC
    APPUI --> CORE
    APPUI -->|SPI discovery| BIZ
    APPUI -->|SPI discovery| AIUI

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

**Layering rule**: `*-service` modules never depend on Compose; `*-ui` modules never depend on a concrete protocol implementation. They meet only through `app-core` SPI contracts and the event bus. **Delete any one MQ module and the rest still starts.**

---

## ✨ Engineering highlights

### Unified visual language

Every module uses the borderless, zebra-striped `PropertyTable`: 120dp fixed-width keys, left-aligned selectable values, two-line truncation, `-` for empty values with the copy button hidden. **The `failureText` implementation that had been duplicated across 13 dialogs is now a single shared function** — and it unwraps `CompletionException` too (what `CompletableFuture` async chains actually throw).

### DBeaver-like MQSC console

```
dis q(*)                   →  DISPLAY QLOCAL(*)          ✅ 12ms
def ql(Q1) MAXDEPTH(5000)  →  auto-expanded              ⚠️ confirm
del chl(CH1)               →  DELETE CHANNEL(CH1)        ⏸️ awaiting confirm
```

- Abbreviations expand per runmqsc convention (`dis`/`disp`, `def`, `del`, `alt`, `clr`, `sta`, `sto`, `res`, `ref`, `sus`)
- Destructive verbs (`DELETE`/`CLEAR`/`ALTER`/`STOP`/`RESET`) are checked **after** expansion, so abbreviations cannot bypass confirmation
- Per-line status: pending (grey line) / running (spinner) / success / failure, with MQRC reason codes translated to readable text

### Security first

- **Sensitive data encrypted at rest with AES-256-GCM**, key kept separately in `~/.mqclient/secret.key`, auto-detected by key name (`password`, `secret`, …)
- **Self-signed CAs fully supported** — JKS / PKCS12 / PEM truststores; RabbitMQ's AMQP and Management API ports each mount their own trust chain
- **Raw error codes never reach the UI** — the service layer translates `CODE: 17 DESC: ...` into friendly messages; raw exceptions go to the log only

### Data management

Everything lives under `~/.mqclient/`:

```
~/.mqclient/
├── config.toml      # connection config (sensitive fields encrypted)
├── secret.key       # AES key
├── logs/            # unified logs (RocketMQ's scattered logs redirected here)
└── *.db             # SQLite (chat history / command audit / connection snapshots)
```

---

## 🚦 Getting started

### Requirements

- **JDK 25** (`jvmToolchain(25)`)
- No Gradle install needed — the wrapper is committed

### Run

```bash
./gradlew run
```

### Test

```bash
./gradlew jvmTest                    # full unit test suite
./gradlew compileKotlinJvm           # compile only
./gradlew :ibm-mq-service:jvmTest    # a single module
```

### Live diagnostics (optional)

Some tests hit a real broker and skip automatically when none is reachable:

```bash
# IBM MQ: uses the first IBM MQ connection in ~/.mqclient/config.toml
./gradlew :ibm-mq-service:jvmTest --tests "*PcfScanBudgetLiveTest"

# RabbitMQ: pass a restricted account to exercise the partial-failure path
./gradlew :rabbitmq-service:jvmTest \
  --tests "*RabbitMqScanPartialLiveTest" \
  "-Drabbitmqdiag.user=your_user" \
  "-Drabbitmqdiag.pass=your_password"
```

### First run

1. Right-click the sidebar → **New connection**
2. Pick a protocol, fill in address and credentials (passwords are encrypted on save)
3. Right-click → **Connect** (explicit-connect model — nothing connects automatically)
4. Expand the connection tree to browse queues / topics / topology

> 💡 **AI diagnosis** requires configuring a provider first (Settings → AI), either OpenAI-compatible or Anthropic.

---

## 🧭 Design decisions

A few choices look counter-intuitive until you know why:

| Decision | Rationale |
| :--- | :--- |
| **Explicit connect model** | Nothing connects to a broker at startup. A sidebar entry is *configuration*; clicking Connect is what opens the TCP connection. Prevents a burst of connections hitting production right after launch. |
| **Cache-first rendering** | Opening a page reads the cache (zero latency); background scans refresh it via events. Switching tabs never spins. |
| **Partial scan failure keeps the cache** | When one query fails (permissions / timeout), the previous data is kept and a banner is shown at the top — instead of writing an empty list. Users can tell "there is genuinely nothing here" from "we didn't manage to read it this time". |
| **A single failed query never marks the connection dead** | RabbitMQ's `/api/nodes` requires the `monitor` tag; a read-only account failing it is normal. Only when all categories fail is the connection marked disconnected. |
| **Cooperative timeout** | `PCFMessageAgent.send` and `disconnect` share a monitor, and `interrupt()` does nothing for a socket wait — so a timeout only refuses the *next* request and never tries to interrupt an in-flight one. |

---

## 📁 Module map

```
mq-client/
├── app-ui/              host: main window, sidebar, tab routing
├── app-core/            kernel: SPI · EventBus · JobScheduler · SQLite · encryption · cache
├── ui-common/           shared widgets: PropertyTable · buttons · FailureText · i18n
├── ui-test-kit/         Compose UI test doubles (UiThreadHarness / RecordingPersistence)
├── mq-base/ · mq-ui/    MQ base types and the command-console framework
├── graph-core/ · topology-ui/   topology model and canvas rendering
├── ai-core/             AI adapter contracts + HTTP plumbing
├── openai-service/ · anthropic-service/ · ai-ui/
├── ibm-mq-service/ · ibm-mq-ui/
├── kafka-service/ · kafka-ui/
├── rabbitmq-service/ · rabbitmq-ui/
├── rocketmq-service/ · rocketmq-ui/
└── scripts/             i18n reference check · MQ attribute table generator · SSL diagnostics
```

---

## 🤝 Contributing

```bash
# before committing
./gradlew assemble jvmTest
python scripts/check_i18n_refs.py   # new i18n keys need both zh-CN and en-US
```

**Conventions**:

- `*-service` modules **must not** depend on Compose
- New i18n keys must be added to both `zh-CN` and `en-US` (the script fails otherwise)
- The UI layer never shows raw error codes; the service layer translates them
- Don't wrap suspend calls in `runCatching` (use `runCatchingCancellable`)

📐 **UI component contracts**: visual and semantic rules for property tables and buttons live in [doc/ui-conventions.md](doc/ui-conventions.md).

---

## 📄 License

[Apache-2.0](LICENSE)

---

<div align="center">

**If this saved you from installing one more client, drop a ⭐**

</div>
