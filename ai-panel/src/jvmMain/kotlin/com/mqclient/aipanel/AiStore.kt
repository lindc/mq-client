package com.mqclient.aipanel

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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

    private val messagesFile: File by lazy { configDir().resolve("ai-messages.json").toFile() }

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
        } catch (t: Throwable) {
            System.err.println("[AiStore] failed to read messages (${messagesFile}): $t")
            emptyList()
        }

    fun saveMessages(messages: List<AiMessage>) {
        try {
            messagesFile.parentFile?.mkdirs()
            messagesFile.writeText(
                json.encodeToString(ListSerializer(AiMessage.serializer()), messages),
                Charsets.UTF_8,
            )
        } catch (t: Throwable) {
            System.err.println("[AiStore] failed to write messages (${messagesFile}): $t")
        }
    }

    /** Per-user dir, under the shared `~/.mqclient/` root used by ConfigStore. */
    private fun configDir(): Path = Paths.get(System.getProperty("user.home"), ".mqclient")

    @Suppress("unused")
    fun configDirPath(): String = configDir().toString()

    @Suppress("unused")
    private fun touch(path: Path) {
        Files.createDirectories(path)
    }
}