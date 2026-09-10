package com.mqclient.core.event

/**
 * [systemTimeMillis] 的 JVM 实现 —— expect 声明见 commonMain 的 `AppEvent.kt`。
 *
 * 这是整套事件总线里唯一一行平台相关代码：commonMain 没有墙上时钟 API。
 */
internal actual fun systemTimeMillis(): Long = System.currentTimeMillis()
