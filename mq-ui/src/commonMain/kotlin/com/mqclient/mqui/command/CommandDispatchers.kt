package com.mqclient.mqui.command

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 命令执行用的后台调度器。
 *
 * 为什么要在 common 里声明：`CommandConsoleViewModel` 位于 commonMain，而
 * `Dispatchers.IO` 只有 JVM 有 —— common 源集里根本写不出这个名字。各模块的命令
 * 执行实现（[com.mqclient.mqui.command.api.CommandExecutor]）全是阻塞 IO：PCF
 * `agent.send`、数据库落库、文件读写，一条 `DISPLAY` 卡住就是整个窗口卡住。
 *
 * 所以「执行不能压在主线程」这条保证放在**桥接层**（ViewModel），而不是指望每个
 * 实现方各自记得 `withContext(Dispatchers.IO)`。在此之前它只是约定：唯一实现
 * （MqscCommandExecutor → MqscPcfSender）确实切了 IO，但接口本身对任何人开放，
 * 而 `MqscHistoryRepository` 的 KDoc 已写明将来会换 SQLDelight 实现 —— 那时一次
 * 阻塞落库就会落在 EDT 上，且没人会想到这里需要补 dispatcher。
 */
internal expect val commandIoDispatcher: CoroutineDispatcher
