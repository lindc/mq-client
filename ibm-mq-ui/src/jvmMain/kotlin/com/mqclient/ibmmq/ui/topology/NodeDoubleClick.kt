package com.mqclient.ibmmq.ui.topology

/**
 * kuiver 0.4.2 没有 `onNodeDoubleClick` 回调：用「[WINDOW_MS] 内同节点两击」手动判定。
 *
 * 拓扑图（TopologyPanel）与集群关系画布（ClusterRelationsPanel）共用。单击语义
 * （选中）不受影响——调用方在 [onClick] 外自行处理；画布空白点击时调 [reset] 防串。
 */
class NodeDoubleClick(private val onDoubleClick: (String) -> Unit) {

    private var last: Pair<String, Long>? = null

    /** 每次节点点击都调它：命中双击窗口时触发 [onDoubleClick] 并自清，单击只记录。 */
    fun onClick(nodeId: String) {
        val now = System.currentTimeMillis()
        val lastClick = last
        last = if (lastClick != null && lastClick.first == nodeId && now - lastClick.second < WINDOW_MS) {
            onDoubleClick(nodeId)
            null
        } else {
            nodeId to now
        }
    }

    /** 打断判定（点画布空白 / 切换选中目标时调用，避免跨目标误判）。 */
    fun reset() {
        last = null
    }

    companion object {
        /** 双击判定窗口（ms）。 */
        private const val WINDOW_MS = 400L
    }
}
