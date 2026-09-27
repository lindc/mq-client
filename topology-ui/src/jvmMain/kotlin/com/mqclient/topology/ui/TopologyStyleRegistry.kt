package com.mqclient.topology.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 单个中间件类型的视觉样式：图标 + 强调色（[accent]）。
 *
 * 拓扑数据模型（graph-core）是**与中间件无关**的：节点只用 [com.mqclient.graph.topology.TopologyNode.type]
 * 这个字符串判别符描述「是什么」，渲染层（本模块）不知道 `"queue"` / `"xmitq"` 具体长什么样。
 * 真正把 type 映射成图标 + 颜色的是**各 *-ui 模块**——它们认识自己的中间件，通过
 * [TopologyStyleRegistry.register] 把自家的 type 登记进来；渲染时按 `(moduleId, type)` 查表。
 *
 * 这样新增一种中间件 / 新对象类型，只需在对应 ui 模块登记一条样式，**渲染层（TopologyPanel）
 * 与数据层（graph-core）都零改动**，满足「核心层不认识具体中间件类型」。
 *
 * 注意：[accent] 是**具体颜色**（非主题角色）——登记发生在模块初始化（组合之外），拿不到
 * MaterialTheme。渲染时若该节点带了语义 [com.mqclient.graph.topology.NodeColor]（非 NORMAL），
 * TopologyPanel 会用主题色覆盖 [accent]，只把 [accent] 当作 type 级别的默认强调色。
 */
data class TopologyStyle(
    val icon: ImageVector,
    val accent: Color,
)

/**
 * 拓扑节点样式注册表：按 `moduleId|type` 索引各中间件登记的类型样式。
 *
 * 与 [com.mqclient.core.topology.TopologyProviderRegistry] 同源思路：宿主路由（app-ui）只持有
 * 本注册表与 graph-core 的通用图类型，不依赖任何具体中间件 ui 模块；各 *-ui 模块在 SPI 初始化
 * 时登记自家样式，互不耦合。
 */
object TopologyStyleRegistry {
    /** 键值用 `moduleId|type`，避免不同中间件复用同名 type 时互相覆盖。 */
    private val styles = LinkedHashMap<String, TopologyStyle>()
    private val lock = Any()

    fun register(moduleId: String, type: String, style: TopologyStyle) = synchronized(lock) {
        styles["$moduleId|$type"] = style
    }

    fun unregister(moduleId: String, type: String) = synchronized(lock) {
        styles.remove("$moduleId|$type")
    }

    /** 取某中间件某类型的样式；未登记返回 null（渲染层回落 [fallback]）。 */
    fun styleFor(moduleId: String, type: String): TopologyStyle? = synchronized(lock) {
        styles["$moduleId|$type"]
    }

    /** 未登记类型时的兜底：中性圆点 + 灰色。节点不至于「开天窗」。 */
    val fallback = TopologyStyle(Icons.Filled.Circle, Color.Gray)
}
