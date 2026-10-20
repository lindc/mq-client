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
- 嵌套 AlertDialog 必须声明在外壳的 content lambda 内（窗口归属，见 ObjectDetailDialog KDoc）。
- 敏感配置（password 等）经 `SensitiveOptionsSerializer` 自动加密落盘，模块内不做手工加密。
- 语义色约定：告警橙 `0xFFED6C02`、健康绿 `0xFF2E7D32`，不随主题翻转。

## 模块功能现状

- **IBM MQ**：连接管理、MQSC 控制台、对象扫描、消息浏览 / 发送 / 下载、AI 链路分析（最完整）。
- **Kafka**：连接 / 扫描、Topic 管理、消费组重置位移（earliest / latest / 按时间戳，含状态守卫）、消息读写、拓扑图、AI 链路（含消费组 lag 上下文）、diag 实连测试。
- **RabbitMQ**（阶段一 + 交换机管理已落地）：管理 API（Ktor + Basic Auth，vhost 按 `%2F` 编码）、显式连接、对象慢扫（5min）+ 队列计数快扫（10s）、概览 Dashboard（指标卡 + 健康度 Banner + Loading 骨架）、队列 / 交换机列表、交换机创建 / 删除 / 属性（**Bindings 增删查**：添加绑定弹窗 + 行内删除，`propertiesKey` 随列表下发用于 DELETE 路径；系统交换机默认 / amq.* 禁写）、拓扑图（Exchange → Binding → Queue 两列布局，双击 exchange 开属性）、AI 链路（CLUSTER → TOPIC → CONSUMER_GROUP lag 上下文）。
- **RabbitMQ 待做**：消息发布 / 消费（amqp-client 5.22.0 已引入未使用）、队列管理操作、拓扑 E2E 绑定边、Routing Key 边标签（待 kuiver 支持）。
- **RocketMQ**：连接表单（ACL + TLS：Truststore 收 JKS/P12 自动转 PEM、PEM CA 证书直接透传；Keystore 双向认证；密码框随文件联动禁用）、显式连接、对象双扫（慢扫 clusterInfo/topics/groups + 快扫消费组 Lag）、Topic 删除、拓扑图、diag 实连测试。TLS 坑位：`ClientConfig.useTLS` 构造时固化（必须 `setUseTLS` 显式传，start 时写 `tls.enable` 全局无效），`TlsSystemConfig` 证书字段在 start() 内 `buildSslContext` 才读取（窗口罩 start()）； PEM 证书若 `IOException` 客户端会静默降级明文，构造前必须预校验。

## 测试环境

chillbear 服务器（`ssh app@www.chillbear.top`）有 IBM MQ / Kafka / RabbitMQ / RocketMQ 容器可做实连冒烟；RabbitMQ 4.3.6 三节点集群管理端口 11207-11209（HTTP），mq-client 冒烟账号 `mqclient`。RocketMQ 5.3.2 三节点（`/opt/server/rocketmq`，说明文件 `connect.txt`）：NameServer 公网端口 **15672**（≠ broker 的 11207-11209），TLS enforcing 单向认证（客户端只需 PEM `ca.crt`，本机在 `~/Documents/mq/rocketmq/`），diag 冒烟账号 `reader-app`；`connect.txt` 含明文 ACL 密钥，禁止入 git。本机无容器运行时，容器操作走 ssh。
