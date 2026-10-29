# Changelog

本文件记录 MQClient 的显著变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased] — 2026-10-29 (22)

### Fixed

- **「消费进度」表与概览的 PropertyTable 视觉对齐；「组不在线」不再刷错误栈**
  （`rocketmq-ui` `RocketMqGroupPropertiesDialog`，`rocketmq-service` `RocketMqAdminClient`）。

  **表格**：这一页原来用 `labelMedium`（表头）+ `bodySmall`（数据），都是 12sp；行高
  只有 6dp 内边距（约 28dp）、每行统一 15% 底色（不是斑马纹）、行间还有
  `HorizontalDivider` —— 与同对话框的概览差一整档。现在逐参数对齐 `PropertyTable`：
  - 表头 → `bodyMedium` + `onSurfaceVariant`（= Key 列），数据 → `bodyMedium` +
    `onSurface`（= Value 列）；
  - 行高 `heightIn(min = 40.dp)` + `padding(horizontal = 10.dp, vertical = 8.dp)`；
  - 斑马纹：奇数行 `surfaceVariant@30%` + 4dp 圆角，偶数行透明；行距 8dp；
    删掉 `HorizontalDivider`；
  - 列宽 `2.5 / 2.5 / 1 / 1.8 / 1.8 / 1.4` 不动；`lag > 0` 的橙色保留，
    去掉 `fontWeight = Medium`（主题是单字重字体，伪粗反而糊）；
  - 取舍：数据格不再用等宽字体（PropertyTable 不暴露字体，两块必须一致）。

  **日志**：用户日志里那条 20 行堆栈
  ```
  IllegalStateException: CODE: 206 DESC: the consumer group[CG_PROGRESS_DEMO] not online
    at RocketMqAdminClient.mapError(...)  at ...fetchConsumerConnection(...)
  Caused by: MQBrokerException: CODE: 206 ...
  ```
  不是故障 —— 「组没有在线消费者」是完全正常的状态。根因是
  `fetchConsumerConnection` 的 catch **只接了 `MQClientException`**，而 broker 对
  「组不在线」回的是 `MQBrokerException`。漏网的异常进 `ioCall` 被包成
  IllegalStateException，再由 `bestEffort` 记成 WARN + 完整栈。
  现在两种异常形态都接：只有 206 / 17 当作预期状态吞掉，**权限与未知错误照常抛出**
  （那些才是需要留痕的）。

  测试：`RocketMqGroupProgressTableTest`（7 条，新：六列表头与数据渲染、表头渲染高度
  ≥ 19dp、行高 ≥ 40dp、斑马纹像素断言（奇行 alpha>0 / 偶行 alpha==0）、空态、
  兜底标注仍在）、`RocketMqConnectionErrorHandlingTest`（6 条，新：206/17 吞掉、
  权限与未知错误不吞、`MQClientException` 行为不回归、用户日志原报文落在已知档位）。
  负向对照：把表头退回 `labelMedium` 且把斑马纹退回统一 15% 底色后，两条用例立刻失败。

  **真实集群验证**：`CG_PROGRESS_DEMO` / `CG_NO_PROGRESS` / `CG_Normal` 三个组调
  `fetchConsumerConnection` 均返回 null，**stderr 干净**（修复前三条都打印堆栈）。

  **未验证**：真机 GUI 观感。

## [Unreleased] — 2026-10-29 (21)

### Fixed

- **消费进度提示分不清「组不存在 / 没人消费过 / 没权限」**（`rocketmq-ui` 新增
  `RocketMqProgressHint.kt`，`rocketmq-service` `fetchGroupConfigOrNull`）。

  用户反馈提示只说「无法获取该消费组的详细位点信息」，看不出到底哪种情况。查下来是
  两个问题叠在一起：

  1. **一个把 Kotlin 内部断言当中文提示的 bug**：`examineSubscriptionGroupConfig`
     对**不存在的组返回 null**（不是抛异常），而 `ioCall<T>` 的泛型上界非空，于是
     那句 null 被 Kotlin 的 `checkNotNull` 变成
     `ioCall(...) must not be null` —— 既不是中文也没说清发生了什么。
     真实集群实测：`NO_SUCH_GROUP_XYZ → ioCall(...) must not be null`。
     现在 `fetchGroupConfigOrNull` 把 null 当作**有意义的业务结果**（组不存在），
     与「查询失败」严格区分，新增 `RocketMqGroupDetailSnapshot.groupMissing`。
  2. **诊断信息没用上**：详情查询本来就同时拿到了「订阅组配置在不在」「有多少在线
     成员」「进度失败的归因」，但只用了最后一项。现在由纯函数 `progressHint`
     组合成六档：组不存在 / 无权限 / 从未有消费者上线 / CODE 17 / CODE 206 / 未知，
     每档一句自己的话。

  典型差异（同为 CODE 17）：组不存在 → 「该消费组不存在，名称可能拼错或已被删除」；
  组在但没有在线消费者 → 「该消费组还没有消费进度，消费者启动并完成一次拉取后就会有
  数据」；组内有成员 → 「重试队列在 NameServer 无路由」。概览 Tab 在组不存在时也会
  加一行红字说明，避免把「未知」误读成查询失败。

  i18n 中英同步（+5），其中 `rocketmq_ui_group_props_progress_denied` 是上一轮
  遗留的**未定义键**（界面上会显示裸 key）：`progressHintTitleKey` 这类「函数返回
  key」的写法绕过了 i18n 静态引用检查（脚本只认 `stringResource("字面量")`）。
  已补键，并新增一条渲染测试断言每一档都能取到真实文案。

  测试：`RocketMqProgressHintTest`（6 条：组不存在优先、权限、零成员、同一 CODE 17
  三种上下文必须给出不同结论、206/未知各归各档、每档文案真的存在），
  `RocketMqGroupProgressTabTest` 调整为 11 条（17 有成员 / 17 零成员 / 组不存在
  三种情形分开断言）。

  **真实集群验证**：`CG_PROGRESS_PROBE → config=CG_PROGRESS_PROBE`，
  `NO_SUCH_GROUP_XYZ → config=null（组不存在）`（修复前是 Kotlin 断言文本）。

  **未验证**：真机 GUI；ACL 分支仍无法在本地复现（该集群对宿主公网 IP 做了 ACL
  白名单，且 NameServer 路由查询先于 broker 的 ACL 检查）。

## [Unreleased] — 2026-10-29 (20)

### Added

- **消费进度：组级查询被 `%RETRY%` 路由挡住时，逐 broker 直读兜底**
  （`rocketmq-service` `resolveGroupProgress` / `RocketMqAdminClient.fetchConsumeStatsInBroker`）。

  上一轮把「为什么查不到」说清楚了（CODE 17 / 206），但还有一种真实场景会丢数据：
  **位点确实存在，组级查询却不可用**（重试队列被删、POP 模式、broker 还没建重试队列）。
  此时 `examineConsumeStats(group)` 先查 `%RETRY%<group>` 的路由就失败，而 broker 的
  ConsumerOffsetManager 里那些位点还在。

  做法：组级失败且分类为 `RETRY_TOPIC_NO_ROUTE` / `NO_CONSUME_STATS` 时，逐 broker 调
  `fetchConsumeStatsInBroker`（**完全不查路由**），按组过滤、去重、排序后作为结果返回，
  新增 `RocketMqProgressSource.BROKER_FALLBACK` 标注来源，UI 在表格上方加一行说明
  「位点由 Broker 直读取得」—— 否则用户会疑惑「刚说查不到，怎么现在又有数了」。

  边界（都写进代码注释与测试）：
  - **只在 17 / 206 兜底**。权限 / 超时 / 未知失败再查一遍没有意义，反而会把一次超时
    放大成 N 次 broker 请求；
  - 兜底查到的行**为空就保持原降级态** —— 「跑过兜底」不等于「有数据」；
  - **不按 topic 猜**：`examineConsumeStats(group, topic)` 对从未订阅该 topic 的组也
    返回整张队列表（实测把一个组的 12 个全 0 队列当成它的进度），那是在编数据；
  - 并发逐 broker、单个 5 秒上限，单 broker 失败只少它自己那份。

  结构上把策略抽成 `resolveGroupProgress(...)`（依赖全部注入）+ 纯函数
  `brokerStatsToRows`，因此 `fetchGroupDetail` 之外也能单测 —— 它本来要连模块注册表
  才能跑。

  测试：`RocketMqGroupProgressResolveTest`（10 条：组级成功不碰 broker、17 有数据即恢复、
  17 无数据保持降级、206 同样兜底、权限不兜底、超时不兜底且文案独立、未知失败带摘要、
  单 broker 失败只少自己、地址查询失败降级、取消原样上抛）、
  `RocketMqBrokerStatsRowsTest`（5 条：只留本组、去重、lag 非负、排序、空响应）、
  `RocketMqGroupProgressTabTest` +2（兜底数据带来源标注 / 常规路径不带）。
  负向对照：去掉兜底后 3 条用例失败。

  **真实集群验证**（chillbear，用 `resetOffsetByTime` 造出「有 offset 但无 retry 路由」
  的 `CG_PROGRESS_PROBE`，未删除任何 topic）：
  ```
  RESOLVE CG_PROGRESS_PROBE -> state=OK  source=BROKER_FALLBACK rows=12
  RESOLVE CG_Normal         -> state=RETRY_TOPIC_NO_ROUTE rows=0
  RESOLVE CID_RMQ_SYS_TRANS -> state=OK  source=GROUP rows=2
  ```

  **未验证**：真机 GUI；`CG_PROGRESS_PROBE` 与它的 12 条测试位点留在集群上（未清理），
  方便你在客户端里直接复现，清理命令见交付说明。

## [Unreleased] — 2026-10-29 (19)

### Fixed

- **RocketMQ 消费组「消费进度」Tab 查不到数据，且界面给不出原因**（`rocketmq-service`
  新增 `RocketMqProgressFailure.kt`，`RocketMqScanService.fetchGroupDetail`、`rocketmq-ui`
  `RocketMqGroupPropertiesDialog` 的 `ProgressTab`）。

  **具体的异常是 CODE 17**（在真实集群上抓到的）：

  ```
  $ mqadmin consumerProgress -n rmq-node1:9876 -g CG_Normal
  Caused by: MQClientException: CODE: 17  DESC: No topic route info in name server
             for the topic: %RETRY%CG_Normal
  ```

  即 `%RETRY%<group>` 在 NameServer 没有路由 —— 组从未消费过（Broker 还没建/上报
  重试队列）。同一集群上还有第二种形态：`chillbear-test-group` 返回 **CODE 206**
  （offset 表为空，没有消费者上线过）。

  **根因不是没捕获异常，而是捕获后把异常类型弄丢了**：`RocketMqAdminClient.ioCall`
  为让 UI 直接显示中文，把协议异常统一包成 `IllegalStateException`（实测三层：
  `IllegalStateException → IllegalStateException → MQClientException`）。于是
  `fetchGroupDetail` 里那句 `e is MQClientException && e.responseCode == 17` **永远为
  false** —— CODE 17 的专属文案是死代码，所有失败一律落进 `FAILED`，界面上只剩一句
  「消费进度查询失败，可点击重试，或检查服务端连接与权限」。

  - 新增 `rocketMqResponseCode(error)`：**沿 cause 链**找 `MQClientException` /
    `MQBrokerException` 的 responseCode（带深度上限，防 cause 成环挂死）；
  - 新增 `classifyProgressFailure(error)`：17 → `RETRY_TOPIC_NO_ROUTE`、206 →
    新增的 `NO_CONSUME_STATS`、ACL 措辞 → 新增的 `NO_PERMISSION`、其余 → `FAILED`
    并带上原始摘要；
  - 超时：进度查询包 `withTimeout(5000L)`。**诚实的边界**写在常量注释里 ——
    `examineConsumeStats` 是阻塞调用，`withTimeout` 只让协程不再等，线程仍会卡到
    AdminClient 的 socket 超时；它保证的是「UI 不永久卡死」。带 timeout 的重载要求
    指定 broker+topic，做不了「按组查全部」；
  - 日志：进度失败打 `state` + `code` + **完整异常栈**（原来只有 `message`，CODE 与
    调用链都丢了）；`config` / `conn` 两个子查询从 `runCatching` 改成显式 try ——
    `runCatching` 会连 `CancellationException` 一起吞掉，对话框关掉后查询还会继续跑；
  - UI：`ProgressWarningBanner` 按状态分档给文案（17 / 206 / ACL / 未知各一句），
    未知失败把 `progressError` 直接显示成「获取消费进度失败：<原始摘要>」；
    「重试」按钮四种状态都有（重试路径本身原本就是好的，未改动）。

  i18n 中英同步（+3）：`rocketmq_ui_group_props_no_stats_sub` /
  `_permission_sub` / `_failed_detail`。

  测试：`RocketMqProgressFailureTest`（9 条，报文逐字取自真实集群：17 / 206 /
  MQBrokerException(17) / ACL 措辞 / 未知失败保留摘要 / 深层 cause 链 / cause 成环）、
  `RocketMqGroupProgressTabTest`（7 条，各状态的界面文案 + 未知失败显示原始错误 +
  每态都有重试 + OK 态渲染表格）。负向对照已验证：把 `rocketMqResponseCode` 退回
  「只看最外层类型」，4 条分类用例失败。

  同一处缺陷还有第三个受害者：`audited { }` 的审计日志里 `reasonCode` 也是直接
  `e as? MQClientException` 取码 —— 同样永远 null，所有写操作（建/删主题、建/删组、
  重置位点）失败时审计记录的 errorCode 一直是空的。一并改成 `rocketMqResponseCode(e)`。

  **已用真实集群验证**（一次性探针，跑完已删）：`CG_Normal` / `CG_Broadcast` →
  `RETRY_TOPIC_NO_ROUTE (code=17)`，`chillbear-test-group` → `NO_CONSUME_STATS (code=206)`，
  `CID_RMQ_SYS_TRANS` → `OK rows=2`。

  **未验证**：真机 GUI（对话框里的实际观感）；ACL 拒绝分支没有在真实集群上复现
  （NameServer 的路由查询先于 broker 的 ACL 检查，denied-app 也先拿到 CODE 17）——
  该分支只由单元测试覆盖。

