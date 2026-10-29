@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.kafka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mqclient.kafka.KafkaPartitionSnapshot
import com.mqclient.kafka.KafkaTopicConfigSnapshot
import com.mqclient.kafka.KafkaTopicOffsetRange
import com.mqclient.kafka.KafkaTopicSnapshot
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 主题属性对话框「概览 / 配置」两 Tab 的渲染守卫。
 *
 * 存在理由：这两页从手写 Row/Column 迁到 `ui-common` 的 `PropertyTableSection`
 * （斑马纹 + 左对齐 + 行尾复制）。迁移是纯视觉重构，没有任何编译期约束保证
 * 「分组还在」「值还在」「复制按钮还在」—— 一旦有人把 items 列表改漏一项，
 * 编译照样通过、界面只是安静地少一行。这里用无头 Compose 渲染钉住三件事：
 *
 *  1. 概览的三组标题 + 各字段值确实渲染（内部主题走「是/否」而不是裸 true/false）；
 *  2. 行尾复制按钮存在（`PropertyTable` 把 item.key 挂在 contentDescription 上）；
 *  3. 配置页的常用 / 非默认分组、默认值小字说明、空态文案都在；
 *  4. 分区表的行高（≥ 40dp）与斑马纹（奇/偶行背景像素不同）真的存在。
 *
 * 不依赖 broker：三个 Tab 的数据入参都是快照值类型，直接构造即可。
 */
class KafkaTopicPropertiesTabsTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, KafkaUI().i18nTables)
    }

    private fun snapshot(internal: Boolean = false) = KafkaTopicSnapshot(
        name = "orders",
        internal = internal,
        partitions = listOf(
            KafkaPartitionSnapshot(id = 0, leaderId = 1, replicaIds = listOf(1, 2, 3), isrIds = listOf(1, 2, 3)),
            KafkaPartitionSnapshot(id = 1, leaderId = 2, replicaIds = listOf(2, 3, 1), isrIds = listOf(2, 3)),
        ),
    )

    @Test
    fun `overview renders three property sections with copyable rows`() = runComposeUiTest {
        loadTables()
        // 消息总数 = (7-0) + (3-0) = 10（避开千分位，与 JVM 默认 Locale 无关）。
        val offsets = LoadState.Success(
            mapOf(
                0 to KafkaTopicOffsetRange(earliest = 0, latest = 7),
                1 to KafkaTopicOffsetRange(earliest = 0, latest = 3),
            ),
        )
        setContent { MaterialTheme { OverviewTab(snapshot(), offsets) } }
        waitForIdle()

        // 基础信息
        onNodeWithText("基础信息").assertExists()
        onNodeWithText("主题名").assertExists()
        onNodeWithText("orders").assertExists()
        onNodeWithText("内部主题").assertExists()
        onNodeWithText("否").assertExists()

        // 分区配置
        onNodeWithText("分区配置").assertExists()
        onNodeWithText("分区数").assertExists()
        onNodeWithText("2").assertExists()
        onNodeWithText("副本因子").assertExists()
        onNodeWithText("3").assertExists()

        // 统计
        onNodeWithText("统计").assertExists()
        onNodeWithText("消息总数").assertExists()
        onNodeWithText("10").assertExists()

        // 行尾复制按钮（contentDescription = PropertyItem.key）。
        onNodeWithContentDescription("主题名").assertExists()
        onNodeWithContentDescription("消息总数").assertExists()
    }

    @Test
    fun `overview hides copy while offsets are still loading`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { OverviewTab(snapshot(), LoadState.Loading) } }
        waitForIdle()

        onNodeWithText("…").assertExists()
        // 加载中的占位不可复制：否则剪贴板里会是省略号。
        onNodeWithContentDescription("消息总数").assertDoesNotExist()
    }

    @Test
    fun `overview marks internal topics`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { OverviewTab(snapshot(internal = true), LoadState.Loading) } }
        waitForIdle()

        onNodeWithText("是").assertExists()
    }

    @Test
    fun `configs renders common and overridden sections with default annotation`() = runComposeUiTest {
        loadTables()
        val configs = listOf(
            KafkaTopicConfigSnapshot(key = "retention.ms", value = "604800000", isDefault = true),
            KafkaTopicConfigSnapshot(key = "compression.type", value = "producer", isDefault = false),
            KafkaTopicConfigSnapshot(key = "segment.ms", value = "3600000", isDefault = false),
        )
        setContent { MaterialTheme { ConfigsTab(LoadState.Success(configs)) } }
        waitForIdle()

        onNodeWithText("常用配置").assertExists()
        onNodeWithText("retention.ms").assertExists()
        onNodeWithText("604800000").assertExists()
        // broker 默认值：PropertyTable 没有颜色通道，改用 description 小字。
        onNodeWithText("默认值").assertExists()

        onNodeWithText("非默认配置").assertExists()
        onNodeWithText("segment.ms").assertExists()
        onNodeWithText("3600000").assertExists()

        // 配置项的复制按钮同样在（长配置表的主要用途）。
        onNodeWithContentDescription("retention.ms").assertExists()
        onNodeWithContentDescription("segment.ms").assertExists()
    }

    @Test
    fun `configs shows the empty state when nothing is overridden`() = runComposeUiTest {
        loadTables()
        val configs = listOf(
            KafkaTopicConfigSnapshot(key = "retention.ms", value = "604800000", isDefault = true),
        )
        setContent { MaterialTheme { ConfigsTab(LoadState.Success(configs)) } }
        waitForIdle()

        onNodeWithText("无非默认配置项").assertExists()
    }

    @Test
    fun `configs surfaces load failures`() = runComposeUiTest {
        loadTables()
        setContent { MaterialTheme { ConfigsTab(LoadState.Error("timed out")) } }
        waitForIdle()

        onNodeWithText("配置加载失败：timed out").assertExists()
    }

    /**
     * 分区 Tab 的表格：表头六列 + 行值都在，且行高 ≥ 40dp（对齐 PropertyTable）。
     *
     * 行高是本轮重构的核心承诺之一（旧实现是 bodySmall + 无最小高度，约 32dp），
     * 用 `assertHeightIsAtLeast` 直接钉住 —— 只要有人把 heightIn 删了，这条就红。
     */
    @Test
    fun `partitions table renders headers and keeps 40dp row height`() = runComposeUiTest {
        loadTables()
        val offsets = LoadState.Success(
            mapOf(
                0 to KafkaTopicOffsetRange(earliest = 0, latest = 7),
                1 to KafkaTopicOffsetRange(earliest = 0, latest = 3),
            ),
        )
        setContent {
            MaterialTheme { PartitionsTab(snapshot(), offsets, search = "", onSearchChange = {}, modifier = Modifier.fillMaxSize()) }
        }
        waitForIdle()

        // 表头六列
        onNodeWithText("Partition").assertExists()
        onNodeWithText("Leader").assertExists()
        onNodeWithText("Replicas").assertExists()
        onNodeWithText("ISR").assertExists()
        onNodeWithText("最早位移").assertExists()
        onNodeWithText("末端位移").assertExists()

        // 行值（2 个分区；"1, 2, 3" 同时出现在 p0 的 replicas 与 isr 两列 → 取首节点）
        onAllNodesWithText("1, 2, 3")[0].assertExists()
        onNodeWithText("2, 3").assertExists()

        onAllNodesWithTag(PARTITION_PLAIN_ROW_TAG)[0].assertHeightIsAtLeast(PARTITION_ROW_MIN_HEIGHT)
        onAllNodesWithTag(PARTITION_ZEBRA_ROW_TAG)[0].assertHeightIsAtLeast(PARTITION_ROW_MIN_HEIGHT)
    }

    /**
     * 斑马纹：奇数行必须自己画上背景，偶数行必须不画。
     *
     * 只断行高证明不了背景 —— 背景是画出来的像素，不是语义树上的属性。这里对整行截图，
     * 采样右侧空白区（避开文字字形）读 alpha：**captureToImage 只捕获节点自身的绘制，
     * 不含父背景**，所以这条正好能分开两种状态 —— 斑马行 alpha > 0（画了
     * `surfaceVariant@30%`），普通行 alpha == 0（透明，交给父背景）。两者颜色必然也不同。
     */
    @Test
    fun `partitions table paints zebra stripes on odd rows`() = runComposeUiTest {
        loadTables()
        val offsets = LoadState.Success(
            mapOf(
                0 to KafkaTopicOffsetRange(earliest = 0, latest = 7),
                1 to KafkaTopicOffsetRange(earliest = 0, latest = 3),
            ),
        )
        setContent {
            MaterialTheme { PartitionsTab(snapshot(), offsets, search = "", onSearchChange = {}, modifier = Modifier.fillMaxSize()) }
        }
        waitForIdle()

        fun edgePixel(tag: String) = onAllNodesWithTag(tag)[0].captureToImage().toPixelMap().let { map ->
            // 右侧边缘：背景覆盖到行边界，而文字左对齐 —— 这里必定是纯底色。
            map[map.width - 6, map.height / 2]
        }

        val plain = edgePixel(PARTITION_PLAIN_ROW_TAG)
        val zebra = edgePixel(PARTITION_ZEBRA_ROW_TAG)
        assertTrue(
            zebra.alpha > 0f,
            "斑马行必须自己画背景（实际 alpha=${zebra.alpha}）—— alpha=0 说明背景没画上",
        )
        assertTrue(
            plain.alpha == 0f,
            "普通行必须保持透明（实际 alpha=${plain.alpha}）—— 否则每行同色，不成斑马",
        )
        assertNotEquals(plain, zebra, "相邻两行的背景必须不同 —— 否则斑马纹没生效")
    }

    @Test
    fun `partitions table shows the empty state when the filter matches nothing`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                PartitionsTab(
                    snapshot(),
                    LoadState.Loading,
                    search = "no-such-partition",
                    onSearchChange = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()

        onNodeWithText("没有匹配的分区").assertExists()
    }
}
