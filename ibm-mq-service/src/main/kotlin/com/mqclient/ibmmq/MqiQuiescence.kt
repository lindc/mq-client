package com.mqclient.ibmmq

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 有界地等一条连接的 `mqiLock` 空出来，返回是否真的等到了。
 *
 * ## 为什么需要
 *
 * 扫描 / 浏览 / 发送 / MQSC 都持 [com.mqclient.ibmmq.IbmMQConnectionContext.mqiLock] 访问
 * 同一个 `MQQueueManager`。`disconnect()` 若在它们还在飞的时候直接执行，就是两个线程
 * 同时操作一个 MQI 句柄 —— 实测会冒 MQRC 2009（连接被中断），行为未定义。
 *
 * ## 为什么必须有界
 *
 * 一条卡在 socket 上的 MQI 调用可能永远不返回（MQI 是纯阻塞、不响应协程取消的）。
 * 无限期等锁等于把「断开 / 删除连接 / 退出程序」一起拖死，而断开是用户明确要求的
 * 动作，必须完成。所以等不到就如实返回 false，由调用方记一笔后照常断开 ——
 * 这比假装串行化了、实际上把退出路径吊死要好。
 */
internal suspend fun awaitMqiQuiescence(lock: Mutex, waitMs: Long): Boolean =
    withTimeoutOrNull(waitMs) { lock.withLock { true } } ?: false
