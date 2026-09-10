package com.mqclient.aipanel

import com.mqclient.core.ConfigStore
import com.mqclient.core.CoreModule
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Filesystem-backed persistence for the AI panel's chat history.
 *
 * Settings (provider / model / URL / key / system prompt / history limit)
 * live in the shared `~/.mqclient/config.json` managed by [com.mqclient.core.ConfigStore];
 * only the chat history — which the other modules don't care about — stays here.
 *
 * File location: `~/.mqclient/ai-messages.json`. Missing or unparsable files
 * fall back to an empty list so a corrupted log never crashes the panel.
 */
object AiStore {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val messagesFile: File by lazy { configDir().resolve(FILE_NAME).toFile() }

    /** Returns the persisted chat history, sliced to at most [limit] entries (most recent). */
    fun loadMessages(limit: Int = Int.MAX_VALUE): List<AiMessage> {
        val all = readAll()
        if (limit >= all.size) return all
        return all.takeLast(limit)
    }

    /** Returns the entire persisted chat history, unsliced. */
    fun readAll(): List<AiMessage> =
        try {
            if (!messagesFile.exists()) emptyList()
            else json.decodeFromString(ListSerializer(AiMessage.serializer()), messagesFile.readText(Charsets.UTF_8))
        } catch (error: Throwable) {
            // 读不出来 = 界面上历史凭空消失（用户会以为记录丢了）。保持原行为返回空列表，
            // 但报到错误通道，让用户知道是读失败而不是真的没记录。
            CoreModule.reportError(
                message = "聊天记录读取失败，历史暂时显示为空",
                source = "ai-panel",
                error = error,
            )
            emptyList()
        }

    /**
     * 写回聊天记录。用「临时文件 + 原子改名」，而不是直接覆写：写到一半崩掉时磁盘上
     * 要么是完整的旧文件、要么是完整的新文件，不会留下半截 JSON 让下次启动直接读失败。
     */
    fun saveMessages(messages: List<AiMessage>) {
        val payload = json.encodeToString(ListSerializer(AiMessage.serializer()), messages)
        try {
            val target = messagesFile.toPath()
            Files.createDirectories(target.parent)
            val tmp = target.resolveSibling(target.fileName.toString() + TMP_SUFFIX)
            Files.write(tmp, payload.toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // 少数文件系统不支持原子改名（如某些 Windows / 网络盘）：退化成普通覆盖。
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            CoreModule.reportError(
                message = "聊天记录保存失败，本次对话不会留下来",
                source = "ai-panel",
                error = error,
            )
        }
    }

    /**
     * 聊天记录的目录：和 `config.json` 同一个 `~/.mqclient/`，因此也尊重
     * `mqclient.config.dir` 覆盖。之前这里自己拼 `user.home`，覆盖目录的测试 / 便携模式
     * 会写到另一个目录去。
     */
    private fun configDir(): Path = ConfigStore.configDir()

    private const val FILE_NAME = "ai-messages.json"
    private const val TMP_SUFFIX = ".tmp"
}