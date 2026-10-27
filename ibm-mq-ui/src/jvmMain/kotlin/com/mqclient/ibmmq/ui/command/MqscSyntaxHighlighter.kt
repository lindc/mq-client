package com.mqclient.ibmmq.ui.command

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mqclient.mqui.command.api.SyntaxHighlighter

/**
 * MQSC 语法高亮（mq-ui [SyntaxHighlighter] 的 IBM MQ 实现）：命令动词 / 对象类型 /
 * 字符串 / 数字 / 注释分色。纯展示层变换 —— 只附加 SpanStyle，不改文本内容；
 * 编辑器侧以 VisualTransformation（Identity offset mapping）包装。
 * 配色是 VS Code Dark+ 的惯用值 —— 只用在字符上，深浅主题都可读。
 *
 * 为什么在 `ibm-mq-ui` 而不是 `ibm-mq-service`：它直接产出 Compose 的
 * `AnnotatedString` / `SpanStyle`，而 `*-service` 是「不得依赖 Compose」的层
 * （见 README 分层原则）。语法高亮本来就是展示逻辑，归 UI 模块才名副其实 ——
 * 放在 service 时，整个 service 编译期被迫依赖 mq-ui（进而依赖 compose.ui）。
 */
class MqscSyntaxHighlighter : SyntaxHighlighter {

    override fun highlight(text: AnnotatedString): AnnotatedString = buildAnnotatedString {
        append(text.text)

        VERBS.forEach { verb ->
            regexFor(verb).findAll(text.text).forEach { match ->
                addStyle(VERB_STYLE, match.range.first, match.range.last + 1)
            }
        }
        OBJECT_TYPES.forEach { type ->
            regexFor(type).findAll(text.text).forEach { match ->
                addStyle(TYPE_STYLE, match.range.first, match.range.last + 1)
            }
        }
        Regex("'[^']*'").findAll(text.text).forEach { match ->
            addStyle(STRING_STYLE, match.range.first, match.range.last + 1)
        }
        Regex("\\b\\d+\\b").findAll(text.text).forEach { match ->
            addStyle(NUMBER_STYLE, match.range.first, match.range.last + 1)
        }
        Regex("^\\*.*$", RegexOption.MULTILINE).findAll(text.text).forEach { match ->
            addStyle(COMMENT_STYLE, match.range.first, match.range.last + 1)
        }
    }

    private fun regexFor(word: String) = Regex("\\b$word\\b", RegexOption.IGNORE_CASE)

    private companion object {
        val VERBS = listOf(
            "DISPLAY", "DEFINE", "DELETE", "ALTER", "CLEAR",
            "START", "STOP", "PING", "RESET", "REFRESH",
        )
        val OBJECT_TYPES = listOf(
            "QLOCAL", "QREMOTE", "QALIAS", "QMODEL",
            "CHANNEL", "TOPIC", "SUB", "PROCESS",
            "NAMELIST", "SERVICE", "AUTHINFO", "COMMINFO", "QMGR",
        )
        val VERB_STYLE = SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Bold)
        val TYPE_STYLE = SpanStyle(color = Color(0xFF4EC9B0))
        val STRING_STYLE = SpanStyle(color = Color(0xFFCE9178))
        val NUMBER_STYLE = SpanStyle(color = Color(0xFFB5CEA8))
        val COMMENT_STYLE = SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)
    }
}
