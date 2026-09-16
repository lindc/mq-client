package com.mqclient.core

import com.mqclient.core.log.Log
import java.util.Base64
import java.util.UUID

/** 本组件的日志 tag；与上报错误的 source（`core.ai`）同一命名。 */
private const val LOG_TAG = "core.ai"

/**
 * AI 连接的持久化入口（读写 `config.toml` 的 `ai` 段），风格对齐 [ConnectionStore]：
 *
 *  - **读**走 [load]：配置读不出来时以 failure 返回 + 错误通道上报，绝不假装
 *    「没有连接」（那会让界面上的连接凭空消失）；
 *  - **写**走 [save] / [create] / [update] / [delete]：读-改-写整份 config.toml，
 *    其它配置段原样保留，落盘失败以 failure 返回；四个方法都是原子的（校验与落盘
 *    在同一个临界区里，见下面的注释）—— 否则和 [ConnectionStore] 并发写会互相覆盖；
 *  - **迁移**：[migrateIfNeeded] 把旧模型（`ai.providerConfigs`，per-provider
 *    单槽）一次性折叠成连接列表，由 `ConfigManager.initOnStartup` 在启动时调用。
 *
 * token 的加解密由 [ConfigManager] 的名字匹配规则承担（字段名含 "token"），
 * 这里读写到的永远是明文。
 */
object AiConnectionRepository {

    /* -------------------------------- 读 -------------------------------- */

    /**
     * 读连接列表。
     *
     * 失败语义与 [ConnectionStore.loadConnections] 一致：文件不存在（首次启动）→
     * 空列表，正常；文件在但读不出来 → failure，同时通过 [CoreModule.reportError]
     * 上报，让调用方和用户都能知道。
     */
    fun load(): Result<List<AiConnection>> {
        val config = ConfigStore.loadConfigOrNull()
        if (config != null) return Result.success(config.ai.connections)

        val failure = IllegalStateException("config.toml 存在但读不出来（AI 连接没有丢，只是这次没读到）")
        CoreModule.reportError(
            message = failure.message ?: "读取 AI 连接失败",
            source = "core.ai",
            error = failure,
        )
        return Result.failure(failure)
    }

    /* -------------------------------- 写 -------------------------------- */

    /**
     * 四个写方法全走 [ConfigStore.editConfig]（原子的读-改-写）：校验与落盘在**同一
     * 个临界区**里完成。分开写（`loadConfigOrNull()` 校验 → `saveConfig()`）的话，
     * 两步之间的窗口里 [ConnectionStore] 的一次保存会把它整段覆盖掉。
     */

    /** 替换整个连接列表（其它配置段原样保留）。 */
    fun save(connections: List<AiConnection>): Result<Unit> =
        ConfigStore.editConfig { config ->
            Result.success(config.copy(ai = config.ai.copy(connections = connections)))
        }

    /** 新增一条连接；id 重复直接失败（和 [ConnectionStore.create] 同一约定）。 */
    fun create(connection: AiConnection): Result<Unit> =
        ConfigStore.editConfig { config ->
            if (config.ai.connections.any { it.id == connection.id }) {
                Result.failure(IllegalArgumentException("连接 id '${connection.id}' 已存在，改用 update()"))
            } else {
                Result.success(
                    config.copy(ai = config.ai.copy(connections = config.ai.connections + connection)),
                )
            }
        }

    /**
     * 切换激活的 AI 连接（`ai.activeConnectionId`）。新建表单落盘后调用它保持与
     * 设置页一致的行为：新建的连接立即成为面板当前连接。
     */
    fun setActive(id: String): Result<Unit> =
        ConfigStore.editConfig { config ->
            if (config.ai.connections.none { it.id == id }) {
                Result.failure(NoSuchElementException("连接 '$id' 不存在"))
            } else {
                Result.success(config.copy(ai = config.ai.copy(activeConnectionId = id)))
            }
        }

    /** 按 id 原位替换（列表顺序不变）；id 不存在直接失败。 */
    fun update(connection: AiConnection): Result<Unit> =
        ConfigStore.editConfig { config ->
            val index = config.ai.connections.indexOfFirst { it.id == connection.id }
            if (index < 0) {
                Result.failure(NoSuchElementException("连接 '${connection.id}' 不存在，改用 create()"))
            } else {
                Result.success(
                    config.copy(
                        ai = config.ai.copy(
                            connections = config.ai.connections.toMutableList().also { it[index] = connection },
                        ),
                    ),
                )
            }
        }