## [Unreleased] — 2026-10-29 (18)

### Changed

- **RocketMQ 主题属性对话框「路由信息」表头对齐概览的 PropertyTable**
  （`rocketmq-ui` `RocketMqTopicPropertiesDialog` 的 `RoutesTab`，顺带 `kafka-ui` 分区表）。

  现场反馈是「表头和数据字体都偏小」，实际只有**表头**偏小：数据行早就是
  `bodyMedium` + `onSurface`，还已经带了 `heightIn(min = 40.dp)`、4dp 圆角、
  奇数行 `surfaceVariant@30%` 斑马纹、10/8 内边距、8dp 行距 —— 三张自绘表里它反而是
  最接近 PropertyTable 的一张。真正差档的是表头 `labelMedium`（12sp）对
  `bodyMedium`（14sp）。

  - 表头 → `bodyMedium` + `onSurfaceVariant`，即 PropertyTable 的 **Key 列**那一档；
    层级差靠颜色（Key 次要色 / Value 主色）表达，两边字号一致。表头内边距 4dp → 8dp、
    补 `verticalAlignment = CenterVertically`、与首行之间加 8dp 间隔，节奏与数据行对齐；
  - 数据行视觉不变，只把五格里缺 `maxLines`/`overflow` 的四格补齐（`brokerName` 本来就有）；
    Master 的 primary 着色保留；
  - 列宽 `2f / 1f / 1f / 1.2f / 1.2f` 一个不动；
  - **同步修正 kafka-ui 的 `PartitionHeaderCell`**（上一轮我把它设成了 `labelMedium`）——
    否则三张表里两张 14sp、一张 12sp。RabbitMQ 绑定表上一轮已经是 14sp，三处现在同档。

  测试：`RocketMqRoutesTabTest`（6 条，新）—— 表头/数据渲染、**表头渲染高度 ≥ 19dp**
  （bodyMedium 行高 20dp vs labelMedium 16dp，阈值能干净分开）、行高 ≥ 40dp、
  斑马纹像素断言、空态。负向对照已验证：把表头临时改回 `labelMedium`，该条实测
  16.0dp 并失败。

  **未验证**：真机观感 —— 同字号下仅靠颜色区分表头，深色主题里层级够不够清楚。

## [Unreleased] — 2026-10-29 (17)

### Changed

- **RabbitMQ 交换机属性对话框：绑定表视觉对齐上半部分的 `PropertyTable`**
  （`rabbitmq-ui` `RabbitMqExchangePropertiesDialog` 的 `BindingsTable`）。

  同一个对话框里两块表格差了一整档：上面是 `PropertyTable`（bodyMedium / 40dp 行高 /
  斑马纹 / 4dp 圆角），下面是手写的 bodySmall + 6dp 上下内边距 + 0.5dp 分割线
  （无操作列的行约 28dp，带 24dp 垃圾桶按钮的行 36dp —— 渲染测试实测）。

  - **字体**：数据 `bodySmall` → `bodyMedium` + `onSurface`（= PropertyTable 的 Value 列）；
    表头 `labelMedium` → `bodyMedium` + `onSurfaceVariant`（= 同一张表的 Key 列），
    即「表头用键样式、数据用值样式」这对既有配对，两块表格的字号/字重/颜色现在同源；
  - **行高**：`heightIn(min = 40.dp)` + `padding(horizontal = 10.dp, vertical = 8.dp)`，
    与 PropertyTable 行同一组参数；水平 10dp 也让它与上面的文字左边缘对齐（外层
    Column 已有 20dp，叠加后都是 30dp）；
  - **斑马纹**：奇数行 `surfaceVariant@30%` + 4dp 圆角，偶数行透明；行距 8dp
    （`spacedBy`），去掉行间 0.5dp 分割线 —— PropertyTable 靠斑马纹分行，留着分割线
    会变成双层分隔；
  - **不动**：列宽（Destination 1.4f / Routing Key 1.2f / Arguments 1f / 操作 56dp）、
    删除垃圾桶按钮、`[E]` 前缀、无权限时隐藏操作列。
  - 取舍：数据格不再用等宽字体（`PropertyTable` 不暴露字体，两块必须一致）。

  测试：`RabbitMqBindingsTableTest`（4 条，新）—— 表头/数据渲染、行高 ≥ 40dp、
  斑马纹像素断言（斑马行 alpha > 0 / 普通行 alpha == 0）、无权限时无操作列。
  负向对照已验证：把 `heightIn` 与斑马纹临时摘掉，测试分别在 36dp 与 alpha=0 处失败。
  另给 `rabbitmq-ui` 的 `jvmTest` 补 `compose.desktop.currentOs`（无头渲染需要
  Skia 原生运行时，与 kafka-ui / rocketmq-ui 同一写法）。

  **未验证**：真机 GUI 观感（斑马纹对比度、长 Arguments 文本的省略表现）。

## [Unreleased] — 2026-10-29 (16)

### Fixed

- **Kafka 消费组页「消费组加载超时」**（`kafka-service` + `kafka-ui`）。

  现场：进消费组页几秒后报 `消费组加载超时，请点击刷新重试`，集群明明正常。

  根因有两层，都不是「网络慢」：

  1. **UI 只等 5 秒，而且等的是整轮对象慢扫**（`KafkaConsumerGroupsState`
     的 `withTimeoutOrNull(5000)`）。那一轮是 brokers + topics + groups 三类并发，
     topic 多的集群上 topics 一批就超过 5 秒 —— 组早就查完了，界面已经先报超时。
  2. **Lag 是逐组串行的两次往返**（旧 `computeOneGroupLag`：每组一次
     `listConsumerGroupOffsets` + 一次 `listOffsets`），200 个组 = 400 次往返；外层
     `withTimeout(3000)` 还是**假守卫** —— `future.get` 不可被协程取消，超时抛了异常、
     线程照样卡住。

  修法：
  - **超时分级**（`KafkaConfig`）：消费组列表 20 秒档（`GROUP_LIST_TIMEOUT_MS`）、
    Lag 30 秒档（`GROUP_LAG_TIMEOUT_MS`），都取「用户配置 vs 下限」的较大值；
    AdminClient 的 `request.timeout.ms` 增加 30 秒下限（`MIN_ADMIN_REQUEST_TIMEOUT_MS`）
    —— 它同时是全部 Admin 请求的上限，用户把「连接超时」填成 3 秒曾让正常请求注定失败
    （连接验证本身仍按用户值传参）。
  - **批量而非串行**：`scanKafkaGroupLags` 用**一次** `listConsumerGroupOffsets(全部组)`
    \+ **一次** `listOffsets(全部分区)` 覆盖所有组，`partitionsToOffsetAndMetadata(groupId)`
    只是同一次响应的本地按组切分 —— 2 次往返取代 2N 次。
  - **真异步**：新增 `KafkaFutures.awaitKafka`（`toCompletionStage` + 挂起），
    不再用阻塞的 `future.get`；`withTimeout` 现在真能打断，`Semaphore(8)` 随之删除
    （没有需要节流的阻塞点了）。
  - **定向查询代替等整轮**：新增 `KafkaScanService.refreshGroups`（只调
    `listConsumerGroups` + 批量 `describeConsumerGroups`，写缓存即返回，不进扫描锁），
    消费组页首屏与「重试」直接走它 —— 不再被 topics 慢扫拖住。
  - **逐组 / 逐分区异常隔离**：单组 describe / offsets 失败（无权限、组刚消失、
    协调器无响应）只跳过该组并记日志，不再拖垮整张列表；单分区查不到末端位移按 lag=0。
  - **UI 兜底**：等待上限 5s → 20s（不小于服务层的列表档，避免 UI 比底层先放弃）；
    文案改柔和为「加载消费组耗时较长，请稍候或点击重试」；超时不判死 —— 慢扫若稍后真的
    完成，事件仍会把提示换成真实列表；「重试」重走首屏路径而不是干等下一轮。
    另修一条真实缺陷：`onStatusChanged(Connected)` 以前只读缓存，隐式连接完成后首屏会
    永远停在 Loading（旧行为正是靠那个 5 秒超时"兜底"看起来才没卡死）。
  - **Lag 占位**：卡片没有 lag 时显示「Lag: 计算中」，不再整块留空（空着读起来像
    零积压）。

  i18n 中英同步：`kafka_ui_group_load_timeout` 改文案，新增 `kafka_ui_group_lag_pending`。

  测试：`KafkaFuturesTest`（6 条，真实 `KafkaFutureImpl`：正常完成 / 超时可打断 /
  超时不杀协程 / 解 `ExecutionException` 露出根因 / 外层 withTimeout 生效 /
  并发等待互不阻塞）、`KafkaGroupTimeoutPolicyTest`（4 条，分级与下限）、
  `GroupCardAdapterTest`（4 条，「计算中」与真实 0 值要区分）。
  顺带把 `KafkaErrorTextTest` 改成断言纯函数 `kafkaErrorKey` —— 它原先是靠「语言表未
  加载」这个进程级隐式前提跑的，新增的卡片测试一旦先加载语言表就按执行顺序变红
  （实测踩到），现在与语言表状态无关。

  **未验证**：真机大集群（几百组 / 上千分区）的实际耗时。本地到编译 + 单测 + i18n +
  分层检查；`verify.sh` 的 `:rabbitmq-service:jvmTest` 仍因本机配了 rabbitmq 连接但
  broker 未运行而失败。

## [Unreleased] — 2026-10-29 (15)

### Fixed

- **消息列表 Value 列整格空白 —— 首行无可见字形**（Kafka + RocketMQ，公共函数下沉到
  `message-interpreter-api`）。

  现场（Kafka）：同一批 XML 消息里 `Offset 6` 正常显示 `<wpf:ResourceDictionary…`，
  `Offset 5` 整格空白，像没读到数据。

  根因不是编码失败，也不是二进制判定，而是**单元格只画第一行**：列表用
  `Text(maxLines = 1, overflow = Ellipsis)`，而原预览就是
  `String(bytes, 0, 256, UTF_8)` 原样返回 —— 正文以 `\n` / `\r\n` / 空格 / UTF-8 BOM
  开头时，第一行是空的，单元格一个像素都不画（实测 `"\n\n<wpf:…"` 渲染 ink=0，
  去掉前导换行后 ink=1119）。排查方向里的另两条都不成立：二进制命中时走 hex
  分支（非空）；非 UTF-8 容错解码产出的是 `\uFFFD` 替换符（有字形，显示成 `���`）。

  修法：
  - `message-interpreter-api` 新增 `MessagePreviewText.kt`：`previewTextOf(bytes)` /
    `singleLinePreview(raw)` —— 去 BOM 与零宽字符、空白与控制字符**折叠**成一个空格
    （不是删，否则 `</a>\n<b>` 会拼成 `</a><b>`）、首尾去空，预览永远以可见字符开头。
    放这里而不是某个 `*-service`：Kafka 与 RocketMQ 的 `previewOf` 曾是逐字相同的
    两份副本，同一个盲区要修两遍；`isProbablyBinary` / `hexPreview` 已在此处；
  - Kafka / RocketMQ 两个 `ValueCell` 四档兜底，绝不剩白格：0 字节（含 Kafka 的
    tombstone）→ `[空消息]`；疑似二进制 → hex；预览全不可见（纯空白 / 全零宽）→
    `[不可见内容] + hex`；否则原文；
  - 顺带修 Kafka `toSummary` 两个同路径隐患：`value()` 为 null 的 tombstone 原来会在
    `previewOf` 里 NPE（整页读取挂掉），现在按空消息处理；`serializedValueSize()`
    对 null 返回 -1，统一 `coerceAtLeast(0)`，UI 才能靠 `valueSize == 0` 判定。

  i18n 中英同步（+2 ×2 模块）：`kafka_ui_msg_browse_empty_value` /
  `kafka_ui_msg_browse_invisible_value`、`rocketmq_ui_msg_browse_empty_value` /
  `rocketmq_ui_msg_browse_invisible_value`。

  测试：`MessagePreviewTextTest`（9 条，`message-interpreter-api`）—— 前导换行 / BOM /
  零宽、折叠而非删除、全不可见→空串、非法字节出替换符、字节预算截断、普通文本不动；
  `KafkaMessageValueCellTest` / `RocketMqMessageValueCellTest`（各 6 条）—— 对单元格
  截图数非背景像素：普通文本、前导换行 XML、BOM XML、全不可见、零字节、二进制六种
  输入都必须有墨迹（修复前前导换行那档实测 ink=0，已作为负向对照验证）。

  `rocketmq-ui` 的 `jvmTest` 补了 `compose.desktop.currentOs`（无头渲染需要 Skia 原生
  运行时，与 `kafka-ui` 同一写法）。

  **未验证**：真机 GUI 观感。本地到编译 + 单测 + i18n + 分层检查；`verify.sh` 的
  `:rabbitmq-service:jvmTest` 仍因本机配了 rabbitmq 连接但 broker 未运行而失败。

## [Unreleased] — 2026-10-29 (14)

### Changed

