package com.mqclient.core.cache

/**
 * 部分扫描的缓存写入纪律。
 *
 * 扫描是「按类」做的（队列 / 通道 / topic / broker / 节点…），单类失败时调用方拿到
 * 的是空结果。若照常写缓存，空结果就等于告诉 UI「这个集群上没有这类对象」—— 列表
 * 被清空、导航徽章归零，而真相是「这次没读到」。所以：**只为本轮真的扫到了的类型
 * 写缓存**，失败的类型保留上一轮的值。
 *
 * 三个 MQ 模块（ibm-mq / kafka / rabbitmq / rocketmq）用的是同一条纪律，这里放一份
 * 而不是各模块各抄一份 —— 抄出来的副本一旦有一份被改漏，部分失败就会悄悄退化成
 * 「清空列表」。
 *
 * ```kotlin
 * val failures = ConcurrentHashMap<ScanKind, String>()
 * val queues = safely(failures, ScanKind.QUEUES) { scanQueues() }   // 失败 → 空表 + 登记
 * cache.putIfScanned("queues", ScanKind.QUEUES, failures, queues, ttlMs = ttl)
 * ```
 *
 * @param scannedKind 本值对应的类型（与 [failedKinds] 的键同一套枚举）。
 * @param failedKinds 本轮失败的类型 → 失败原因（原因留给日志 / 事件用）。
 */
fun <K, T> CacheManager?.putIfScanned(
    key: String,
    scannedKind: K,
    failedKinds: Map<K, String>,
    value: T,
    ttlMs: Long = 0,
) {
    if (failedKinds.containsKey(scannedKind)) return
    this?.put(key, value, ttlMs = ttlMs)
}

/**
 * 所有类型**全部**失败 —— 整体不可用。
 *
 * 这条判定回答的是「要不要把连接标成已断开」，而不是「这次扫描是否完整」：
 * 单类失败常常只是权限缺口（例：RabbitMQ 的 `/api/nodes` 要 `monitor` tag，只带
 * `management` tag 的只读账号读不到），把整条连接标成 Failed 会让一个完全可用的
 * 账号显示成红色错误、点重连也没用。全部失败才是「一条请求都读不通」。
 *
 * @param failed 本轮失败的类型。
 * @param all 该扫描涉及的全部类型（通常是 `SomeKind.entries`）。
 */
fun <K> allKindsFailed(failed: Collection<K>, all: Collection<K>): Boolean =
    all.isNotEmpty() && failed.containsAll(all)
