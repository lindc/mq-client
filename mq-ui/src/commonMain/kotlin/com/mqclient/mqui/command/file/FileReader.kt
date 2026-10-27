package com.mqclient.mqui.command.file

import io.github.vinceglb.filekit.PlatformFile

/**
 * 命令文件不是合法 UTF-8 时抛出。多平台类型（继承 [RuntimeException]），
 * JVM actual 由 [java.nio.charset.CharacterCodingException] 转换而来，
 * 供调用方（Panel）按 i18n 键翻译成「文件非 UTF-8」提示。
 */
class FileNotUtf8Exception(message: String) : RuntimeException(message)

/**
 * 读取用户选择的命令文件为文本：校验大小上限与**严格 UTF-8** 可解码性。
 *
 * 失败以异常上抛，由调用方（Panel）翻译成用户可见的提示：
 *  - 超限 → [IllegalArgumentException]；
 *  - 非法 UTF-8 → [FileNotUtf8Exception]；
 *  - 其余 IO 问题 → 原样上抛（[IOException] 子类）。
 *
 * readBytes 是 suspend，不阻塞 EDT。
 */
expect suspend fun PlatformFile.readCommandText(): String
