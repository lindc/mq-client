# CLAUDE.md

KMP 桌面端中间件管理工具（IBM MQ / Kafka / RabbitMQ / RocketMQ），Compose Multiplatform for Desktop + Material 3。项目结构与约定详见 `README.md`。

## 构建与测试

- **必须用本地 Gradle**（`/opt/software/build/gradle`，9.7.1）——`services.gradle.org` 不可达，`./gradlew` 离线跑不了：
  ```bash
  /opt/software/build/gradle/bin/gradle assemble jvmTest   # 全量构建 + 测试
  /opt/software/build/gradle/bin/gradle :rabbitmq-ui:jvmTest  # 单模块
  ```
- 实连 diag 测试默认跳过，需 `-Dkafkadiag.enabled=true -D...` / `-Drocketmqdiag.enabled=true -D...` 透传（见 `kafka-service` / `rocketmq-service` 的 `build.gradle.kts` 与 diag 测试 KDoc）。

## 架构速查

- SPI 自动发现：模块实现 `ServiceAdapter`（rabbitmq-service 等 `META-INF/services/`），宿主零注册；导航 / 表单 / 图标 / i18n / 连接快照全部「注册即生效」。
- 数据流约定：**缓存优先 + 事件驱动**——扫描服务写 per-connection `CacheManager` + 发 `<module>.scan.*.completed` 事件，UI 进页读缓存、订阅事件刷新。
- 连接管理：显式连接模型（登记 DISCONNECTED，用户动作才建连），参照 `KafkaConnectionManager`。
- **ToolbarButton**（ui-common `uicommon/command/`）是三模块工具栏的统一按钮：扁平 / hover 浅底 / `isActive` 激活变主色；内部 pointerInput 手写点击（不用 clickable——会抢 CommandConsole 焦点）。新增工具栏动作一律用它。
- **普通操作按钮**统一用 ui-common `uicommon/Buttons.kt` 的 `PrimaryButton`（创建 / 保存 / 发送）、`SecondaryButton`（取消 / 关闭 / 返回）、`DangerButton`（删除 / 清空 / 重置）；禁用态由组件内统一处理，调用方不要再传 `containerColor` / `contentColor` / `border`（详见 README「按钮规范」）。
- **拓扑图**统一用 topology-ui 的 `TopologyCanvas`；右上角缩放控件内建「导出图片」（GraphicsLayer 只录 `KuiverViewer`，不含工具栏 / 滚动条 / 详情卡；PNG 经 ui-common 的 FileKit 保存）。
- 嵌套 AlertDialog 必须声明在外壳的 content lambda 内（窗口归属，见 ObjectDetailDialog KDoc）。
- 敏感配置（password 等）经 `SensitiveOptionsSerializer` 自动加密落盘，模块内不做手工加密。
- 语义色约定：告警橙 `0xFFED6C02`、健康绿 `0xFF2E7D32`，不随主题翻转。

## 模块功能现状

