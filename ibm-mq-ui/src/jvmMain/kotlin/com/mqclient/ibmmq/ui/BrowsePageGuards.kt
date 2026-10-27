package com.mqclient.ibmmq.ui

/**
 * 浏览页两个动作的**进入条件**（纯函数，便于单测）。
 *
 * 这些判断此前散在 `loadMore()` / `refresh()` 的裸 `if` 里，与协程启动混在同一个
 * Composable 函数中，没有任何测试。它们坏掉的表现是「按钮点了没反应」：
 *  - 少判 `exhausted` → 队列读空之后每点一次都发一轮 browse，白跑；
 *  - 少判 `loadingMore` → 连点两下并发翻页，`messages + more` 会重复追加同一批；
 *  - 少判 `refreshing` → 刷新与翻页互相覆盖列表（刷新把光标 reset 到队首，
 *    翻页接着在旧位置之后追加，结果顺序错乱）。
 *
 * 非 Ready 态（Loading / Failed）一律不可动作：那时连会话内容都还没有。
 */

/** 是否允许「加载更多」：就绪、未在翻页、未在刷新、且光标未到底。 */
internal fun canLoadMore(state: BrowseUiState?): Boolean {
    val ready = state as? BrowseUiState.Ready ?: return false
    return !ready.loadingMore && !ready.refreshing && !ready.exhausted
}

/**
 * 是否允许「刷新」：就绪、未在刷新、未在翻页。
 *
 * `exhausted` 不影响刷新 —— 正是「读到底了」才更需要重新拉一遍（新消息可能已到货）。
 */
internal fun canRefresh(state: BrowseUiState?): Boolean {
    val ready = state as? BrowseUiState.Ready ?: return false
    return !ready.refreshing && !ready.loadingMore
}
