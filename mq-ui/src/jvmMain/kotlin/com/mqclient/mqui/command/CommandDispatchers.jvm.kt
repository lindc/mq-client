package com.mqclient.mqui.command

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** JVM：命令执行走 [Dispatchers.IO]（阻塞 IO 弹性池，不与计算任务抢线程）。 */
internal actual val commandIoDispatcher: CoroutineDispatcher = Dispatchers.IO