- **Kafka 主题属性对话框「分区」表对齐 PropertyTable，消息浏览页的拉取条数换成紧凑输入框**
  （`kafka-ui` `KafkaTopicPropertiesDialog` / `KafkaMessageBrowseTab`）。

  分区表原来是手写 `bodySmall` + 无背景 + 0.5dp 分割线（行高约 32dp），与隔壁「配置」
  Tab 的 `PropertyTable` 明显不是一套东西。现在：
  - 数据行 `heightIn(min = 40.dp)` + 斑马纹（奇数行 `surfaceVariant@30%`）+ 4dp 圆角 +
    行距 8dp，**去掉全部 `HorizontalDivider`**；
  - 正文 `bodySmall` → `bodyMedium`，表头 `bodySmall/Medium` → `labelMedium` + 次要色
    （与 RocketMQ 路由表同款）；
  - 六列权重不变、全部左对齐；Leader 缺失仍用 error 色；
  - 代价：数字列失去 Monospace（`PropertyTable` 不暴露字体），与概览/配置两页一致。

  消息浏览页的「拉取条数」从 m3 `OutlinedTextField`（默认 56dp）换成 `ui-common` 的
  `CompactTextField`（无 label 时 40dp），宽度 80dp；OFFSET 模式的位移框同换（原来两者
  共用同一个私有 `OutlinedSmallField`，只改一处会留下第二个 56dp 错位）。顺带修了
  `OutlinedSmallField` 自己的宽度 bug：调用方传 84dp、函数内部又追加 140dp，实际生效的
  是前者，两个数字都写错了地方。

  测试：`KafkaTopicPropertiesTabsTest` 增 3 条（分区表表头/行值 + 行高 ≥ 40dp、斑马纹像素
  断言、空态），`KafkaMessageBrowseToolbarTest` 新增 2 条（条数框 = 40dp 且与「读取」
  按钮等高 < 1dp；OFFSET 模式位移框与条数框等高）。为使 Tab/Rows 可测，`PartitionsTab` /
  `StartSelectorRow` / `StartMode` 由 `private` 放宽到 `internal`，两处输入框带 testTag。

  **未验证**：真机 GUI 观感（斑马纹对比度、680dp 最小宽度下六列是否挤、输入框与按钮
  的实际贴合）。本地只到编译 + 单测 + i18n；`verify.sh` 的 `:rabbitmq-service:jvmTest`
  仍因本机配了 rabbitmq 连接但 broker 未运行而失败（与本次改动无关，依赖图上不相交）。

## [Unreleased] — 2026-10-29 (13)

### Changed

- **Kafka 主题卡片收拢破坏性操作，属性对话框「概览 / 配置」改用 `PropertyTable`**
  （`kafka-ui` `TopicListPanel` / `KafkaTopicPropertiesDialog`）—— 向 RocketMQ 主题页对齐。

  卡片右下角的垃圾桶其实是「清空消息」（`deleteRecords` 把各分区位移起点推到末端），
  不是「删除主题」—— 删除主题一直只在属性对话框底部。直接删图标会让「清空消息」
  在整个 UI 上失去入口，所以**连同确认框一起搬进属性对话框底栏**：
  - 卡片操作区只剩 `AI / ✉️ / ⓘ`（与 `RocketMqTopicsPanel` 逐项同构）；
  - 属性对话框底栏：内部主题照旧隐藏两个 danger 按钮；否则「清空消息」在左、
    「删除主题」在右（破坏性递进），互斥禁用（任一开始执行后两者都不可点）；
  - 清空成功 → Snackbar 报分区数 → `load()` 重拉位移，概览的「消息总数」不回落在旧值上。

  两个 Tab 废弃手写 Row/Column，改为 `ui-common` 的 `PropertyTableSection`：
  - **概览**：基础信息（主题名 / 内部主题）、分区配置（分区数 / 副本因子）、
    统计（消息总数）三组；加载中的消息总数显示 `…` 且 `copyable = false`（否则复制到的是省略号）。
  - **配置**：常用配置 / 非默认覆盖项两段保留，全部改为 `PropertyItem(key = 配置名, value = 配置值)`。
    原先「默认值标灰」的语义 `PropertyItem` 没有颜色通道，改用 `description = 默认值` 小字说明。

  i18n 中英同步（+6）：新增 `kafka_ui_topic_props_overview_name` / `_internal_label` /
  `_section_basic` / `_section_partitions` / `_section_stats` / `kafka_ui_topic_props_configs_default_label`。
  内部主题标签**不能**复用 `kafka_ui_topic_internal` —— 英文表里它是小写 `internal`，做行标签会读成 `internal  Yes`。
  清空消息的按钮文案复用卡片原本的 `kafka_ui_topic_clear`（键未变，只是调用点从卡片移到对话框）。

  测试：`KafkaTopicPropertiesTabsTest`（6 条，新增）—— 无头 Compose 渲染两个 Tab：
  三组标题与字段值、内部主题走「是/否」、行尾复制按钮存在、加载中不复现复制按钮、
  配置页分组 / 默认值小字 / 空态 / 失败态。两个 Tab 由 `private` 放宽到 `internal` 仅为可测。

  **未验证**：真机 GUI 实操（卡片图标排布、斑马纹观感、长配置表滚动）。本地只到
  `assemble + jvmTest + i18n`；全仓 `verify.sh` 的 `:rabbitmq-service:jvmTest` 有 4 条
  既有 Live 探针因本机 `~/.mqclient/config.toml` 存了 rabbitmq 连接、而 broker 未运行
  连不上而失败（与本次改动无关：rabbitmq-service 不依赖任何 kafka 模块）。

## [Unreleased] — 2026-10-29 (12)

### Changed

- **dmpmqmsg 的「导入」从消息详情对话框移到队列消息处理对话框**（`ibm-mq-ui`
  `MessageDetailDialog` / `DmpmqmsgImport.kt` / `QueueMessagesDialog` / `MessageBrowseTab`）。

  放错了层级的不是按钮位置，是**动作的作用域**：消息详情看的是一条**已存在**的消息，
  而导入是往队列里发一批**新的**消息 —— 两者没有因果关系。用户在详情里看到「导入」，
  直觉会以为是「把这条消息导入到别处」，而实际行为与屏幕上这条消息毫无关系。

  现在：
  - 详情对话框底栏只留「导出 dmpmqmsg / 下载消息 / 复制正文」+ 关闭
    （净减 113 行；`onImported` 回调随之删除，已无调用方）。
  - 「导入 dmpmqmsg」进「浏览消息」工具栏（`ToolbarButton`，导入中禁用），
    与「刷新」同排。
  - 导入状态（`DmpmqmsgImportState`）挂在**对话框层**，与 `SendMessagesState` 同级：
    切到「发送消息」Tab 再切回来，进行中的导入不会被打断（Tab 会被销毁，状态不能跟着走）。
  - 导入成功 → `browseReloadKey++` → 浏览 Tab 就地 `refresh()`，新消息立即可见
    （新 MessageId 原列表里没有）。

  导入语义从「预览里选一条、发那一条」改为**整批**：这与「导入一个 dmpmqmsg 文件」的
  本意一致，也是验收要求的「确认后把这批消息批量发送」。为避免「我选了第 3 条，结果全
  导入了」这类误解：
  - 预览框新增一行范围说明：「确认后把这 N 条消息全部发送到队列「X」」；
  - 多条时的下拉降级为**查看器**（只切换预览哪一条，不改变导入范围）。

  批量发送抽成纯函数 `importDmpmqmsgMessages(messages, onProgress, send)`：逐条发送并汇总
  `sent / failed / firstError`，**一条失败不中断整批**（用户要的是「尽量都进去」），
  取消原样抛出（调用方靠它结束「导入中」状态）。反馈文案按结果分三种：
  `成功导入 N 条消息` / `成功导入 N 条，失败 M 条` / `导入失败：<第一条原因>`，
  进行中显示 `正在导入 n / N…`。

  解析与文件选择复用既有实现（`DmpmqmsgFormat.parse` + FileKit `pickFile`），未改动格式层。
  唯一的取舍：`pickFile` 按验收要求过滤了扩展名（`txt` / `json`），因此**无扩展名或 `.dmp`
  后缀的导出文件不在默认列表里**（仍可手动输入文件名）；需要放开是一行改动。

  关闭拦截相应泛化：`ConfirmCloseWhileSendingDialog` → `ConfirmCloseWhileBusyDialog(message)`，
  发送中 / 导入中各有措辞（`ibm_mq_msg_send_close_confirm` / `ibm_mq_msg_import_close_confirm`）。
  关闭**仍然没有任何 `enabled` 门禁**，理由同详情对话框：渲染 / 发送可以慢，退出不能慢。

  i18n 中英同步（+7 −2）：新增 `ibm_mq_msg_import_button` / `_all_hint` / `_progress` /
  `_result` / `_result_partial` / `_close_confirm` / `_preview_heading`；移除
  `ibm_mq_msg_detail_import_dmpmqmsg`（按钮已搬走）与 `ibm_mq_msg_import_success`
  （它只报单条 MessageId，整批之后由 `_result*` 取代）。

  测试：`DmpmqmsgImportBatchTest`（6 条，新增 —— 全成功计数、失败计数与首因保留、
  失败后继续尝试、进度序列、空批次不调 sender、取消原样抛出）。

  **未验证**：GUI 实操。只到编译 + 单测，导入按钮的实际位置、预览框排版、进度刷新节奏
  需要真机跑一次。

## [Unreleased] — 2026-10-29 (11)

### Fixed

- **消息详情对话框打开后永久停在「正在加载消息…」，点关闭没反应；但拖一下窗口或改窗口
  大小，数据就突然全出来了**（`ibm-mq-ui` / `kafka-ui` 的 MessageDetailDialog +
  `ui-common` 公共详情组件）。

  这条不是「MQ 慢」。现场日志（`~/.mqclient/logs/mqclient.log`，同一条消息连开四次）
  把两段时间并排放着：

  ```
  11:06:32.932  msg detail start
  11:06:33.002  msg detail completed  duration=68ms      ← 数据 68 毫秒就回来了
  11:06:43.322  detail loaded         total=2773ms       ← 界面 7.5 秒后才开始渲染
  ```

  四次打开的空档分别是 `7.55s` / `0.055s` / `0.98s` / `1.31s` —— 忽快忽慢，是调度
  而不是 I/O。同期后台每 ~3.5 秒一轮 `pcf scan [q-statuses] count=70 duration=2314–2582ms`
  与 `scheduled scan (messages) queues=70 messages=266`。

  根因在窗口管线本身：详情框是 nucleus/Tao 自绘窗口（RabbitMQ 侧那份埋点里
  `awtWindowCount()` 一直是 `windows=0` 就是旁证），**主循环只在收到原生窗口事件时
  推进**。协程从后台 resume 回界面调度器时排进的那个队列，主循环不自己来取 —— 要等
  下一次原生事件。这同时也解释了「关闭没反应」：✕ 的处理虽然同步执行了，但「移除这个
  原生窗口」同样排在同一条队列后面。`ui-common` 的 KDoc 里记着同一条结论的 51 秒原始
  现场（`1ms 的活等了 51 秒`）。

  上一版（`903d06f`）的修法方向对、落点没在这条队列上：

  1. **按三态各 new 一个 `ObjectDetailDialog`** —— 每翻一次状态都销毁一个 Tao 原生
     窗口再建一个新的。Ready 之后还得再等一次窗口创建 + 组合（就是那 7.5 秒），而且
     窗口一销毁就把 `LaunchedEffect` 的协程一起取消了。日志里 `11:07:29.895` 的
     `msg detail start` 没有对应的 `completed`、2.76 秒后同一 messageId 又来一次
     `start` —— 同一条消息被取了两次，就是这个结构造的。
  2. **`withTimeout(5000)` 从来没生效过** —— 它杀不掉阻塞的 MQI 调用，而超时取消的
     投递本身也要走那条被饿住的队列。所以不是「5 秒后报错」，而是永久停在加载。

  现在：**一个窗口、壳内切三态**（与仓库里 `MqObjectPropertiesDialog` 的既有范本
  一致）。`ui-common` 拆出 `MessageDetailUiState`（状态与渲染结果不再随窗口重建丢失）、
  `MessageDetailContent`（内容）与 `RowScope.MessageDetailFooterActions`（下载 / 复制 /
  关闭）；旧的一行接入 `MessageDetailDialog` 保留签名，RabbitMQ 不受影响。IBM 与
  Kafka 两个宿主都改为单窗口，取数直接调用（两个 service 内部本来就
  `withContext(Dispatchers.IO)`，外面再包一层只是多一层调度）。

  关闭**仍然没有任何 `enabled` 门禁**：渲染、下载可以慢，退出不能慢。
  真正的底层边界留在 service 里（`MQGMO_NO_WAIT` + `OP_SCAN_BUDGET_MS` 扫描预算 +
  `CancellationException` 优先重抛 + `finally` 关句柄）。

  两个宿主都补了 `[ui.detail]` 四拍埋点，下次不必从猜开始：

  ```
  detail opened:          sinceCompose=0ms windows=N
  state -> Loading (from=Ready)
  state -> Ready (from=Loading) elapsed=68ms windows=N
  detail loaded total / detail disposed lifetime
  ```

  判读：`elapsed` 小但界面仍停在加载 → 瓶颈在「回组合」，`windows` 随开关次数上涨 →
  结构修复失效。

  测试：`MessageDetailUiStateTest`（7 条，新增 —— 钉住「未超限不复制数组」（「转圈
  不停」那个老 bug 的根）、超限截断但 `rawBody` 完整、二进制默认 hex、切解释器重置
  参数、重复选中不清用户参数）；`MessageDetailPipelineTest`（4 条）保持全绿。

  **未验证**：GUI 实际行为。改动只到「编译 + 单测 + 结构审查」，卡死是否真的消失、
  `windows` 是否恒定，需要真机打开一次详情看新的 `[ui.detail]` 段落。