- **IBM MQ**：连接管理、MQSC 控制台、对象扫描、消息浏览 / 发送 / 下载、AI 链路分析（最完整）。
- **Kafka**：连接 / 扫描、Topic 管理、消费组重置位移（earliest / latest / 按时间戳，含状态守卫）、消息读写、拓扑图、AI 链路（含消费组 lag 上下文）、diag 实连测试。
- **RabbitMQ**（管理面 + 消息面均已落地）：管理 API（Ktor + Basic Auth，vhost 按 `%2F` 编码）、显式连接、对象慢扫（5min）+ 队列计数快扫（10s）、概览 Dashboard（指标卡 + 健康度 Banner + Loading 骨架）、队列 / 交换机列表、交换机创建 / 删除 / 属性（**Bindings 增删查**：添加绑定弹窗 + 行内删除，`propertiesKey` 随列表下发用于 DELETE 路径；系统交换机默认 / amq.* 禁写）、队列创建 / 清空 / 删除、拓扑图（Exchange → Binding → Queue 两列布局，双击 exchange 开属性）、AI 链路（VHOST → EXCHANGE → QUEUE，含绑定 routingKey 与队列积压）。
- **RabbitMQ 消息面**（AMQP 0.9.1，`RabbitMqMessageSession`）：`basicGet(autoAck = false)` 只读浏览（未 ack 的消息仍留在队列）→ 行内 `basicAck` 彻底消费、`basicNack(requeue)` 全部放回（「重新拉取」）、`basicPublish` 直发当前队列、`forward` 经任意 exchange + routingKey 转发并保留原消息属性。对话框：`RabbitMqQueueMessagesDialog`（拉取 / 详情 / 删除 / 重发）、`RabbitMqSendMessageDialog`、`RabbitMqForwardMessageDialog`。channel 一次性、失败即报废，`channelOps` 单飞守卫。
- **RabbitMQ 待做**：拓扑 E2E 绑定边、Routing Key 边标签（待 kuiver 支持）。
- **RocketMQ**：连接表单（ACL + TLS：Truststore 收 JKS/P12 自动转 PEM、PEM CA 证书直接透传；Keystore 双向认证；密码框随文件联动禁用）、显式连接、对象双扫（慢扫 clusterInfo/topics/groups + 快扫消费组 Lag）、Topic 删除、拓扑图、diag 实连测试。TLS 坑位：`ClientConfig.useTLS` 构造时固化（必须 `setUseTLS` 显式传，start 时写 `tls.enable` 全局无效），`TlsSystemConfig` 证书字段在 start() 内 `buildSslContext` 才读取（窗口罩 start()）； PEM 证书若 `IOException` 客户端会静默降级明文，构造前必须预校验。

## 测试环境

chillbear 服务器（`ssh app@www.chillbear.top`，容器走 `podman`）上的中间件容器**抢同一组
宿主端口 11207-11209**，因此**同一时刻只能起一套**。这不是推测：今天依次观察到 ——
RabbitMQ 停机时 IBM MQ 的 QM2 在 11208 可连；IBM MQ 停机、RabbitMQ 起来后，同一个
11208 变成了 RabbitMQ 的管理 HTTP。切换前先 `podman ps -a` 看清楚当前跑的是哪套，
否则新容器会因端口冲突起不来。

（Kafka 在 `~/.mqclient/config.toml` 里也写的 11207 —— 它跟上面同一命运，只是当前没有
Kafka 容器在跑，未实测。）

**当前部署状态（`podman ps -a` 实测）**：只有 rabbit-1/2/3 在跑（镜像
`rabbitmq:4-management`）；IBM MQ / Kafka / RocketMQ 的容器**已不存在**（不是停止，是删除），
要重做冒烟得先把对应容器起回来，并注意先停 RabbitMQ 否则抢 11207-11209 起不来。

### RabbitMQ（实测 2026-11-09）

**4.3.6，三节点真集群**（`rabbit@rabbit1` / `rabbit@rabbit2` / `rabbit@rabbit3`；
从任一管理端口查 `/api/nodes` 都能看到这三台）。冒烟账号 **`mqdev`**
（`~/.mqclient/config.toml` 里的 `rabbitmq-1` 就是它；管理 API 用 Basic Auth 返回 200）。

端口是**反的**，别按默认值猜：

| 节点 | 管理 HTTP（宿主） | AMQP（宿主 → 容器 5672） |
| --- | --- | --- |
| rabbit-1 | 11207 | **15672** |
| rabbit-2 | 11208 | 5673 |
| rabbit-3 | 11209 | 5674 |

`podman ps` 里写的是 `0.0.0.0:15672->5672/tcp` 这种形式 —— **左边是宿主端口**，
所以 rabbit-1 的 AMQP 在 15672（不是 5672），管理面在 11207（不是 15672）。