    /** 按 id 删除；不存在直接失败。删掉的若是当前激活连接，激活 id 一并清空。 */
    fun delete(id: String): Result<Unit> =
        ConfigStore.editConfig { config ->
            val ai = config.ai
            if (ai.connections.none { it.id == id }) {
                Result.failure(NoSuchElementException("连接 '$id' 不存在"))
            } else {
                Result.success(
                    config.copy(
                        ai = ai.copy(
                            connections = ai.connections.filterNot { it.id == id },
                            activeConnectionId = if (ai.activeConnectionId == id) "" else ai.activeConnectionId,
                        ),
                    ),
                )
            }
        }

    /* ---------------------------- 一次性迁移 ---------------------------- */

    /**
     * 旧模型（`ai.providerConfigs`，per-provider 单槽）→ 连接列表（`ai.connections`）。
     *
     * 幂等：旧的为空、或已经迁移过（connections 非空）时**原样返回同一个对象** ——
     * 调用方用引用判等（`!==`）决定要不要落盘。
     *
     * 旧的 SPI id 到新 providerId 的映射：
     *  - `openai` → `openai-compatible`（沿用用户填的 URL）；
     *  - `ollama` → `openai-compatible`，URL 补 `/v1`（那是它的 Chat Completions 端点）；
     *  - `claude` → `claude`（该适配器已不在内置列表；槽位迁移成连接保留数据，
     *    以「未知 provider」出现在设置里，由用户删除或重填）；
     *  - 其他（第三方 SPI provider）→ `openai-compatible` 尽力而为，用户可在设置里改。
     */
    fun migrateIfNeeded(ai: AiConfig): AiConfig {
        if (ai.providerConfigs.isEmpty() || ai.connections.isNotEmpty()) return ai

        val migrated = ai.providerConfigs.map { legacy -> legacy.toConnection() }
        val activeIndex = ai.providerConfigs.indexOfFirst { it.providerId == ai.activeProviderId }
        return ai.copy(
            connections = migrated,
            // 旧模型选中的 provider 对应的那条连接接任激活项；找不到就退第一条。
            activeConnectionId = migrated.getOrNull(activeIndex)?.id ?: migrated.firstOrNull()?.id.orEmpty(),
            providerConfigs = emptyList(),
        )
    }

    private fun AiProviderConfig.toConnection(): AiConnection {
        val mapping = LEGACY_PROVIDER_MAP[providerId]
        var baseUrl = providerUrl.ifBlank { mapping?.defaultBaseUrl.orEmpty() }
        if (mapping?.appendV1 == true && baseUrl.isNotBlank() && !baseUrl.trimEnd('/').endsWith("/v1")) {
            baseUrl = baseUrl.trimEnd('/') + "/v1"
        }
        return AiConnection(
            id = UUID.randomUUID().toString(),
            name = mapping?.name ?: providerId,
            providerId = mapping?.providerId ?: "openai-compatible",
            baseUrl = baseUrl,
            token = accessToken,
            model = model.ifBlank { mapping?.defaultModel.orEmpty() },
            systemPrompt = decodeLegacyPrompt(systemPromptBase64),
            enabled = true,
        )
    }

    /** 旧模型把提示词存成 Base64；解不出来按空处理（= 用默认提示词），但要留日志。 */
    private fun decodeLegacyPrompt(base64: String): String {
        if (base64.isBlank()) return ""
        return try {
            String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
        } catch (error: Throwable) {
            Log.warn(LOG_TAG, "failed to decode legacy systemPrompt (base64): $error")
            ""
        }
    }

    private data class LegacyMapping(
        val providerId: String,
        val name: String,
        val defaultBaseUrl: String,
        val defaultModel: String,
        /** 真表示旧的 URL 没带 `/v1` 时要补上（Ollama 的兼容端点在 `/v1` 下）。 */
        val appendV1: Boolean = false,
    )

    private val LEGACY_PROVIDER_MAP: Map<String, LegacyMapping> = mapOf(
        "openai" to LegacyMapping("openai-compatible", "OpenAI", "https://api.openai.com/v1", "gpt-4o"),
        "ollama" to LegacyMapping(
            "openai-compatible",
            "Ollama",
            "http://localhost:11434/v1",
            "llama3.1",
            appendV1 = true,
        ),
        "claude" to LegacyMapping("claude", "Claude", "https://api.anthropic.com", "claude-3-5-sonnet-latest"),
    )
}
