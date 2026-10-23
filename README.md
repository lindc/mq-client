# MQClient

KMP 桌面端的中间件管理工具（当前主打 IBM MQ），Compose Multiplatform for Desktop + Material 3。

## 功能概览

- **IBM MQ 连接管理**：显式连接模型 —— 新建 / 编辑 / 删除连接，点击才建连；侧栏树形导航，右键菜单（连接 / 断开 / 测试 / 查看属性 / 删除）
- **MQSC 命令控制台**：类 DBeaver SQL 控制台的交互体验，MQSC 命令经 PCF 送达队列管理器（详见下文）
- **队列管理器属性面板**：PCF 查询 QMGR 配置与运行状态
- **AI 助手面板**：多连接切换的会话面板，聊天历史落库（SQLDelight）
- **RabbitMQ 支持**：连接管理（AMQP + 管理 API 双端口）、概览（健康度 / 集群节点）/ 队列 / 交换机 / 拓扑面板、消息浏览（basicGet 只读）与转发搬移（AMQP 直发）、队列 / 交换机创建与 Arguments、命令历史审计、「询问 AI」链路分析
- **通用能力**：中英文 i18n（设置内切换）、主题 / 字号 / 字体设置、日志统一落 `~/.mqclient/logs/`、Material Icons 全量替代 Emoji（Linux 下无字体依赖）、四个 MQ 模块拓扑图一键导出 PNG

## 构建与运行

```bash
./gradlew run                 # 启动桌面应用
./gradlew jvmTest             # 全量单元测试
./gradlew compileKotlinJvm    # 仅编译
```

配置与数据目录：`~/.mqclient/`（`config.toml`、`secret.key`、`logs/`、SQLite）。

## MQSC 命令控制台

**入口**：侧栏展开连接 → 「命令控制台」节点（未连接时点击会先隐式建连）。

### 编辑器

- 多行输入，逐条顺序执行（后一条常依赖前一条）
- `*` 开头的行为注释，空行跳过，两者不参与执行但保留行号
- 状态列逐行标记：未执行（灰线）/ 执行中（进度圈）/ 成功 / 失败
- 输出区展示每条命令的回显（`AMQ8409: ...`）、耗时与错误详情（MQRC 原因码已翻译成中文）

### 命令缩写

支持 runmqsc 习惯的缩写，发送前自动展开为完整命令（结果与历史记录展开后的文本）：

| 输入 | 实际执行 |
|------|---------|
| `dis q(*)` | `DISPLAY QLOCAL(*)` |
| `def ql(Q1) MAXDEPTH(5000)` | `DEFINE QLOCAL(Q1) MAXDEPTH(5000)` |
| `del chl(CH1)` | `DELETE CHANNEL(CH1)` |
| `ping qmgr` | `PING QMGR` |

常用缩写：`dis`/`disp`、`def`、`del`、`alt`、`clr`、`sta`、`sto`、`res`(RESET)、`ref`、`sus`；对象类型 `q`/`ql`、`qr`、`qa`、`qm`、`chl`/`ch`、`t`、`prc`、`nl`、`srv`、`auth`、`comm`。全称大小写不敏感；参数区大小写不做转换。

### 危险命令确认

`DELETE / CLEAR / ALTER / STOP / RESET` 开头的命令会先弹确认框，确认后才真正执行；执行中可随时点「取消」（当前这条跑完后停止后续命令）。

判定用的是**展开缩写之后**的动词，所以 `del q(Q1)` / `clr q(Q1)` / `alt` / `sto` / `res` 与全称同样会弹确认框 —— 缩写不会绕过这一步。

## RabbitMQ 连接与 TLS

RabbitMQ 连接涉及**两套端口**：AMQP 0.9.1（默认 5672，消息收发）与管理 API（默认 15672，扫描 / 属性面板 / 拓扑）。勾选「启用 TLS」后两者分别走 5671 / https。

### 自签 CA 配置

勾选「启用 TLS」后表单会出现两个附加字段：

| 字段 | 说明 |
|------|------|
| TLS 信任库路径 | PKCS12 信任库文件（需包含 broker 证书链），如 `/path/truststore.p12` |
| 信任库密码 | 信任库密码（落盘自动加密） |

- 两个都填写：AMQP 会话与管理 API 挂定制信任链（自签 CA 可用）
- 路径留空：使用 JVM 默认信任链（公共 CA 签发的证书场景）
- 信任库加载失败会在连接时报出具体原因

## UI 属性表规范（PropertyTable）

