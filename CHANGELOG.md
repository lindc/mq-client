# Changelog

本文件记录 MQClient 的显著变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

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
