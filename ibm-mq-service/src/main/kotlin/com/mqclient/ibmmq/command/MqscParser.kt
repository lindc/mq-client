package com.mqclient.ibmmq.command

import com.mqclient.ibmmq.command.model.MqscCommand

/** MQSC 命令解析：多行文本 → 命令列表 + 危险命令判定。 */
object MqscParser {

    /**
     * 解析多行文本为命令列表。注释（`*` 开头）与空行标记出来但不剔除 ——
     * 调用方（编辑器状态列）需要完整行号对齐；执行侧自行跳过。
     */
    fun parse(text: String): List<MqscCommand> =
        text.lines().mapIndexed { index, line ->
            val trimmed = line.trim()
            MqscCommand(
                lineNumber = index + 1,
                text = line,
                isComment = trimmed.startsWith("*"),
                isBlank = trimmed.isEmpty(),
            )
        }

    private val DANGEROUS_VERBS = setOf("DELETE", "CLEAR", "ALTER", "STOP", "RESET")

    /**
     * 是否为危险命令（可能修改 / 删除对象）：按**展开后**的首个词判定。
     *
     * 两个细节都是防绕过：
     *  1. **先展开缩写** —— 执行侧（[MqscExecutor.execute] / mq-ui 的适配层）发出去的是
     *     `MqscCommandExpander.expand` 的结果，而本函数的调用点在执行之前。只看原文的话，
     *     `del q(Q1)`（→ `DELETE QLOCAL(Q1)`）、`clr q(Q1)`、`alt`、`sto`、`res` 全部
     *     绕过确认框直接生效 —— 而 README 恰好教用户用这些缩写。
     *  2. **整词比较**而非 `startsWith`：`STOPPED` 这类词往后扩展就不该再算命中。
     *
     * 注释行不参与判定（调用方先跳过 `isComment`）；万一传进来，首个词是 `*`，不命中。
     */
    fun isDangerous(command: String): Boolean {
        val expanded = MqscCommandExpander.expand(command)
        val verb = expanded.trim().takeWhile { !it.isWhitespace() }.uppercase()
        return verb in DANGEROUS_VERBS
    }
}
