package com.mqclient.core.topology

import com.mqclient.graph.topology.TopologyGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 拓扑图数据提供方（接口签名，不实现）。
 *
 * 由各中间件 service 模块实现：把自身领域对象映射为通用 [TopologyGraph]。数据模型层
 * （graph-core）只定义数据结构，本接口定义「如何产出图」的契约，不依赖任何 MQ 客户端库。
 * UI 层只依赖此接口与 [TopologyGraph]，从而与具体中间件解耦。
 *
 * 实现方可按自身中间件的类型体系来解释 [rootType] 与 [TopologyNode.type]（均为不透明字符串
 * 判别符，见 graph-core），例如 IBM MQ 传 `"queue"` / `"channel"`，Kafka 传 `"topic"` / `"broker"`。
 *
 * 数据就绪遵循「先连接 → 全面扫描 → 出图」的通用节奏（与其它对象列表页一致）：UI 在挂载时
 * 调 [refresh] 触发一次扫描，并订阅 [dataChanged] 在扫描完成后重建图。无需扫描的静态实现
 * 可用默认值（[refresh] 空实现、[dataChanged] 空流）。
 */
interface TopologyProvider {
    /**
     * 从 [rootName]（类型 [rootType]）出发，向上游/下游各展开 [depth] 层，
     * 返回单个 QMgr 内的局部拓扑。
     *
     * @param connectionId 连接标识
     * @param rootName 起始对象名（即节点 [com.mqclient.graph.topology.TopologyNode.name]）
     * @param rootType 起始对象类型（不透明字符串判别符，由实现方按自身中间件解释）
     * @param depth 展开层数（默认 1；当前只做单机视图，暂不支持跨 QMgr）
     */
    suspend fun buildGraph(
        connectionId: String,
        rootName: String,
        rootType: String,
        depth: Int = 1,
    ): TopologyGraph

    /**
     * 触发一次后台刷新（如扫描缓存）。默认空实现；需要拉取 / 同步数据的实现应覆盖，
     * 例如 IBM MQ 在此发起一次对象慢扫，扫完后经 [dataChanged] 通知 UI 重建。
     *
     * 未连接时实现可安全 no-op（扫描会被连接后的定时任务接管）。
     */
    suspend fun refresh(connectionId: String) {}

    /**
     * 数据可用信号：扫描完成等时机发出对应 [connectionId]；UI 订阅后重建图。
     * 默认空流（无需刷新的静态实现无需关心）。
     */
    val dataChanged: Flow<String> get() = emptyFlow()
}
