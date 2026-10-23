package com.mqclient.uicommon

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * 把从 service 层冒上来的异常翻成**能直接给用户看**的一行文字。
 *
 * 存在的理由：13 个对话框各抄了一份一模一样的私有实现，其中一份改漏就会让某个
 * 面板的报错退化成 `java.util.concurrent.ExecutionException`。这里只留一份。
 *
 * 解包装的顺序有讲究：
 *  - [ExecutionException]：`CompletableFuture.join()` / `Future.get()` 的包装。
 *  - [CompletionException]：`CompletableFuture` **异步链**（`thenApply` 里抛）的包装。
 *    这是之前那 13 份副本共同的缺陷 —— 只解 `ExecutionException`，走异步链的路径
 *    会把 `CompletionException` 原样显示给用户。
 *
 * 只解这两层，不做「一直挖到底」：`cause` 链可能绕圈（自定义异常把自己挂成 cause），
 * 无限下钻会死循环；而 service 层已经承诺把根因包成人类可读文案。
 *
 * 空文案兜底到类名，再不行给 `"unknown"` —— 永远不返回空串（UI 会把空串当「没错误」）。
 */
fun failureText(error: Throwable): String {
    val unwrapped = when (error) {
        is ExecutionException -> error.cause ?: error
        is CompletionException -> error.cause ?: error
        else -> error
    }
    return unwrapped.message?.takeIf { it.isNotBlank() }
        ?: unwrapped::class.simpleName?.takeIf { it.isNotBlank() }
        ?: unwrapped::class.java.name.takeIf { it.isNotBlank() }
        ?: "unknown"
}
