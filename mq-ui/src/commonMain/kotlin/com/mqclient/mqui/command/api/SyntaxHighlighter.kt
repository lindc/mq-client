package com.mqclient.mqui.command.api

import androidx.compose.ui.text.AnnotatedString

/**
 * 语法高亮接口（各 MQ 模块实现）：对编辑器文本应用分色。
 *
 * 实现是**纯展示层**变换 —— 只改字符样式，不改文本内容；编辑器侧以
 * `VisualTransformation` 包装本接口（Identity offset mapping）。
 */
interface SyntaxHighlighter {

    /**
     * 对文本应用语法高亮。返回的 [AnnotatedString] 文本内容必须与输入一致，
     * 只允许附加 SpanStyle。
     */
    fun highlight(text: AnnotatedString): AnnotatedString
}