- **七类简单对象页的搜索框与刷新按钮直接显示 i18n key 本身**（`ibm-mq-ui`
  `SimpleMqObjectListPanel`，主题 / 订阅 / 服务 / 进程定义 / 名称列表 / 认证信息 /
  通信信息）。

  现象是界面上写着 `topic_refresh` / `topic_search_placeholder` 这种裸 key。根因不是
  漏调 `stringResource` —— 它调得很规范，键是**运行期拼出来的**：
  `stringResource("${spec.i18nPrefix}_refresh")`。而 `9571216` 给模块私有键统一加
  `ibm_mq_` 前缀时，字面量调用点都跟着改了，唯独这张 spec 表漏了（那个提交的文件
  清单里没有 `SimpleMqObjectListPanel.kt`）。于是拼出 `topic_refresh`，而表里只有
  `ibm_mq_topic_refresh`，`stringResource` 查不到就原样返回 key。

  影响面比报出来的大：6 类键 × 7 页 = **42 个**（`_search_placeholder` / `_refresh` /
  `_loading` / `_load_failed` / `_scan_failed` / `_action_properties`），属性按钮的
  tooltip 也在其中，只是平时不悬停看不到。中英两张表本身不缺键（各 1154 条，脚本
  核对过），只改前缀即可。

  `scripts/check_i18n_refs.py` 只扫字面量与 `tooltip = "…"`，扫不到插值 —— 这次回归
  因此一路绿灯。新增 `SimpleMqObjectI18nKeysTest`（2 条）按**与面板完全相同的派生
  规则**逐键核对两种语言，缺一个就红（已反向验证：把 `TopicSpec` 前缀改回 `"topic"`
  立即失败）。

- **顶部 Tab 栏在 Tab 溢出时无法用鼠标翻到后面**（`app-ui` `MainWindow`）。

  现状其实已经是 `Row + Modifier.horizontalScroll(...)`，不是普通 `Row` —— 真正缺的
  是另外两件：没有自动定位（新建 / 激活的 Tab 可能在视口外），以及桌面端没有横向
  滚动条、鼠标滚轮只滚纵向，等于「能滑但滑不到」。

  现在：激活 Tab 变化（点击 / 新建 / 关闭后落位）时 `animateScrollTo` 滚进视口；
  右侧溢出处给两个极简翻页按钮（一页 = 视口 80%），不溢出时不占位。测量按**内容
  坐标**（`positionInWindow - viewportLeft + scroll`），滚动中不会漂、不会与用户手动
  滚动互相拉扯；完整可见时返回 `null` 不起动画（避免「点一下抖一下」）。

  未采用 `ScrollableTabRow`：它没有关闭按钮的位置，改造它等于重做 × 的命中区。
  关闭逻辑一字未动（× 仍是唯一关闭入口，`onSelect` / `onClose` 原样透传）。
  几何部分抽成纯函数 `tabScrollTarget` + `TabScrollTargetTest`（7 条：贴边 / 两侧被裁 /
  视口未测量 / Tab 宽于视口）。公共表 +2 键（`common_tab_scroll_left` /
  `common_tab_scroll_right`），中英同步。

### Changed

- `ui-common` 的详情组件按「外壳 / 内容 / 底部动作」拆开
  （`MessageDetailContent` / `MessageDetailFooterActions` / `MessageDetailUiState`）。
  一行接入的 `MessageDetailDialog` 签名不变，RabbitMQ 零改动；IBM 与 Kafka 改为自持
  外壳，因为只有它们有 Loading / Failed 三态。**不要在宿主里按三态各 new 一个
  `ObjectDetailDialog`** —— 原因见上面第一条。

## [Unreleased] — 2026-10-29 (10)

### Fixed

- **IBM MQ 的 TLS 证书只对「测试连接」生效，正式连接一个字节都没拿到**（`IbmMQService` /
  `IbmMQConnectionManager`）。

  `applySslSystemProperties` 只在 `testConnection` 里被调用；`open()` 用的
  `mqProperties` 只带 host / channel / cipher / peerName —— 证书放不进 MQ 的连接
  `Hashtable`（客户端不提供连接级的证书属性），于是「测试通过、正式连接握手失败」，
  而且行为取决于 MQ 客户端有没有恰好缓存上一次测试的上下文。`mqProperties` 的 KDoc
  原写着「测试与正式连接共用同一份组装，保证行为永远一致」，正是这句把它盖住了。

  现在证书注入统一到 `IbmMqTls.withTls`：快照 6 个 JSSE 属性 → 写入本连接的值 →
  建连 → 还原，**窗口互斥**（属性是进程级的，两条不同证书的连接并发建连会互相覆盖）；
  留空字段写成空串而不是「跳过」，修掉「先测 A 的自签 CA、再测 B 的默认链，B 用上 A
  的信任库」这条假测试结论。`open()` 与 `testConnection()` 调的是同一个函数。
  证书在建连前预校验（文件缺失 / 口令错 / 无私钥条目），错误文案直接可读。

- **测一次 IBM MQ 的 SSL 连接，会把整个进程的默认 SSLContext 换掉**（同上）。
  `configureSslContext` 末尾的 `SSLContext.setDefault(...)` 从不还原：MQ 客户端根本
  不读默认上下文（`RemoteTCPConnection.getNonFipsSocketFactory` 自己
  `SSLContext.getInstance(...)`），对 MQ 毫无作用，却让 AI 面板的 Ktor 客户端改用
  MQ 那个 truststore。该函数已删除。

- **PKCS12 keystore 在 IBM MQ 上永远拿不到客户端证书**：客户端读
  `javax.net.ssl.keyStore` 时写死 `KeyStore.getInstance("JKS")`，而表单收的是 `.p12`。
  现在 PKCS12 会转成同口令的临时 JKS（POSIX 600、窗口结束即删），JKS 原样使用。

- **测试源码里提交了明文口令**（`IbmMQSslDiagTest`）：`mqdiag.pass` 与两个证书口令
  曾是带真实值的默认参数，从 `e9a3e0c` 起就在仓库里。现在默认值全部留空，参数一律
  由 `-Dmqdiag.*` 传入；`enabled=true` 但缺参数时明确报出缺哪一项。
  **这些凭据应视为已泄露，需要轮换**（历史提交仍在）。

- **解不开的密文会被再加密一层，造成不可逆的配置损坏**（`Sensitive.kt`）。
  密钥换了（换机器 / 拷贝配置 / 误删 `secret.key`）时 `decryptStoredValue` 保留密文
  （设计如此），而保存时 `encryptStoredValue` 把「密文当明文」再加密 —— 之后即使把
  正确密钥放回去，解出来也只是 `enc:v1:…` 字面量，且每保存一次多嵌一层。
  `encryptStoredValue` 现在对已是密文的值原样返回（幂等）；字符串字段与
  `SensitiveOptionsSerializer` 走的同一个入口。

- **命令控制台的 10MB 上限拦不住大文件**（`FileReader.jvm.kt`）：先 `readBytes()` 再判
  尺寸，选错一个几百 MB 的日志文件会先吃满堆再报错。现在先 `size()` 再读（读完再判一次
  兜 TOCTOU）。
- **四个消息发送页完全没有大小上限**：同样先读后判都没有 —— 裸 `File.readBytes()`。
  抽出 `mqui/file/MessageFileBytes.kt`（`readMessageBytes`，100MB 上限 + 上限前置判断），
  四个协议的单发 / 批量 / 「从文件加载」全部改走它；失败按「文件过大」与「读不出来」
  分开提示（新增 `*_send_file_too_large` 文案，中英各一份）。

- **IBM MQ 的「自动协商」加密套件其实永远连不上**（`mqProperties` / 连接表单）。
  真机实测（MQ 客户端 10.0.0.5 对 QM2 的 `ECDHE_RSA_AES_128_GCM_SHA256` 通道）：

  | 客户端 `SSL_CIPHER_SUITE` | 结果 |
  | :--- | :--- |
  | 留空（UI 的默认项，标着「自动协商」） | `MQRC_JSSE_ERROR (2397)` |
  | `ANY_TLS12_OR_HIGHER` | `MQRC_UNSUPPORTED_CIPHER_SUITE (2400)` |
  | 逗号分隔的套件列表 | `2400`（只接受单个套件名） |
  | IBM 名 `ECDHE_RSA_AES_128_GCM_SHA256` | `2400`（映射已关掉，它不是 Java 套件名） |
  | Java 名 `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` | **连接成功** |

  也就是说默认配置下**任何** IBM MQ TLS 连接都会失败，而失败信息是 2397 这种不指向
  根因的码。现在留空落成 `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`（可被用户覆盖），
  下拉首项文案也从「自动协商（默认）」改成实际使用的套件名。

### Tests

- **`IbmMqTlsLiveTest`（7 例，真机，默认 SKIP）**：走**正式连接路径**
  （`IbmMqTls.withTls` + `mqProperties` + `MQQueueManager`）连 QM2 的双向 TLS 通道，
  并配四条反向对照 —— 去掉 truststore（2397）、换成无关 CA（2397，证明服务端证书
  确实按我们的信任库校验）、去掉 keystore（2393）、错密码（2035）；另有一条
  「测试连接之后再连，不许继承上一条的证书」。
- **`PcfObjectScannerLiveTest` 三处连接绕过了 TLS 窗口，配了证书的 QM 永远「连不上」**
  并被记成 `LIVE SKIP: 连接失败（QMGR 不在线？）`：它直接
  `MQQueueManager(qmgr, mqProperties(config))`，而证书那一半在 `IbmMqTls.withTls` 里。
  这类假 SKIP 比红灯更危险 —— 报告是绿的，实际一条真机用例都没跑。改成与
  `IbmMQConnectionManager.open` 同一组合（证书窗口 + 属性）。本机登记
  `ibm-mq-qm2-tls` 之后，`aliasQueuesExposeExtendedAttrs` 与 `liveScanAllObjectTypes`
  真跑并通过（74 队列 / 17 通道 / 8 主题 / 3 订阅 / 2 监听器 / 5 认证信息…，failed 为空）。
- **`IbmMqPropertiesTest`（8 例，离线）**：留空套件落成默认值、显式值优先、去空白、
  SSL 关闭 / BINDING 模式不写 TLS 属性、peerName 仅在填了时下发。
- **`IbmMqTlsTest`（10 例，离线，keytool 现场生成证书）**：窗口内属性正确 / 窗口外还原 /
  后一条连接不继承前一条 / 文件缺失、口令错、无私钥条目三种预校验 / PKCS12 转临时 JKS
  且带私钥、窗口结束即删 / JKS 原样透传 / 关闭 SSL 与 BINDING 模式不碰全局属性。
- **`SensitiveValueIdempotenceTest`（6 例，离线）**：加解密往返、二次加密不变、异钥密文
  在「读-改-写」一轮后字节不变、明文仍被加密、options map 序列化同一条语义。
- **`MessageFileBytesTest`（7 例，离线）**：稀疏文件按 `length()` 拒绝（不读进内存）、
  边界值放行、自定义上限、缺失文件归类为「读不出来」、两个上限常量的大小关系。

## [Unreleased] — 2026-10-27 (9)

### Fixed

- **消息详情要 40 秒（第二个 60 秒+、而且递增）—— 根因是渲染被拆到第二个 effect、要等下一帧，
  而 Tao 主循环只在收到原生事件时派帧**（`MessageDetailDialog`）。

  真机埋点（`[ui.detail]`）测到的原始形态：

  ```
  detail composed:      sinceCompose=16ms
  detail preview ready: elapsed=3ms
  detail rendered:      render=1ms  total=51021ms   ← 1ms 的活等了 51 秒
  ```

  旧实现是两个 `LaunchedEffect`：第一个把基线写进 `preview`，第二个以 `preview` 为 key。
  key 一变，第二个 effect 的启动就必须等**下一帧**（Recomposer 的 `withFrameNanos`），
  而这个应用的主循环是 nucleus/Tao 自绘框架 —— 用户不碰鼠标就没有帧。于是 1ms 的渲染
  能等上几十秒，`disposed` 也跟着晚 2~6 秒。这也解释了「越开越慢」的观感：每个弹窗都
  在等一次用户交互。

  现在解码与渲染合进一个协程（`buildDetailContent`）：点击本身带来一帧，这一帧里既算完
  基线也算完渲染。真机实测 12 次打开：

  ```
  composed → loaded:  166/140/143/124/123/127/144/150/142/129/125/148 ms
  ```

  从 40~60 秒降到 **~130ms**，连开 12 次无任何递增。附带修正：看门狗改成到点**复查
  真实状态**（而不是无条件报警），正常完成的场景不会闪错误条。

### Tests

- **`MessageDetailPipelineTest`（4 例，离线）**：把「解码 → 渲染」抽成可测的
  `buildDetailContent(...)`（`ObjectDetailDialog` 用 nucleus `DecoratedDialog` 建真实窗口，
  无头测试里构造不出来，所以原来这条管线无法测）。断言一次调用同时给出基线与渲染、
  二进制 + 文本解释器不渲染、hex 解释器渲染二进制、小消息整条管线 < 500ms（实测 16.8ms）。
  变异验证：改成「只解码不渲染」→ 2 例红，报错直接指向「渲染又被挪到等下一帧的另一个
  effect 上了」。
- **`BundledFontCostTest` / `DetailCompositionCostTest`（app-ui）**：给「字体/新窗口代价」
  留两条真实阈值（单次字体解析 < 2s、连读 5 次总耗 < 5s、每段 composition < 5s 且不
  逐次变慢）。它们证明了一件事：**这两个假设都不成立**（字体 157ms→12ms 有全局缓存，
  新 composition 1007ms→30ms），历史上那次卡顿与它们无关。
- **`[ui.detail]` 埋点常驻**：`clicked / composed / loaded / disposed` 四行带毫秒时间戳，
  下次再出现「慢」可以直接加减定位，不必从猜开始。