**外网只能到 11207-11209 与 15672。** 5673 / 5674 虽然也发布在 `0.0.0.0` 上，但被
防火墙挡住（实测外网 connect 超时），所以**从本机只能连 rabbit-1 的 AMQP**。要验证
集群行为请走管理 API（三个端口都通）。

上面四条客户端用例（heartbeat / session 泄漏 / 共享连接 / 部分扫描）都直接复用
config.toml 的这条连接；**其中两条在 broker 不在时会 FAIL 而不是 SKIP**，
详见文末「门禁与真机测试的关系」。

### IBM MQ

这就是上面提到的「四套共用 11207-11209」里的那一套（`ibmcom/mq`）：11207 = QM1（明文）、
**11208 = QM2（双向 TLS + 密码双因素）**、11209 = QM3。QM2 的通道
`DEV.ADMIN.SVRCONN` 要求客户端证书（`SSLCAUTH(REQUIRED)`，`SSLPEER('CN=mqclient,O=Dev')`）
+ 密码，套件 `ECDHE_RSA_AES_128_GCM_SHA256`；证书在服务器
`/opt/server/ibmmq/tls/`（`client.p12` / `trust.p12` / `ca.crt`），本机副本在
`~/Documents/mq/ibmmq/`（600，**不入库**）。`~/.mqclient/config.toml` 里已登记连接
`ibm-mq-qm2-tls`（口令密文落盘），所以 `PcfObjectScannerLiveTest` /
`PcfScanBudgetLiveTest` 在**停机时**会记 SKIP 而不是失败。

真机 TLS 用例：`IbmMqTlsLiveTest`（走正式连接路径 + 四条反向对照），参数一律
`-Dmqdiag.*` 传入，源码里没有任何凭据。

### RocketMQ

5.3.2 三节点（`/opt/server/rocketmq`，说明文件 `connect.txt`），TLS enforcing 单向认证
（客户端只需 PEM `ca.crt`，本机在 `~/Documents/mq/rocketmq/`），diag 冒烟账号
`reader-app`；`connect.txt` 含明文 ACL 密钥，**禁止入 git**。diag 用例需显式打开：
`-Drocketmqdiag.enabled=true -Drocketmqdiag.sk=...`，否则 SKIP。

> ⚠️ **NameServer 端口待确认。** 旧文档写「公网 15672」，但 15672 已实测是 rabbit-1 的
> AMQP；`config.toml` 里 `rocketmq-reader` 的 `namesrvAddr` 也指向它（9876 连不通）。
> 由于四套容器共用 11207-11209，RocketMQ 起起来后端口很可能也不是 15672 —— 下次启动
> 时用 `podman ps` 核对后把这一行改成实测值，不要再沿用旧数字。

本机无容器运行时，容器操作走 ssh。

### 门禁与真机测试的关系

`scripts/verify.sh` 跑全仓单测（`test` + `jvmTest`）。真机用例分两类，**行为不一致**：

- **无环境即 SKIP**（`assumeLive`）：IBM MQ / Kafka / RocketMQ / Anthropic 的全部 LiveTest，
  以及 RabbitMQ 的 `RabbitMqScanPartialLiveTest`、`RabbitMqSessionLeakLiveTest` 中走管理
  API 的那条、`RabbitMqSharedConnectionLiveTest`；
- **连不上就 FAIL**（直接 `factory.newConnection()`，没有兜底）：
  `RabbitMqHeartbeatLiveTest`、`AmqpThreadNamingProbeTest`，以及
  `RabbitMqSessionLeakLiveTest` 的 `liveSharedConnectionReleasesChannelsAndUnackedMessages` /
  `liveCloseDuringChannelOpenMustNotLeakChannel`。

所以 **RabbitMQ 停机时全量门禁会红**（实测 4 条失败），而 IBM MQ / Kafka / RocketMQ 停机
只是 SKIP。这是已知不一致，尚未统一——建议真机能力验证一律改成 SKIP，让门禁只反映代码问题。
