@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 主机指纹确认卡的渲染守卫。
 *
 * 这是 SSH 安全的**用户可见那一半**：指纹必须完整展示（不能截断，否则没法逐段
 * 核对），首次连接与密钥变更必须视觉可分（后者更危险）。
 */
class HostKeyConfirmCardTest {

    private fun load() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, StarRocksAdapterUI().i18nTables)
    }

    private fun state() = StarRocksConnectionFormState("c", CoroutineScope(Job()))

    @Test
    fun `unknown host shows the full fingerprint`() = runComposeUiTest {
        load()
        val fingerprint = "QwnlRhv82bAgNxZwntSU76S+73djyUyv7ggXkjOlv2A"
        setContent {
            MaterialTheme {
                HostKeyConfirmCard(
                    state = state(),
                    prompt = StarRocksConnectionFormState.SshHostPrompt(
                        host = "jump.example.com",
                        port = 2222,
                        keyType = "ssh-ed25519",
                        fingerprint = fingerprint,
                        changed = false,
                    ),
                )
            }
        }
        // 指纹必须完整出现 —— 截断的指纹无法核对。
        onNodeWithText("SHA256:$fingerprint").assertExists()
        onNodeWithText("jump.example.com:2222 · ssh-ed25519").assertExists()
        assertTrue(stringResource("olap.starrocks.form.ssh_host_trust").isNotBlank())
        onNodeWithText(stringResource("olap.starrocks.form.ssh_host_trust")).assertExists()
    }

    @Test
    fun `changed host uses the alarming wording`() = runComposeUiTest {
        load()
        setContent {
            MaterialTheme {
                HostKeyConfirmCard(
                    state = state(),
                    prompt = StarRocksConnectionFormState.SshHostPrompt(
                        host = "jump.example.com",
                        port = 22,
                        keyType = "ssh-rsa",
                        fingerprint = "AAAA",
                        changed = true,
                    ),
                )
            }
        }
        // 变更与首连必须文案不同：前者可能是中间人，用户要停一下。
        onNodeWithText(stringResource("olap.starrocks.form.ssh_host_changed_title")).assertExists()
    }
}