- **RabbitMQ 消息会话：一次超时之后整个会话被自己锁死**（现场症状：「第一次读取消息
  详细信息就超时，之后彻底不动」）。根因是 `abort()` 被同步调在 `channelOps` 临界区里。
  `ChannelN.abort(int,String)` 并不「发一帧就返回」：它走
  `quiescingRpc(Channel.Close)` → `quiescingTransmit` → `Frame.writeTo` →
  `SocketFrameHandler.writeFrame`，那里拿的是**每连接一把**的 `_outputStreamLock`，直接写
  裸 socket。Java 的 `SO_TIMEOUT` 只作用于读，socket 写没有超时 —— 对端不读（broker
  flow control / 端口转发失效 / 发送缓冲区满）时它**永久阻塞**。于是：

  ```
  basicGet 等不到回复 → 5s 超时（用户看到「超时」）
    → discardChannel() → abort() 卡在 socket 写上
    → channelOps（Mutex）永不释放 → 之后每次拉取 / ack / 发布都挤在同一把锁上
  ```

  现在 abort 统一走 `abortInBackground`：有界 daemon 池（2 线程 / 队列 8 / 满了丢任务，
  绝不用 `CallerRunsPolicy` 把阻塞搬回调用方），会话 `close()` 另给一个有界等待
  `ABORT_WAIT_ON_CLOSE_MS = 500ms`，保住「close 返回时 channel 已关」这个正常路径性质。
  覆盖全部五处 abort 点：`discardChannel`、`ensureChannel` 的两条失败分支、
  `close()`、`RabbitMqModule.openMessageSession` 的失败路径。
- **RabbitMQ 消息会话没有任何日志**。真机出事时日志里只有管理 API 扫描与最后的
  shutdown，会话层是黑盒。现在 `RabbitMqMessageSession` 会记：`fetched`（条数 / limit /
  耗时）、`fetch timed out`（WARN）、`channel discarded`（WARN，带原因与 channel 号）、
  `channel aborted`（DEBUG，带耗时；超过 RPC 超时抬到 WARN 并注明「socket 写无超时」）、
  `session closed`。
- **消息详情框的「5s 硬超时」是假的**（`MessageDetailDialog`）。`withTimeout` 只能取消
  可取消的等待：实测 `withTimeout(200)` 套一个阻塞 1.2s 的块，实际耗时 1245ms —— 要等块体
  自己返回才抛。而 `ContentTab` 在两个 `null` 分支里只有一个永不停歇的转圈，于是解释器
  一旦卡住就是「无声地转圈」。现在：KDoc 改成实话，另加一条**独立**看门狗
  （`STALL_HINT_MS = 8s`，在另一个协程里计时，不受被阻塞的渲染协程影响）到点把转圈换成
  可读提示；基线已就绪时直接显示原文，用户至少有内容可看。

### Tests

- **`RabbitMqSessionAbortTest`（离线）**：用动态代理造一个 `abort()` 永久阻塞、`basicGet()`
  抛 `ChannelContinuationTimeoutException` 的假 channel，断言「第一次超时后第二次 fetch
  仍能返回」且 abort 跑在 `rabbitmq-channel-abort` 后台线程上、与 RPC 线程无交集。
  变异验证：把 `discardChannel` 改回同步 `abort()` → `fetchNext` 5s 内不返回，测试红。
- **`RabbitMqSessionLeakLiveTest` 跟着语义调整**：close-during-open 那条改成**有界等待**
  channel 真关（abort 现在是后台的），channel 记账那条靠 `close()` 的有界等待继续守住
  「每轮复用同一个 channel 号」（实测仍恒为 2）。

- **RabbitMQ 消息会话：`close()` 落在开 channel 期间会在共享连接上留下一条死 channel**
  （`RabbitMqMessageSession.ensureChannel`）。`createChannel()` / `queueDeclarePassive()`
  都是 RPC，这期间 `close()` 可能已经跑完 —— 它当时读到 `channel == null`（上一次
  `basicGet` 失败刚被 `discardChannel`），没有任何东西可 abort；随后 `ensureChannel`
  把迟到的 channel 照旧发布，这条 channel 再没人关，之后拉到的消息永久 unacked
  （对其他消费者不可见）。现在发布前在 `stateLock` 内复查 `closed`，已关闭则就地
  `abort` 并抛 `IllegalStateException("消息会话已关闭")`。
  另外 `close()` 整段改为跑在 `NonCancellable` 上：清理不能被取消，否则调用协程一旦被
  取消，`withContext` 在入口就抛，requeue 与 abort 一步都不执行（与 kafka / ibm-mq 的
  `closeResources` 同口径）。
- **会话账本只增不减**（`RabbitMqModule.openSessions`）。`QueueSessionSlot` 有两条直接
  调 `close()` 的路径（换队列关旧会话、槽已销毁时关迟到的会话），它们不经过
  `closeMessageSessionAsync`，于是每换一次队列 / 每「建连中 → 关闭」一次，账本就多留
  一条已关闭的会话，强引用着共享 `Connection` 直到应用退出。现在入账的是
  `TrackedSession` 包装：`close()` 的 `finally` 里销账，任意关闭路径都成立。

### Tests

- **`RabbitMqSessionLeakLiveTest` 补两条真机证据**（需要 `config.toml` 里的 RabbitMQ
  连接，无环境时 SKIP）：
  - 共享连接上的 channel 记账：一台连接上跑 11 轮「开 channel → 拉 5 条 → close」，
    `Channel.channelNumber` 必须被复用（实测恒为 2，一次都没涨），且每轮都能重新拉满
    同一批、全部轮次结束后队列里仍能取回全部消息。amqp-client 5.22 的
    `ChannelManager` 用 `IntAllocator` 发号，`abort()` 会经
    `AMQPConnection.disconnectChannel` → `releaseChannelNumber` 归还 —— 号涨即泄漏。
    这条**证伪**了「正常开关对话框会把 channel 漏在共享连接上」的猜测。
  - 竞态复现：用 `Connection` 代理把 `createChannel()` 放慢 600ms，在睡眠里调
    `close()`。修复前那条迟到的新 channel `isOpen == true` 且拉走的 1 条消息卡在
    unacked；修复后 `isOpen == false`、fetch 抛 `IllegalStateException`、队列可取回
    全部消息。
- **`RabbitMqSessionLedgerTest`（4 例，离线）**：`TrackedSession` 关闭即销账（含
  `delegate.close()` 抛异常、重复关闭、委派成员透传），不依赖 broker。
- **Live 测试不再把「环境不可用」报成「代码回归」**（`KafkaGroupLagBatchLiveTest`）。
  排查现场：`verify.sh` 全量跑红，唯一失败是这条；`config.toml` 里 Kafka 指向
  `11207-11209` 且是 `SASL_SSL`，而该端口当时跑的是 RabbitMQ 管理 API（`curl` 回
  `401 / not_authorized`，`openssl s_client` 回 `wrong version number`）。服务器资源
  只够起一种中间件，这几个端口是复用区 —— 配置齐全不代表端口上是 Kafka。
  旧版只检查「有 Kafka 连接 + truststore 文件在」就直连，于是握手失败被记成 FAILED。
  现在首次 `listConsumerGroups` 失败即 `assumeLive` 记 **SKIP**，与
  `PcfScanBudgetLiveTest` / `RabbitMqScanPartialLiveTest` 的探活口径一致；
  `CancellationException` 单独重抛（不被宽 catch 吞掉），跳过原因取根因文案。
- **批量取位点的等价断言存在真空通过**（同上测试）。两个取数函数用
  `runCatching{}.getOrNull()` 静默丢失败组：集群在测试中途掉线时，串行与批量都返回
  空表，`assertEquals(serial, batched)` 拿两张空表通过 —— 越是环境恶劣越容易变绿。
  现在取位点结果拆成 `OffsetsProbe(offsets, failures)`，并断言
  「真实组一个都不许读失败」「批量版覆盖全部消费组」，最后才比等价；
  坏组那一步另加「失败不得连坐真实组」。
- **`scripts/verify.sh` 缺执行位，GitHub Actions 的门禁从未真正跑过**：文件在 git 里
  是 `100644`，而 `.github/workflows/ci.yml` 写的是 `run: ./scripts/verify.sh` ——
  检出后直接 exit 126。已提交为 `100755`，并把 workflow / `doc/ci-gitee.md` 的调用改成
  `bash ./scripts/verify.sh`：执行位在不依赖它的前提下才是保险，而不是前提（Windows
  检出、zip 分发都会丢执行位）。

### Tests

- **`KafkaOffsetsProbeTest`（5 例，离线）**：把「失败必须被记下来」这层机制从 live 测试里
  抽成可注入的 `probeOffsets(groupIds, read)`，于是在没有 broker 的机器上也能证明它 ——
  而 broker 恰恰是最难得的资源。覆盖：成败分组、全失败仍留下失败痕迹、文案取根因、
  取消不被记成失败、空组表。
- **变异验证**：把 `probeOffsets` 的 `catch` 改回静默丢弃 → 3 例红（分组 / 全失败 /
  根因各一），确认这几条断言不是摆设。

### 未做（如实记录）

- 这条 live 测试的**新断言仍只在真集群上会执行**：本机 11207 上跑的是 RabbitMQ，
  今天只能证明「跳过路径 + 机制本身」。等价性 / 不慢于串行 / 单组隔离三条要等
  Kafka 起来再验（`-Dkafkadiag.enabled=true` 或直接 `:kafka-service:jvmTest`）。
- 其余 live 测试的开关仍是两套口径：IBM MQ / Kafka 消息面 / RocketMQ 用显式
  `-D*diag.enabled=true`，RabbitMQ 与 PCF 扫描用「配置在就自动连」。统一成一种需要
  动 8 个文件，等真机轮换验证过各模块再决定，不在这轮里顺手改。

## [Unreleased] — 2026-10-27 (8)

### Tests（先补测试，再动手拆）

- **对象慢扫的编排语义有了端到端测试**（`KafkaObjectScanOrchestrationTest`，8 例）：
  编排本体此前只能靠真集群验证 —— 单点测试只覆盖了 `scanKindOr` 与 `putIfScanned`
  各自的行为，**没有一条把真实编排跑一遍**。现在注入假 scanner 覆盖三件事：
  1. **单类失败不影响其他类**：topics 抛错时 brokers / groups 照常入缓存，
     失败的那类不写（不是写空列表）；
  2. **三类真的并发**：用 gate（三类都到齐才放行）+ `withTimeout` 断言，
     串行化实现会在这里超时；
  3. **全失败才判死**：部分失败发 `SCANNED(partial=true, failedKinds=[...])`
     且**不发** `SCAN_FAILED`；三类全灭才发 `SCAN_FAILED`。
  外加两条缓存策略：失败的类型**保留上一轮结果**（写成空列表等于告诉 UI
  「集群上没有 topic」）、broker 类失败时兜底空快照**不能**覆盖上一轮的 clusterId。
  事件断言用「屏障事件」做确定性等待（`emitSync` 只是往 SharedFlow 投递，
  送达仍是异步的 —— 第一版就是栽在这里）。
- **消费组位点行的纯聚合抽出来并测试**（`buildGroupOffsetDetails` +
  `KafkaGroupOffsetDetailsTest`，7 例）：`lag = end - current` 这三个算式是用户
  判断消费滞后的唯一依据，此前埋在 200 行的实时查询里。边界：从未提交 →
  `currentOffset` 为 **null**（不是 0，两者含义不同）、无末端位点 → 按 0 算、
  空组 → `ownerId` 为 null。
- 变异验证（4 项，全部确认能红）：失败的类型也写缓存 → 2 例红；
  单类失败即判死 → 1 例红；去掉并发改串行 → 1 例红；「判死」改成「一半失败」→ 红。

### Changed

- **`KafkaScanService` 按职责切分**（1007 → 926 行，另起 `KafkaObjectQueries.kt` 137 行）：
  把**协议查询层**（`scanKafkaBrokers` / `scanKafkaTopics` / `scanKafkaGroups` +
  `ClusterScan`）切到独立文件 —— 这一层只做「AdminClient → 领域快照」，不含并发
  编排、缓存策略、失败判定与事件。切分前后做了一次归一化逐字比对，三个函数体
  **完全一致**。
- 编排入口 `runObjectScan` 改为 `internal` 并接受三个 scanner lambda：真实路径
  注入 AdminClient 调用，测试注入假实现。这是本轮唯一为「可测」而做的结构调整，
  行为不变。
- 错误文案统一走 `kafkaErrorOf`（协议查询层与 `KafkaScanService.errorMessage`
  共用一份实现，不再各写一遍 `ExecutionException` 解包）。

### 未做（如实记录）

- `KafkaScanService` 仍剩约 926 行：组详情 / 重置位点 / Topic 管理这些**写操作与
  实时查询**没有行为测试，按「有测试的拆分才敢做」的原则没有动。要继续拆，需要先
  给这些路径补上同类 seam 测试（下一个自然切口是 `KafkaGroupService` 与
  `KafkaTopicAdmin`）。
- `IbmMQService`（1012 行）没有动：它的扫描走 PCF agent + 协作式预算，与 Kafka 的
  `coroutineScope { async }` 结构不同，不能套同一个 seam。

## [Unreleased] — 2026-10-27 (7)

### Fixed

- **队列深度告警的两份实现对 threshold=0 结论相反**（IBM MQ）。同一判定写在
  `QueueCardAdapter.toQueueCardData`（`maxDepth > 0 && ratio >= t/100`）与
  `TopologyGraphBuilder`（`ratio >= t/100`，未知 maxDepth 时 ratio=0）两处；正常阈值
  （表单限制 50..100）下等价，但 threshold=0 时拓扑把所有 **maxDepth 未知**的本地队列
  标成 WARNING，而列表页不标 —— `ibmMQConfigFromOptions` 对 config.toml 不钳制，
  手改配置即可触发。抽出 `isQueueDepthWarning(currentDepth, maxDepth, thresholdPercent)`
  作为唯一判定，两处共用。

### Changed

