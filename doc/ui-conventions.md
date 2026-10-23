# UI 约定：PropertyTable 与按钮组件

这份文档记录 `ui-common` 里两个被全模块复用的组件规范。它们的价值在**一致性**：
同一个视觉语言出现在四个 MQ 模块的所有属性面板和对话框里，用户不需要为每个模块
重新学习"哪个是主操作"。

改动这两个组件等于改动全应用的观感，所以约定要写下来，而不是散在各位作者的记忆里。

---

## UI 属性表规范（PropertyTable）

各 MQ 模块的属性面板/对话框统一用 `ui-common` 的 `PropertyTable` 展示 Key-Value 属性
（连接属性、队列管理器、主题/消费组属性、集群概览等）：

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

范围边界：`MessageHeaderRow`（消息详情 Header 的元数据表）与 IBM MQ 的
`PropertyGroupsPane`（队列管理器分组管线）不在该组件范围内；UI 层禁止直接展示
`CODE: 17 DESC: ...` 一类的原始错误码 —— service 层转友好文案，原始异常只进日志。

---

## 按钮规范（PrimaryButton / SecondaryButton / DangerButton）

各模块的普通操作按钮统一用 `ui-common` 的 `com.mqclient.uicommon` 按钮组件，
调用方不再自行拼 `colors` / `border`：

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

---

## 相关：错误文案的单一出口

与上面两个组件同属「不要在调用处重复实现」的一条：

UI 层把从 service 层冒上来的异常翻成用户可读文字时，**只用 `ui-common` 的 `failureText`**，
不要在自己的对话框里再写一份私有实现。它解包 `ExecutionException` 与
`CompletionException` 两层，并把空文案兜底到类名 —— 这三件事每份副本都会漏掉一两件。

历史教训：这条实现曾在 13 个对话框里各存一份逐字节相同的副本，其中一份改漏就会让
某个面板的报错退化成 `java.util.concurrent.CompletionException`。
