package com.mqclient.ibmmq.ui

import com.mqclient.ibmmq.messaging.MessageSummary
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 浏览页「加载更多 / 刷新」的**进入条件**。
 *
 * 这些判断此前是 Composable 里 `fun loadMore()` 内部的两行裸 `if`，与协程启动混在
 * 一起、没有任何测试。它们坏掉的表现是「按钮点了没反应」或「列表重复/错乱」：
 *  - 漏判 exhausted → 队列读空后每点一次都白跑一轮 browse；
 *  - 漏判 loadingMore → 连点两下并发翻页，同一批消息被追加两次；
 *  - 漏判 refreshing → 刷新把光标 reset 到队首，翻页接着在旧位置之后追加，顺序错乱。
 */
class BrowsePageGuardTest {

    private fun ready(
        exhausted: Boolean = false,
        loadingMore: Boolean = false,
        refreshing: Boolean = false,
    ) = BrowseUiState.Ready(
        messages = emptyList<MessageSummary>(),
        depth = 0,
        exhausted = exhausted,
        loadingMore = loadingMore,
        refreshing = refreshing,
    )

    /* ------------------------------ 加载更多 ------------------------------ */

    @Test
    fun `loadMore allowed on a plain ready page`() {
        assertTrue(canLoadMore(ready()))
    }

    @Test
    fun `loadMore blocked when the cursor is exhausted`() {
        assertFalse(canLoadMore(ready(exhausted = true)), "读到底了就别再发请求")
    }

    @Test
    fun `loadMore blocked while another page is loading`() {
        assertFalse(canLoadMore(ready(loadingMore = true)), "连点两下不能并发翻页")
    }

    @Test
    fun `loadMore blocked while refreshing`() {
        // 刷新会把光标 reset 到队首；此时翻页会在旧位置之后追加，列表顺序错乱。
        assertFalse(canLoadMore(ready(refreshing = true)))
    }

    @Test
    fun `loadMore blocked unless ready`() {
        assertFalse(canLoadMore(null))
        assertFalse(canLoadMore(BrowseUiState.Loading))
        assertFalse(canLoadMore(BrowseUiState.Failed("boom")))
    }

    /* ------------------------------ 刷新 ------------------------------ */

    @Test
    fun `refresh allowed on a plain ready page`() {
        assertTrue(canRefresh(ready()))
    }

    @Test
    fun `refresh allowed even when exhausted`() {
        // 正是「读到底了」才更需要重拉：新消息可能已到货。
        assertTrue(canRefresh(ready(exhausted = true)))
    }

    @Test
    fun `refresh blocked while refreshing or loading more`() {
        assertFalse(canRefresh(ready(refreshing = true)), "别叠第二次刷新")
        assertFalse(canRefresh(ready(loadingMore = true)), "翻页途中刷新会打断光标")
    }

    @Test
    fun `refresh blocked unless ready`() {
        assertFalse(canRefresh(null))
        assertFalse(canRefresh(BrowseUiState.Loading))
        assertFalse(canRefresh(BrowseUiState.Failed("boom")))
    }
}