- **消息浏览页的搜索防抖统一到 `rememberDebouncedValue`**（P2.6 收尾）：IBM MQ /
  Kafka / RocketMQ 三个浏览页此前各写一份
  `LaunchedEffect(rawSearch) { delay(300); appliedSearch = rawSearch }`，与四个对象
  列表页已经用的共享实现并存 —— 同一语义两套实现。现在 8 处调用点共用一份。
- **把 UI 里的纯逻辑从 Composable 中提出来**（P1.3）：UI 层测试密度只有 8-12%，而
  git 历史显示缺陷恰恰集中在那里。本轮不提覆盖率数字，只把「伪装成 Composable 的
  纯函数」提出到可测位置：
  - `filterMessages`（三个浏览页）`private` → `internal`；IBM 版的 `now` 改为参数
    注入，相对时间窗的端点边界才测得准。
  - 新增 `canLoadMore` / `canRefresh`（IBM 浏览页）：翻页与刷新的进入条件此前是
    `loadMore()` / `refresh()` 里的裸 `if`，与协程启动混在一起。它们坏掉的表现是
    「按钮点了没反应」或「列表重复/错乱」。
  - `BrowseUiState` / `BrowseTimeRange` `private` → `internal`（测试可见）。

### Tests

- 新增 5 个测试类、40 例：
  - `QueueDepthWarningTest`(7)：端点包含、未知 maxDepth 不告警、**threshold=0 的分叉格**、
    null 当前深度、超容量。
  - `MessageBrowseFilterTest`(12)：搜索大小写/多项匹配/trim、相对时间窗（5min/1h/24h）
    边界含端点、脏时间戳只在「全部」可见、CUSTOM 单侧开放区间、搜索与时间窗是 AND。
  - `BrowsePageGuardTest`(9)：exhausted / loadingMore / refreshing 三种互斥、
    非 Ready 态一律不可动作、刷新在 exhausted 下仍允许（新消息可能已到货）。
  - `KafkaMessageFilterTest`(7) / `RocketMqMessageFilterTest`(7)：多字段匹配
    （key hex / value hex / offset / 时间文本）、**可空 key/tags 不因 "null" 命中**、
    空查询返回同一列表实例（组合期每帧调用，不该复制）。
  - `DebouncedValueTest`(5，`mq-ui` 首个 Compose 运行时测试)：用 `mainClock` 虚拟时钟
    验证首帧立即生效、299ms 不生效 / 300ms 生效、连打只生效最终值、清空立即生效、
    自定义间隔。
- `mq-ui` 的 jvmTest 增加 Compose 运行时依赖（`ui-test-kit` + `desktop.currentOs`）：
  防抖的定义就是时间，假时钟才有意义。
- 变异验证（全部确认能红）：
  - 队列告警判定换回拓扑旧实现 → 「threshold=0 的分叉格」变红；
  - `canLoadMore` 漏判 exhausted → 对应用例变红；
  - 防抖去掉首帧立即生效 → 4 例变红；
  - 防抖去掉「清空立即生效」→ 对应用例变红。

## [Unreleased] — 2026-10-27 (6)

### Changed

- **三个 `*ScanService` 的扫描骨架合并到 `mq-base` 的 `AbstractScanService<Ctx, Kind>`**
  （与上一阶段的 `AbstractConnectionManager` 同构）。此前 Kafka / RabbitMQ / RocketMQ
  各有一份逐字重复的骨架，约 90 行 × 3：

  | 成员 | 之前的重复情况 |
  | --- | --- |
  | `scanLocks` + 加锁包装 | 三份逐字相同 |
  | `manualScans` + `launchManualScan` + `cancelScan` | 三份逐字相同 |
  | `objectsTtlMs` / 快扫 TTL | 公式相同，只差取的配置字段名 |
  | `cacheOf` | 三份逐字相同 |
  | `markFailed` | 三份逐字相同 |
  | `isConnectionBroken` | Kafka/Rocket 是成员、Rabbit 是顶层函数，语义相同 |
  | 单类兜底 | 实现相同，**名字三种**：`safely` / `scanTypeOr` / `scanKindOr` |
  | 「全部失败」收尾（清标记 → 发事件 → 记账） | Kafka 有、Rabbit 缺 `clearScanFailures`、Rocket 走异常路径 |

  名字不统一本身就是漂移的证据 —— 同一个语义三个名字，读代码的人得先确认它们真的一样。
  现在统一为 `scanKindOr`，三个模块的调用点与时序不变。
  扫描本体（扫哪些对象、并发几路、事件 payload）仍留在各模块：它们的类型与业务语义
  确实不同，抽成泛型模板只会换来 `Any` 与强转。

### Fixed（顺带对齐的小偏差）

- RabbitMQ 的「四类全失败」路径此前**没有**清掉「部分数据未刷新」标记，而 Kafka 有。
  合并到基类后两者一致（整体不可用时「部分」已无意义，界面走连接错误态
  —— 见 Kafka 侧原有注释）。这是本轮唯一的行为变化，方向是让 RabbitMQ 向 Kafka 对齐。

### Tests

- 新增 `AbstractScanServiceTest`（mq-base，10 例）：单类兜底登记失败 / **不吞取消** /
  成功不记账、单类失败不算断连、全类失败才算、TTL 倍率（慢扫 ×1.5 / 快扫 ×2）、
  缓存取用、全类失败收尾、手动扫描触发与空转取消。
- 三个模块的既有失败隔离测试改用统一名字（`safely` / `scanTypeOr` → `scanKindOr`；
  RabbitMQ 的顶层 `isConnectionBroken` → 实例方法）。
- 变异验证：把 `scanKindOr` 的取消分支删掉 → 「does not swallow cancellation」变红；
  把「全类失败」误写成「一半失败」→ 「单类失败不算断连」变红；恢复后全绿。

## [Unreleased] — 2026-10-27 (5)

### Changed

- **AI 连接表单从两份逐字副本合并成一份**（`openai-ui` + `anthropic-ui` 去重）：
  `OpenAiConnectionForm` 与 `AnthropicConnectionForm` 各 229/237 行，逐行 diff 后
  **唯一差异是预选哪个 provider**。现在表单本体是 `ai-ui` 的
  `AiProviderConnectionForm`（构造参数 `presetProviderId`），两个 provider 模块各留
  19/21 行的声明（`serviceId` + 预选 id）。净减约 430 行。
- **`ModelComboBox` 移到 `ai-ui`**：它是 AI 专属控件，两个 provider 都要用；此前放在
  `openai-ui` 导致 `anthropic-ui` 必须 `implementation(projects.openaiUi)` —— 两个平级
  provider 之间的**横向依赖**，使「删掉一个 provider 不影响其余」不成立。该依赖已删除。
- 两个 provider UI 模块的依赖表收紧到实际所需（去掉 ai-service / ui-common /
  compose.foundation / material3 / materialIconsExtended；补 ui-graphics，因为
  `ModuleIcon.painter()` 的返回类型 `Painter` 在那里）。

### 澄清（重要）

- 上一轮质量扫描把 `openai-ui` / `anthropic-ui` 判为「约 740 行死代码」是**错的**。
  当时的依据是 `grep` 直接引用，而它们的调用发生在**运行时**：
  `META-INF/services/com.mqclient.core.ConnectionFormUi` / `ModuleIcon`
  → `ModuleRegistry.forms` / `.icons` → `NewConnectionDialog` 遍历装配、
  `AiSettingsPane` 按 providerId 取品牌图标。若照当时的结论删除，后果是
  「新建连接 → AI」整条路径只剩 `new_connection_form_todo` 占位，且 Anthropic 连接
  在设置页会显示成 OpenAI 的 logo（回落分支 `?: openAiIcon()`）。

### Tests

- 新增 `AiProviderSpiTest`（app-ui，4 例）：按**装配结果**而非源码引用断言 ——
  每个 AI provider 都有 ConnectionFormUi 与 ModuleIcon 注册、都在 AI 类目里、
  无重复注册。这正是上一轮误判的那个盲区。
  变异验证：删掉 anthropic 的 `ConnectionFormUi` 注册文件 → 4 例中 1 例变红；
  恢复 → 全绿。

## [Unreleased] — 2026-10-27 (4)

### Fixed

- **`ibm-mq-service` 依赖 `mq-ui`（进而编译期依赖 Compose），违反 README 的分层原则**。
  根因不是 build 文件多了一行，而是**三个 UI 适配器放错了层**：
  `MqscCommandParser` / `MqscCommandExecutor` / `MqscSyntaxHighlighter` 实现的都是
  `mq-ui` 的 UI 契约（`CommandParser` / `CommandExecutor` / `SyntaxHighlighter`，而后者的
  签名就是 Compose 的 `AnnotatedString`），却住在 service 模块里。于是整个
  `ibm-mq-service` 被迫 `implementation(projects.mqUi)`，而 `mq-ui` 以
  `api(compose.ui)` 传递 Compose；`MqscSyntaxHighlighter` 更是直接
  `import androidx.compose.ui.text.*`。
  修法是**把三个适配器移到 `ibm-mq-ui`**（`com.mqclient.ibmmq.ui.command`，与使用它们的
  `IbmMqCommandConsolePanel` 同包），协议本体（`MqscParser` / `MqscCommandExpander` /
  `MqscExecutor` / `MqscPcfSender`）留在 service。`ibm-mq-service` 不再声明 `mq-ui`，
  全模块零 Compose 引用。
  没有选「新建 command-api 模块」：那只是把 Compose 契约换个地方放，适配器本身仍是
  展示逻辑住在服务层 —— 移层才是根治。

### Added

- **分层架构门禁 `scripts/check_architecture.py`**（接进 `scripts/verify.sh`，CI 同步）：
  文档写了「`*-service` 不依赖 Compose / `*-ui` 不依赖具体协议实现」，但两条各被违反过
  一次。现在做成会失败的检查：①service 的 build 文件不得出现 compose 依赖；
  ②service 不得依赖任何 UI 模块；③service 源码不得 `import androidx.compose.*`；
  ④protocol UI 不得依赖别的协议的 service（`app-ui` 宿主机豁免）；⑤共享 UI 不依赖
  任何 service。单行 `// arch-allow: <原因>` 可显式豁免并留下痕迹。
  变异验证：临时注回 `implementation(projects.mqUi)` 与注入一行
  `import androidx.compose.ui.text.AnnotatedString`，检查分别以退出码 1 命中，恢复后归零。
  `./scripts/verify.sh --arch-only` 可单独跑。

### Tests

- 迁移出来的适配器补上测试（此前 service 里一条都没有）：
  `MqscCommandConsoleAdapterTest`（3 例：逐行对齐 / SKIPPED 标记 / 危险命令判定含缩写）、
  `MqscSyntaxHighlighterTest`（5 例：**文本内容不被改写**、动词加粗、注释斜体、空文本安全）。

## [Unreleased] — 2026-10-27 (3)

第三阶段重构：修测试假绿、抽连接生命周期基类、下沉跨模块复制的 UI。

### Fixed

- **LiveTest / DiagTest 的「假绿」**（13 个文件、23 处跳过点）：旧写法是
  `println("SKIP"); return` —— JUnit 把它记成 **passed**，于是离线 CI 上这些「真机
  验证」测试永远显示通过，实际上一次都没跑过（`KafkaGroupLagBatchLiveTest` 在无环境时
  也是「绿」的）。全部改成 JUnit assumption：新增 `:test-support` 模块提供
  `assumeLive(msg, cond)` / `assumeNotNull(msg, value)`，无环境时报告记 **skipped**，
  Gradle 能直接数出「有几个没跑」。
  两个坑已在 test-support 的 KDoc 里写明：①`AssumptionViolatedException` 是
  `RuntimeException`，被 `catch (Throwable)` 吞掉就会退回 PASSED —— 假设必须放在
  try 之外；②assumption 不做 smart-cast，需要非空值时用 `assumeNotNull`。
- **`graph-core` / `mq-base` / `hex` / `idoc` / `text` 解释器没有一行测试**：补
  `TopologyGraphTest`(5)、`MQServiceAdapterTest`(4)、`AbstractConnectionManagerTest`(10)、
  `HexInterpreterTest`(6)、`IdocInterpreterTest`(12)、`TextInterpreterTest`(6)，
  重点覆盖解析/渲染的异常边界（空输入、非法序列、未知字符集、未认领格式）。

### Changed

- **四个 `*ConnectionManager` 合并到 `mq-base` 的 `AbstractConnectionManager<Ctx>`**：
  `ensureContext` / `updateContextConfig` / `remove` / `connect` 状态机 / `disconnectNow`
  收尾顺序 / `registerScanJobs` / `onScanFailed` 的连续失败阈值与自愈 / `setStatus`
  双写，四条逐字副本合并成一份。上下文侧新增 `AbstractConnectionContext<C>`
  （status / lastError / cache / 连续失败计数 / cleanup）。
  子类只实现 `createContext` / `applyConfig` / `open` / `closeResources` / `scanJobs` /
  `isHandlePresent` / 事件名，外加可选钩子 `onConnected` / `onBeforeDisconnect` /
  `describe` / `errorMessage`。四个 manager 从 388+469+319+291 行降到
  约 200+180+160+200 行；`SCAN_FAILURE_THRESHOLD` 收敛为基类常量（各模块常量指向它）。
  **行为保持**：IBM MQ 的扫描失败仍按旧语义在 `IbmMQService` 里直接置 Failed（没有
  夹带「接入阈值」这类行为变更）。
- **`PartialScanBanner` 下沉到 `ui-common`**：四个模块的外壳（悬停配色 / 图标 / 两段
  文案）逐字相同，只保留「本连接失败了哪些类型 + 类型名怎么翻译」在各模块。
- **`SendTaskRow` 下沉到 `mq-ui`**：IBM MQ / Kafka / RocketMQ 三个发送页的任务行
  （文件名 tooltip / 大小列 / 四态图标 / 行尾动作 / 失败原因 hover）合并；
  各模块只留「状态 → 共用四态」与「状态 → i18n 键」两张映射表。
