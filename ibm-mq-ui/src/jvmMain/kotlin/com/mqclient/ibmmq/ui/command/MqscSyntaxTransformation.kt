package com.mqclient.ibmmq.ui.command

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color

/**
 * MQSC 语法高亮（VisualTransformation + 正则）：命令动词 / 对象类型 / 字符串 /
 * 数字 / 注释分色。纯展示层变换（OffsetMapping.Identity），不影响输入本身。
 * 配色是 VS Code Dark+ 的惯用值 —— 只用在字符上，深浅主题都可读。
 */
class MqscSyntaxTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
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
        return TransformedText(annotated, OffsetMapping.Identity)
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
