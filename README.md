# MQClient

KMP 桌面端的中间件管理工具（当前主打 IBM MQ），Compose Multiplatform for Desktop + Material 3。

## 功能概览

- **IBM MQ 连接管理**：显式连接模型 —— 新建 / 编辑 / 删除连接，点击才建连；侧栏树形导航，右键菜单（连接 / 断开 / 测试 / 查看属性 / 删除）
- **MQSC 命令控制台**：类 DBeaver SQL 控制台的交互体验，MQSC 命令经 PCF 送达队列管理器（详见下文）
- **队列管理器属性面板**：PCF 查询 QMGR 配置与运行状态
- **AI 助手面板**：多连接切换的会话面板，聊天历史落库（SQLDelight）
- **通用能力**：中英文 i18n（设置内切换）、主题 / 字号 / 字体设置、日志统一落 `~/.mqclient/logs/`、Material Icons 全量替代 Emoji（Linux 下无字体依赖）

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

## 架构

- `app-core`：配置 / 事件总线 / 加密 / 日志 / 数据库 / 调度 / SPI 定义（不依赖 Compose）
- `app-ui`：宿主（窗口、侧栏树、Tab、设置、AI 面板挂载）
- `ibm-mq-service` / `ibm-mq-ui` 等业务模块经 SPI（`ServiceAdapter` / `AdapterUI`）接入；UI 模块可按 `objectType` 贡献 Tab 内容（如命令控制台），无贡献时宿主回落占位页

各模块私有 i18n 表放各自 `composeResources/files/i18n/`，公共表在 `ui-common`。