- **`AbstractConnectionFormState<C>` 提取到 `mq-ui`**：四个连接表单的「完成」链路
  （校验 → 15s 临时连接测试 → 落盘 → 失败弹「是否仍要保存」）合并为模板；
  子类只实现 `validate` / `buildConfig` / `testConnection` / `testTimeoutMessage` /
  `testFailurePrefix` / `persist`，可选 `canTestConnection`（IBM MQ 无头环境跳过测试）、
  `describeFailure` / `saveFailureMessage`（Kafka 用本地化文案）。
  落盘失败的收尾（清 `pendingConfig`、复位状态、回调宿主）从四份变成一份。
- **列表搜索加 300ms 防抖**：新增 `mq-ui` 的 `rememberDebouncedValue`，
  用于 IBM MQ 的通道 / 监听器 / 队列 / 通用对象列表：输入框仍即时回显，
  过滤用防抖后的值（清空输入立即生效，不让「取消筛选」有滞后感）。

### Tests

- `:test-support`（新模块，jvmMain）：`assumeLive` / `assumeNotNull`。
- 全量 `assemble jvmTest`：663 例、9 skipped（真机测试在无环境时正确记 SKIP）、
  唯一失败仍是 `KafkaGroupLagBatchLiveTest`（本机 truststore 与该集群证书不匹配，
  环境问题，自第一阶段起就在干净 HEAD 上复现）。

## [Unreleased] — 2026-10-27 (2)

### Fixed

- **XML 解释器把合法报文切碎**（消息解码器，`message-interpreter-xml`）：旧实现用
  `split(Regex("(?=<)|(?<=>)"))` 按字符切分，三类合法输入全部被切坏 —— CDATA 里的
  `<`（`<![CDATA[if (a < b)]]>`）、DOCTYPE 内部子集（`<!DOCTYPE msg [<!ENTITY …>]>`）、
  属性值里的 `>`（`<a title="x>y">`）。改为**真 DOM 解析 + 重新序列化**：
  1. `DocumentBuilderFactory` 按 XXE 加固（禁止外部通用/参数实体、禁止加载外部 DTD、
     `FEATURE_SECURE_PROCESSING`、外部 DTD/Schema 访问置空）；
  2. 整篇解析失败后**再按多根片段重试**（套内部根后只输出其子节点）—— MQ 正文里
     多根并列是常态，而严格 XML 解析器只接受单根；两条路径都失败才报 Failure；
  3. 自写递归序列化器（不走 Transformer）：CDATA 逐字保留、注释/PI 保留、
     空元素自闭合、单文本子节点内联（`<a>1</a>`）、多空格与换行按缩进归一。
  代价（已记录在 KDoc 里）：实体按解析结果展开、属性引号归一、混合内容的标签间空白
  被 trim、`<?xml?>` 声明与 DOCTYPE 不重新输出。
- **JSON 解释器把畸形 JSON 当成功**：旧 `prettyPrintJson` 是纯 token 重排，`{"a":}`、
  `{"a":1,}`、`{`、`{"a":1}{"b":2}` 全部返回 `Success` —— 用户会误以为消息体是
  合法 JSON。现在**先严格校验再重排**：`kotlinx-serialization-json` 做合法性校验
  （拒绝尾逗号 / 未加引号的 key / 多根），通过后仍走原来的字符级重排（只动空白，
  字符串字面量、数字形态、键序、`\u` 转义全部原样）。空容器 `{}` / `[]` 保持紧凑。
  `canInterpret` 同步收紧为「首字符像 JSON **且** 真能解析」—— Base64 解码层的链式
  选择不再把 `{oops` 这类文本错认成 JSON 再把整条链路拖成 Failure。
- **Base64 误判 + 静默吞字符**（`message-interpreter-base64`）：旧 `canInterpret` 只看
  `%4 == 0` + 字母数字白名单，而 Base64 字母表是 ASCII 子集的真子集 —— `ORDERS05`、
  `ABCD1234` 这类普通编号在数学上也是合法 Base64；更糟的是 `interpret` 用
  `Base64.getMimeDecoder()`，它会**静默跳过**非法字符，于是 `hello, world!` 被“成功”
  解码成一段垃圾字节。现在：严格字符集 + 长度 4 的倍数 + 填充只能出现在末尾且最多
  两个（`getDecoder()` 严格模式，不合规一律 `Failure`）；自动认领另加启发式 —— 需
  出现 `=`/`+`/`/` 或长度 ≥ 16，短纯字母数字串不再自动认领（仍可手动选）。
- **AI 连接迁移到不存在的 provider**：`AiConnectionRepository.migrateIfNeeded` 把旧
  `claude` 槽位映射成 `providerId = "claude"`，而 Anthropic 适配器的真实 id 是
  `anthropic` —— 迁移出来的连接在注册表里查不到适配器，面板只会报「没有对应的
  适配器」。改为映射到 `anthropic`（`defaultBaseUrl` / `defaultModel` 不变）。
- **AI 流式回复 O(n²)**：`AiPanelState` 每收到一个 SSE 分片就 `content + chunk` 再
  写回消息，气泡因此对整个累积文本**重跑一遍 Markdown 解析** —— 长回复就是几千次
  全量重解析，越写越卡。新增 `StreamingReplyBuffer`（StringBuilder 缓冲 + 100ms
  节流）：首片立即上屏（不让用户觉得“没反应”），之后距上次刷新 ≥100ms 才刷，稳态
  约 10 fps；流结束 / 取消 / 异常一律 `force` 刷出尾巴，一个字不丢（审计记录与界面
  内容一致）。
- **忽略 SSL 用了进程级共享的 trust-all 客户端**（`AiHttp`）：所有 `ignoreSsl=true`
  的连接共用一个“信任所有证书”的客户端，等于把这条宽松策略的适用范围扩散到全部
  使用方。改为**按目标 host 派生**（键 = `host[:port]`，同 host 不同端口也算两个
  目标）：每个 trust-all 客户端只可能与用户显式点开开关的那一个 host 握手。
  baseUrl 解析不出 host 时**抛 AiException**，不回退到共享客户端。
- **两处 `outcome.lastError!!`**（`ChannelListPanel` / `ListenerListPanel`）：改成
  在 `PollOutcome.Timeout` 分支里先绑定到局部 `val`，既不需要 `!!` 也不依赖跨协程
  smart-cast。
- **kafka-ui 直接展示 Kafka 原始异常**：8 处把
  `org.apache.kafka.common.errors.TimeoutException: Topic xxx not present in metadata
  after 60000 ms.` 这类英文原文（或 `cause` 链用 `"  ←  "` 拼成的长串）直接甩给用户。
  新增 `kafkaErrorText()`（kafka-ui 唯一出口，职责对齐 IBM MQ 的 `MqrcTranslator`）：
  解包 `ExecutionException` / `CompletionException` → 按异常类型映射到 i18n 文案
  （超时 / 认证 / 授权 / Topic 不存在 / 消息过大 / 位点越界 / Topic 已存在 / Topic 参数
  非法 / 协议版本不支持 / SSL）→ 附上截断后的原文片段（保留「哪个 topic」这类可操作
  细节）。未分类的异常原样走 `failureText`，不重复包装 service 已写好的中文文案。

### Security

- `ignoreSsl=true` 的信任范围从「进程级共享」收窄到「单个目标 host」，并在 **AI 面板
  顶部（常驻横幅）** 与 **设置页连接卡** 两处给出显著警告（`ai_ignore_ssl_warning`，
  中英 i18n）：证书校验已关闭 → 可被中间人劫持，API key 与对话内容可能泄漏。

### Tests

- 新增 `XmlInterpreterTest`（12 例：CDATA / DOCTYPE 内部子集 / 属性含 `>` / 注释与 PI /
  空元素 / 多根片段 / 标签错配 / 未闭合 / 外部实体不解析）、`JsonInterpreterTest`（7 例：
  5 种畸形必失败 + 字符串与数字不被改写 + 空容器紧凑）、`Base64InterpreterTest`（9 例：
  普通文本拒绝 / 短串不认领 / 折行容忍 / 非法字符与错位填充拒绝）、`StreamingReplyBufferTest`
  （7 例，含「渲染次数与时间成正比而非与分片数」）、`AiHttpTest`（7 例：同 host 共享 /
  异 host 与异端口隔离 / 坏 URL 抛错）、`KafkaErrorTextTest`（8 例）。
- `AiConnectionRepositoryTest` 补 `providerId == "anthropic"` 断言；`AiPanelStateTest`
  补「节流后 200 个分片仍逐字完整」。

## [Unreleased] — 2026-10-27

### Fixed

- **IBM MQ 消息操作吞掉取消**（`messaging/*`：download / detail / delete 三处 browse
  循环 + send）：`CancellationException` 被 `catch (e: Exception)` 转成
  `MessageOperationResult.failure` / `SendResult.failure`，关掉对话框后界面会看到一条
  **假失败**；更糟的是 `queue.get` 是纯阻塞 MQI 调用，协程取消它根本看不见 ——
  `while (true)` 会继续把整个队列扫完。改法：循环开头
  `currentCoroutineContext().ensureActive()` 作为唯一终止点，并在宽 catch **之前**加
  `catch (cancellation: CancellationException) { throw cancellation }`。
  `deleteMessageById` 尤其危险：取消后继续 browse 到匹配条目，破坏性 get 照样会执行
  （误删）。
- **IBM MQ 共享 `MQQueueManager` 并发竞态**：定时扫描（心跳 / 慢扫）与消息浏览 / 发送 /
  单对象查询 / MQSC 控制台各走各的，同时在同一条 MQ 通道上发 MQI 请求。新增
  `IbmMQConnectionContext.mqiLock`（**每连接一把** `Mutex`，随上下文生死），以下入口
  全部持锁：`startScan` / `scanMessageCounts` / `scanObjects`、`pollChannelStatus` /
  `pollListenerStatus` / `getChannelStatus` / `getQueueDepth` / `getClusterRelations` /
  `queryQmgrSnapshot`、`openBrowseSession` + `MessageBrowseSession.browseNext`、
  `downloadMessageBody` / `getMessageDetail` / `deleteMessage` / `sendMessage`、
  `MqscPcfSender.executeMqsc` / `diagnose`。锁内重新读取 `mqConnection`（等锁期间被断开
  的连接不会拿废句柄）；`scanAll` 内部 11 类查询仍在锁内并发（每类独立 agent），不因
  加锁退化成串行慢扫；断开路径**不**取锁（一条卡死的 MQI 调用不能把断开一起拖死）。
  `pollChannelStatus` / `pollListenerStatus` 保持「连接未登记 = 抛」的旧语义，不退化
  成「状态未知」让轮询白等到超时。
- **RocketMQ 全局 TLS 窗口改用 `Mutex`**：`RocketMqTlsStart.start` 从 `synchronized`
  改为 `TLS_BUILD_LOCK.withLock`，排队等窗口时可取消（连接在等待期间被断开不再把
  线程钉在 monitor 上）。互斥语义不变：AdminExt / Producer / PullConsumer 的所有实例
  共用 companion 的同一把锁，「快照全局字段 → 写本连接证书 → start（Netty 构造
  SslContext）→ 还原」整段互斥，连接 A 的握手不可能用到连接 B 的证书。
- **AI 流式请求静默失败**：OpenAI 兼容服务用 HTTP 200 + `{"error":…}` 回错误时，
  适配器把该帧当「解析不了的帧」跳过，最终返回空回复（界面只剩一个空气泡，日志无痕）。
  `chatStream` 现在在解析前拦错误帧并抛 `AiException`；非流式 `chat` 也加了同样的守卫
  （否则 `choices` 缺失会被解成空回复）。
- **`buildChainContextJson` 可能产出非法 JSON**：chain 字符串解析成非数组（如裸 token）
  时 `JsonPrimitive.toString()` 不带引号，拼出来的上下文不是合法 JSON（审计列也解不开）。
  现在只接受 `JsonArray`，其余一律降级为空数组。

### Security

- **Prompt Injection 防御（「询问 AI」链路分析）**：发给模型的上下文整段包进
  `[BEGIN UNTRUSTED CONTEXT] … [END UNTRUSTED CONTEXT]`，数据里伪造的标记被中和成
  全角形式（防止队列名提前闭合边界、把注入文字放到可信区）；系统提示词追加
  `ai_untrusted_context_rule`（中英 i18n + 代码兜底），明确「标记内是数据、不是指令」。
  包裹发生在**唯一出口** `AiPanelState.sendUserMessage`，四个 MQ 模块的
  `*AiChainBuilder` 产物自动覆盖；`chat_history.context_json` 仍存未包裹的纯 JSON
  （审计列保持可解析）。

### Changed

- **文档纠偏**：`CLAUDE.md` 的 RabbitMQ 段补上已落地的消息面（`basicGet` 只读浏览 /
  `basicAck` / `basicNack(requeue)` / `basicPublish` / `forward`，队列创建·清空·删除），
  删掉「amqp-client 已引入未使用」，并把写错的 AI 链路（`CLUSTER → TOPIC →
  CONSUMER_GROUP` 是 Kafka 的）改为 `VHOST → EXCHANGE → QUEUE`；`CHANGELOG` 里
  「删除 topology-ui / graph-core 模块」更正为「只删 Provider SPI，两个模块仍在使用」。

### Tests

- 新增 `RocketMqTlsStartTest`（2 例：8 实例并发断言窗口内最大并发 = 1、字段还原；
  去掉锁后该测试失败）、`AiSystemPromptsTest`（5 例）、`AiPanelStateTest` 增加
  「wire 上下文被边界包裹」1 例、`OpenAiCompatibleAdapterTest` 增加两个 200+error 用例。

## [Unreleased] — 2026-10-26 (4)

### Changed

