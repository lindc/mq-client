package com.mqclient.aipanel

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Filesystem-backed persistence for the AI panel's settings and chat history.
 *
 * Both files live under `~/.config/mq-client/` and are created lazily on first
 * write. Missing or unreadable files fall back to defaults — the panel must
 * still come up if the user has never launched the app before.
 */
object AiStore {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsFile: File by lazy { configDir().resolve("ai-settings.json").toFile() }
    private val messagesFile: File by lazy { configDir().resolve("ai-messages.json").toFile() }

    fun loadSettings(): AiSettings =
        try {
            if (!settingsFile.exists()) AiSettings()
            else json.decodeFromString(AiSettings.serializer(), settingsFile.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            System.err.println("[AiStore] failed to read settings (${settingsFile}): $t")
            AiSettings()
        }

    fun saveSettings(settings: AiSettings) {
        try {
            settingsFile.parentFile.mkdirs()
            settingsFile.writeText(json.encodeToString(AiSettings.serializer(), settings), Charsets.UTF_8)
        } catch (t: Throwable) {
            System.err.println("[AiStore] failed to write settings (${settingsFile}): $t")
        }
    }

    fun loadMessages(): List<AiMessage> =
        try {
            if (!messagesFile.exists()) emptyList()
            else json.decodeFromString(ListSerializer(AiMessage.serializer()), messagesFile.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            System.err.println("[AiStore] failed to read messages (${messagesFile}): $t")
            emptyList()
        }

    fun saveMessages(messages: List<AiMessage>) {
        try {
            messagesFile.parentFile.mkdirs()
            messagesFile.writeText(
                json.encodeToString(ListSerializer(AiMessage.serializer()), messages),
                Charsets.UTF_8,
            )
        } catch (t: Throwable) {
            System.err.println("[AiStore] failed to write messages (${messagesFile}): $t")
        }
    }

    /** Per-user config dir. ~ expansion isn't done by the JVM, so resolve relative to `user.home`. */
    private fun configDir(): Path = Paths.get(System.getProperty("user.home"), ".config", "mq-client")

    /** Used by tests / settings dialog reset button. */
    @Suppress("unused")
    fun configDirPath(): String = configDir().toString()

    @Suppress("unused")
    private fun touch(path: Path) {
        Files.createDirectories(path)
    }
}