各 MQ 模块的属性面板/对话框统一用 `ui-common` 的 `PropertyTable` 展示 Key-Value 属性（连接属性、队列管理器、主题/消费组属性、集群概览等）：

```kotlin
data class PropertyItem(val key: String, val value: String?, val copyable: Boolean = true)

@Composable
fun PropertyTable(items: List<PropertyItem>, modifier: Modifier = Modifier)
@Composable
fun PropertyTableSection(title: String, items: List<PropertyItem>, modifier: Modifier = Modifier)
```

视觉与交互约定：

- **斑马纹**：奇数行 `surfaceVariant.copy(alpha = 0.3f)` + 4dp 圆角，偶数行透明；行高最小 40dp，行距 8dp
- **对齐**：Key 定宽 120dp 左对齐（次要色）；Value `weight(1f)` **左对齐**（不右对齐），超长两行后省略，包在 `SelectionContainer` 里可拖选
- **空值**：`null` / 空串显示 `-`，且不渲染复制按钮
- **复制**：行尾 24dp IconButton（ContentCopy 图标），点击写剪贴板并弹「已复制」Snackbar（host 内置在组件里，调用方无需接入）
- **分组**：小标题 + `PropertyTable`（用 `PropertyTableSection`），不再套卡片背景（避免与斑马纹叠色）

范围边界：`MessageHeaderRow`（消息详情 Header 的元数据表）与 IBM MQ 的 `PropertyGroupsPane`（队列管理器分组管线）不在该组件范围内；UI 层禁止直接展示 `CODE: 17 DESC: ...` 一类的原始错误码——service 层转友好文案，原始异常只进日志。

## 按钮规范（PrimaryButton / SecondaryButton / DangerButton）

各模块的普通操作按钮统一用 `ui-common` 的 `com.mqclient.uicommon` 按钮组件，调用方不再自行拼 `colors` / `border`：

```kotlin
PrimaryButton(onClick = ::submit, enabled = !busy) { Text(stringResource("common_save")) }
SecondaryButton(onClick = onDismiss) { Text(stringResource("common_cancel")) }
DangerButton(onClick = ::delete, enabled = !busy) { Text(stringResource("common_delete")) }
```

语义与配色：

- **PrimaryButton**：主操作（创建 / 保存 / 发送 / 确定 / 重试）。标准 `Button`，可用态 `primary` 底 + `onPrimary` 文字；禁用态 `onSurface@12%` 底 + `onSurface@38%` 文字；**无边框**
- **SecondaryButton**：次要操作（取消 / 关闭 / 返回 / 刷新）。标准 `OutlinedButton`，透明底 + `outline` 细边框 + `primary` 文字；禁用态透明底 + **无边框** + `onSurface@38%` 文字
- **DangerButton**：破坏性操作（删除 / 清空 / 重置）。标准 `Button`，可用态 `error` 底 + `onError` 文字；禁用态同 Primary 的灰色让位；**无边框**

约定：

- 主操作一律实心 `Button`，禁止用 `OutlinedButton` / `SecondaryButton` 承担创建、保存、发送
- 弹窗底部的「取消」「关闭」「返回」一律 `SecondaryButton`
- **禁用态绝对无边框**：`PrimaryButton` / `DangerButton` 本身无边框；`SecondaryButton` 禁用时必须显式 `border = null`，不能使用 `OutlinedButton` 默认的 `outline@12%` 禁用轮廓
- 禁止在调用处传 `containerColor` / `contentColor` / `border` / `BorderStroke`；语义色由组件内部决定
- 形状统一 `MaterialTheme.shapes.small`，内边距默认 `ButtonDefaults.TextButtonContentPadding`（紧凑弹窗密度）；纯图标按钮传 `standardMinWidth = false`
- 工具栏动作仍用 `ToolbarButton`（见 `CLAUDE.md`），不属于这三个组件的范围

## 架构

- `app-core`：配置 / 事件总线 / 加密 / 日志 / 数据库 / 调度 / SPI 定义（不依赖 Compose）
- `app-ui`：宿主（窗口、侧栏树、Tab、设置、AI 面板挂载）
- `ibm-mq-service` / `ibm-mq-ui` 等业务模块经 SPI（`ServiceAdapter` / `AdapterUI`）接入；UI 模块可按 `objectType` 贡献 Tab 内容（如命令控制台），无贡献时宿主回落占位页

各模块私有 i18n 表放各自 `composeResources/files/i18n/`，公共表在 `ui-common`。
