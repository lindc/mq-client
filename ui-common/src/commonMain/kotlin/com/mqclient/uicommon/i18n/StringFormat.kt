package com.mqclient.uicommon.i18n

/**
 * `stringResource` 无格式化参数，本函数是占位符替换的**全项目唯一实现**
 * （此前散落在 mq-ui / ibm-mq-ui 共 5 份手写实现，2026-10 收拢于此）。
 *
 * 支持两种占位符风格，可混用：
 *  - **位置式**：`%1$s` / `%1$d` / `%2$s` …—— 第 N 个参数替换第 N 组；
 *  - **顺序式**：`%s` / `%d` —— 裸占位符按在模板中出现的顺序逐个取参。
 *
 * 实现上对**原模板做单遍扫描**替换（非逐次 replace），因此参数值里出现
 * `%`、`%s`、`%d` 等字面量时不会被二次替换。未匹配到参数的占位符原样保留。
 * 不用标准库 `String.format` 统一：文案可能含字面 `%`（如阈值百分比），
 * `format` 遇到非法格式符会抛异常，本实现不受影响。
 *
 * 示例：
 * ```
 * "共 %s 条".withArg(5)                       // "共 5 条"
 * "共 %s 条（已加载 %s 条）".withArg(5, 3)     // "共 5 条（已加载 3 条）"
 * "原始值 %1$s".withArg("XXX")                // "原始值 XXX"
 * "耗时 %1$d ms".withArg(42)                  // "耗时 42 ms"
 * ```
 */
private val PLACEHOLDER = Regex("%(\\d+)\\$[sd]|%[sd]")

fun String.withArg(vararg args: Any): String {
    if (args.isEmpty()) return this
    // 位置式已占用的参数，顺序式取参时跳过（两种风格混用时各取所需）。
    val used = BooleanArray(args.size)
    var bareIndex = 0
    return PLACEHOLDER.replace(this) { match ->
        val positional = match.groupValues[1]
        when {
            // 位置式：%N$s / %N$d → 第 N 个参数（越界原样保留）。
            positional.isNotEmpty() -> {
                val index = positional.toInt() - 1
                if (index in args.indices) {
                    used[index] = true
                    args[index].toString()
                } else {
                    match.value
                }
            }
            // 顺序式：%s / %d → 按出现顺序取参（用尽后原样保留）。
            else -> {
                while (bareIndex < args.size && used[bareIndex]) bareIndex++
                if (bareIndex < args.size) {
                    used[bareIndex] = true
                    args[bareIndex++].toString()
                } else {
                    match.value
                }
            }
        }
    }
}