- **`PropertyTable` 宽度策略与分栏比例**（全应用属性面板的观感一次性变了）：
  1. **Key 不再定宽 120dp** —— 那个宽度装不下 `x-dead-letter-exchange`（约 158dp）
     之类的长参数名，旧写法只能折行，而且行数无上限。改为 Key `weight(0.35f)` /
     Value `weight(0.65f)`（保持左右两列），两列都 `maxLines = 2` + 省略号。
     900dp 上限下 Key ≈ 300dp，长参数名一行就能站下。
  2. **整表 `widthIn(max = 900.dp)` 并水平居中**（`PropertyTableMaxWidth`，修饰符顺序与
     `ObjectCardGrid` 一致：先夹约束再 `fillMaxWidth`）。旧写法 `fillMaxWidth` +
     Value `weight(1f)` 会让短值（如 `classic`）在高分屏窗口里被推到极左、后面
     几千像素空白 —— 窗口越宽比例越失衡。
  3. **无复制按钮的行补 24dp 占位**：`weight` 分配的是扣除固定子项后的剩余宽度，
     缺按钮时那 24dp 会退还给两列，分栏线逐行错开约 8dp。
  4. **`PropertyTableSection` 的标题与表格进同一个限宽列**（内层传
     `maxWidth = Dp.Infinity`）：只夹表格会让标题贴最左、表格居中缩进几百 dp。
  5. 新增公开的 `WidthCappedColumn`（限宽 + 居中），供**混合页面**把整页内容夹到
     同一宽度：RocketMQ 概览（属性表 + Broker 表格卡片 + 计数）、RabbitMQ 概览
     （属性表 + 节点行）。只夹表格会让它比相邻的全宽块窄一截、左右缩进不齐。
  6. `PropertyTable` / `PropertyTableSection` 新增 `maxWidth` 参数（默认
     `PropertyTableMaxWidth`）—— 带默认值，11 个调用点一行未改。

  斑马纹与行尾复制行为不变。文档 `doc/ui-conventions.md` 与 `README.md` 里那句
  「Key 定宽 120dp」一并改掉（已失效的规范比没有规范更坑）。

## [Unreleased] — 2026-10-26 (3)

### Fixed

- **Kafka / RocketMQ 同步收口「单次失败即判死 → 假未连接」**（与上一轮 RabbitMQ 同一份
  写法的副本）。两个模块的 `markFailed` 都是一次扫描失败就写 `status = Failed`，而扫描
  入口又要求 `status == Connected`，于是判死之后心跳再也不跑，健康连接永久显示
  「未连接」—— 与 RabbitMQ 日志里那条完全同构。四处修正，逐模块落地：
  1. **连续失败阈值**（`SCAN_FAILURE_THRESHOLD = 3`）：失败只记一笔，连续 3 轮才置
     `Failed`；任一成功即计数归零，并把误判的 `Failed` **自动恢复**成 `Connected`。
     状态翻转收敛到 `KafkaConnectionManager` / `RocketMqConnectionManager` 的
     `onScanFailed` / `onScanSucceeded`，不再由 ScanService 直接改 `ctx.status`。
  2. **能力优先的守卫**：请求入口不再拿 `status` 当否决票，只要 AdminClient /
     管理客户端还在就直接发请求。Kafka 8 处、RocketMQ 6 处调用点统一收敛到
     `targetOrThrow`，两个模块各自的 `connectedContext`（只认 Connected）删除。
  3. **扫描入口忽略 status**（`scanTargetOrNull`）：判死之后心跳仍然继续跑 —— 这是
     自愈的前提；否则“第一次成功就恢复”永远等不到。
  4. **拒绝文案分层**（`connectionRefusalText`）：未登记 / 正在连接（提示等待）/
     上次请求失败（带出 `lastError` 真实原因）/ 未连接（指去侧栏）。
- **消息会话也不再被误判堵死**：`KafkaMessageService` / `RocketMqMessageService` 的会话
  守卫同样是「只认 Connected」，改为「有客户端就允许」，否则一次抖动会让消息浏览 /
  发送也一并报「未连接」，而底层连接其实还能用。

### Tests

- 新增 `KafkaConnectionGuardTest` / `RocketMqConnectionGuardTest`（各 8 例）：连续失败
  计数与重置、断开清理归零、阈值常量、四种状态拒绝文案互不相同、`Failed` 必须带出
  真实原因。三个模块现在是同一套断言。

## [Unreleased] — 2026-10-26 (2)

### Fixed

- **RabbitMQ 属性对话框假报「未连接」（根因：心跳单次失败就被判死，且判死永不复查）**：
  运行时日志里的现场是 —— 11:08:15 四类对象端点（exchanges / queues / bindings /
  nodes）**全部读成功**，11:08:20 一次状态快扫失败
  （`HTTP/1.1 header parser received no bytes`，长连接池取到被代理回收的空闲
  socket）就 `markFailed`；而扫描入口当时要求 `status == Connected`，判死之后
  10s 心跳再也不跑，于是**一条完全健康的连接永久显示「未连接」**，点重连也没用。
  三处修正：
  1. **连续失败阈值**：扫描失败只记一笔，连续 3 轮（≈30s）都失败才置 `Failed`；
     任一成功即计数归零，并把误判的 `Failed` **自动恢复**成 `Connected`。
  2. **能力优先的守卫**：请求入口不再拿 `status` 当否决票，只要管理 API 客户端
     还在就直接发请求（`status` 是扫描结论，`admin` 才是能力事实）；真断了由
     客户端招供 `ConnectException` / 超时的真实原因。12 处调用点统一收敛到
     `targetOrThrow`，扫除了「守卫先拒 → 真实错误根本没机会出现」这一类问题。
  3. **拒绝文案分层**：`connectionRefusalText` 按状态分开说 —— 未登记 / 正在连接
     （提示等待）/ 上次请求失败（**带出 `lastError` 真实原因**）/ 未连接（指去侧栏）。
     旧写法四种情况全叫「未连接」。
- **属性对话框不再钉住假错误**：队列与交换机属性对话框都跟连接状态走 —— 当前是
  错误态且连接落定成 `Connected` 时自动重拉。解决「打开瞬间恰好在
  `Connecting` / 抖动恢复中」造成的粘性报错（「重试」按钮保留）。
- **交换机绑定表的「刷新」按钮是永久转圈**（顺带发现）：`load()` 只把状态改成
  `Loading`，真正发请求的是 `LaunchedEffect(connectionId, exchangeName, bindingsReload)`
  —— 不动 `bindingsReload` 就不会重跑，界面卡死在转圈。现改为同时递增触发器。

### Tests

- 新增 `RabbitMqConnectionGuardTest`（8 例）：连续失败计数与重置、断开清理归零、
  阈值常量为 3、四种连接状态拒绝文案互不相同、`Failed` 必须带出真实原因、
  原因为空时仍可读。

## [Unreleased] — 2026-10-26

### Fixed

- **RabbitMQ 队列属性对话框「消费者」Tab 永远转圈**：根因是它去请求
  `GET /api/queues/{vhost}/{name}/consumers` —— 管理 API 根本没有这个端点
  （`rabbit_mgmt_dispatcher.erl` 里只有 `/consumers` 与 `/consumers/{vhost}`），
  每次点 Tab 都是一次注定失败的请求；UI 侧又让 `Idle` 与 `Loading` 共用转圈、
  全链路没有任何超时，所以失败被呈现成「加载不出来了」。改为直接渲染详情响应
  自带的 `consumer_details`（同一个 GET，零额外请求，消费者 Tab 不再有自己的
  加载态）：`RabbitMqAdminClient.queueConsumers` 这个伪端点请求、`RabbitMqScanService`
  的对应封装与 UI 调用点一并移除。字段按**可空**解析（老版本 / 关闭 metrics
  collector 时缺失），空明细但计数 > 0 时提示「明细不可用」而不是谎称无消费者。
  详情加载加 5s 硬超时，`TimeoutCancellationException` 先于
  `CancellationException` 接住 —— 成功 / 失败 / 超时一律落到终态。
- **属性对话框会「极其缓慢」的真正原因：消费者表格没有虚拟化**：
  `Column(verticalScroll) { consumers.forEach { ... } }` 会把全部消费者一次性
  组合（数千 consumer 的队列即上万节点，每次都重新布局）。换成 `LazyColumn`，
  并将单次渲染上限设为 200 行（超出的一行小字告知总数），组合与布局成本不再随
  消费者数增长。
- **详情请求的硬超时下沉到 HttpClient 层**（`getJson` 新增 `timeoutMillis`，
  `queueDetail` 传 `DETAIL_TIMEOUT_MS = 5s`），不再只靠全局连接超时（默认 30s、
  用户可改大）；UI 层 `withTimeout` 改为 6s 作最后一道保险 —— 刻意让客户端先触发，
  用户看到的是具体的「请求超时（5s）」，而不是笼统的「数据加载超时」。
  顺带修一个真实缺陷：`HttpRequestTimeoutException` 在 Ktor 3.2.3 里继承
  `java.io.IOException`（不是 `SocketTimeoutException`），`mapConnectionError` 漏发
  这一支时超时会直接透出英文原文
  `Request timeout has expired [url=...]`，现补上中文映射（GET 与写请求共用）。
- **RabbitMQ 属性对话框版式不统一**：概览 / 参数两个 Tab 手写的 Key-Value 行
  （值右对齐、无斑马纹、无复制按钮）全部换成 ui-common 的
  `PropertyTableSection` / `PropertyTable`，与 Kafka / RocketMQ 属性面板同一套
  视觉（斑马纹、Key 左对齐、值可拖选、行尾一键复制）。参数里的死信 / 资源限制
  类键改走 `PropertyItem.description` 小字标注，原先「值可点击跳转交换机 /
  队列」的链接按决策移除（连同失去生产方的 `RabbitMqUiHints` 对象焦点通道与
  两个面板里的消费点）。交换机属性对话框里的同一份手写 `PropertyLine` 一并收掉。
  「状态」色点与「Unacked」橙色随之取消 —— 与公共组件不支持值着色保持一致。

### Tests

- `RabbitMqAdminClientContractTest` +3 例：详情响应 `consumer_details` 明细解析、
  字段缺失 / 显式 null 都解析成 null 而不抛错；以及 MockEngine 拖 6s 时
  `queueDetail` 在 5s 抛带中文「请求超时」的 `IllegalStateException`（实测
  5.09s 完成，证明是客户端那道超时先说话，且英文原文没泄露）。

## [Unreleased] — 2026-10-22

### Added

- **拓扑图「导出为图片」**（commit ce5256c）：右上角缩放控件新增下载按钮，
  导出整张拓扑为 PNG。**离屏渲染而非截屏**——取 `viewerState.layoutedKuiver`
  （含用户拖动后的手动位置）按全图逻辑包围盒重画一遍，与当前缩放 / 平移
  完全无关，放大后屏幕外的节点不会再丢；场景 `density = 2`，卡片文字 / 图标 /
  线宽按 2x 重新栅格化（不是先画 1x 再拉伸），汇报和文档里不会糊。实现走
  Compose Desktop 的 `ImageComposeScene`（kuiver 0.4.2 的
  `KuiverViewer` / `KuiverViewerState` 没有 renderToBitmap / export API，已核对
  sources）：同一套 `TopologyNodeCard` 组合到独立 Skia surface，因此节点卡片
  仍是真 Composable，而非手绘 Canvas。包围盒 = 节点中心 ± 卡片尺寸 + 贝塞尔边
  与自环边的几何采样（长 BACK 边外抛的曲线不会被裁）+ 48dp 内边距；导出内容
  只含节点与连线，工具栏 / 滚动条 / 详情卡 / 选中高亮一律不进图，背景纯白
  （`background = null` 可得透明底）。
  OOM 防呆两级：单边 8000px + 总像素 2400 万，超限自动降 scale 而不是裁图，
  实际倍率随结果返回并在成功提示里带上输出尺寸。连线复刻 kuiver `EdgeRenderer`
  的端点算法（矩形边界求交 + 4px 间隙），复用库公开的 `EdgePathFactory` /
  `EdgeStyle` / `DefaultArrowDrawer`，BACK 边橙色与屏幕一致。渲染放
  `Dispatchers.Default`，保存走 ui-common `saveFileBytes`（FileKit 另存为），
  文件名 `topology-yyyyMMdd-HHmmss.png`；导出期间按钮置灰防并发，未传
  `snackbarHostState` 的面板（RocketMQ）由画布兜底本地 host 提示。
- **i18n**：ui-common 公共表 +4 键（`topology_action_export` /
  `topology_export_success` / `topology_export_failed` / `topology_export_empty`），
  中英同步。

### Tests

- `TopologyImageExportTest`（5 例）：包围盒与 2x 像素尺寸（476×152 dp →
  952×304 px）、超大图自动降 scale 且单边 ≤ 8000px、1x 按 1x 输出、空图拒绝
  导出、真实离屏渲染产出合法 PNG（尺寸 / 纯白左上角 / 存在非白内容）。
- `topology-ui` 的 `jvmTest` 补 `compose.desktop.currentOs`（离屏场景需 Skia
  原生库）与 `skiko.data.path` 指向构建目录（CI / 沙箱 HOME 只读时默认的
  `~/.skiko` 会失败）。

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
- **旧通用拓扑 Provider SPI 下线**：删除 TopologyProvider SPI /
  IbmMQTopologyProvider / IbmMqTopologyStyle（附带删除一个在部分环境会无限挂死的
  provider 单测）；拓扑渲染由 MQ 专属面板承担，TopologyFocusHolder 焦点机制保留。
  **topology-ui 与 graph-core 两个模块没有删除**——它们仍是各 MQ 专属拓扑面板共用的
  画布与节点模型（`TopologyCanvas` / 统一节点词汇），至今在 `settings.gradle.kts` 与
  各 `*-ui` 的依赖里活跃使用。
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
