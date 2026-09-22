package com.mqclient.core

import com.akuleshov7.ktoml.Toml
import kotlin.test.Test

/** 临时诊断：ktoml 0.7.1 对 Windows 反斜杠路径的转义 round-trip。 */
class TomlBackslashDiagTest {

    @Test
    fun roundTrip() {
        val toml = Toml.Default
        val path = "C:\\Users\\206630672\\Documents\\data\\mq\\trust.p12"
        val config = Config(
            connections = listOf(
                ConnectionInfo(
                    id = "1",
                    name = "n",
                    serviceId = "ibm-mq",
                    mqName = "m",
                    host = "h",
                    port = 1,
                    options = mapOf("trustStorePath" to path),
                ),
            ),
        )
        val text = toml.encodeToString(Config.serializer(), config)
        println("===== encoded =====")
        println(text)
        val back = runCatching { toml.decodeFromString(Config.serializer(), text) }
        println("===== decoded path = ${back.getOrNull()?.connections?.firstOrNull()?.options?.get("trustStorePath")} =====")
        println("===== roundtrip ok = ${back.getOrNull()?.connections?.firstOrNull()?.options?.get("trustStorePath") == path} =====")
    }
}
