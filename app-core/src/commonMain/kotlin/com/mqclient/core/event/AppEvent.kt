package com.mqclient.core.event

/**
 * 事件总线上流动的唯一事件模型。
 *
 * 事件只用**字符串名字**标识（命名约定 `<模块名>.<对象>.<动作>`，见 [EventNames]），
 * 不用 sealed class：新增模块 / 新增事件只要在自己的模块里加一个常量，
 * 事件总线核心代码零改动。
 *
 * [payload] 是开放的 `Map<String, Any?>`：字段可以随版本增减，老订阅者读不到新
 * 字段时拿到 null，不构成编译期破坏。
 *
 * 注意：AppEvent 只在进程内传递，不参与序列化（[payload] 里放的东西没法序列化）。
 * 需要落盘 / 跨进程的事件数据，由订阅者自己决定怎么存。
 */
data class AppEvent(
    /** 事件名，如 `ibm-mq.scan.completed`。 */
    val name: String,
    /** 事件携带的数据；key 由发送方与订阅方自行约定，允许为空。 */
    val payload: Map<String, Any?> = emptyMap(),
    /** 事件创建时刻（epoch 毫秒）。 */
    val timestamp: Long = systemTimeMillis(),
    /**
     * 可选来源标识，通常是模块 id（如 `ibm-mq`）。
     * 走 ServiceContext 发事件时会自动填成模块 id。
     */
    val source: String? = null,
)

/**
 * 按 key 取强类型 payload 值，取不到或类型不符返回 null。
 *
 * ```
 * val queueCount = event.get<Int>("queueCount") ?: 0
 * ```
 *
 * ⚠️ `as? T` 是**擦除后**的运行时检查，两点要清楚：
 *  1. `get<List<String>>("x")` 只能验证到 `List`，元素类型不检查；
 *  2. 不做数值转换 —— payload 里存的其实是 `Long`（JSON 反序列化的常见结果）时
 *     `get<Int>` 会静默返回 null。这种场景用 [getInt]。
 */
inline fun <reified T> AppEvent.get(key: String): T? = payload[key] as? T

/** `String` 值，类型不符返回 null。 */
fun AppEvent.getString(key: String): String? = payload[key] as? String

/** `Int` 值；容忍任意 [Number]（Long / Short / Byte / Double / Float）与可解析的字符串。 */
fun AppEvent.getInt(key: String): Int? = when (val value = payload[key]) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

/** `Long` 值；容忍任意 [Number] 与可解析的字符串。 */
fun AppEvent.getLong(key: String): Long? = when (val value = payload[key]) {
    is Long -> value
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
}

/** `Boolean` 值；字符串只认 "true" / "false"（大小写敏感，与 `toBooleanStrictOrNull` 一致）。 */
fun AppEvent.getBoolean(key: String): Boolean? = when (val value = payload[key]) {
    is Boolean -> value
    is String -> value.trim().toBooleanStrictOrNull()
    else -> null
}

/**
 * 墙上时钟毫秒。
 *
 * commonMain 里没有可用的墙上时钟 API（`System.currentTimeMillis()` 是 JVM 的），
 * 所以照 app-core 既有的 EncryptionService / DriverFactory 模式走 expect/actual：
 * 声明在这里，JVM 实现见 `TimeJvm.kt`。只用于给事件打时间戳，不参与业务判断。
 */
internal expect fun systemTimeMillis(): Long
