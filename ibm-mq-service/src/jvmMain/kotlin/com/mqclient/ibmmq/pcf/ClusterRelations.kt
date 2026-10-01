package com.mqclient.ibmmq.pcf

/**
 * 本地队列管理器在集群中的角色：
 *  - FULL：完整仓库（DISPLAY QMGR 的 REPOS 字段非空，承载集群完整对象库）；
 *  - PARTIAL：部分仓库（有集群成员，但自身不承载完整对象库）；
 *  - NONE：不属于任何集群（REPOS 为空且 INQUIRE CLUSQMGR 无成员）。
 */
enum class QmgrClusterRole { FULL, PARTIAL, NONE }

/**
 * 集群成员（DISPLAY CLUSQMGR 的一条响应）：本地 QMGR 视角下的另一个队列管理器，
 * 以及连接它的集群发送通道。
 *
 * [qmType]：该成员的仓库角色（REPOS = 完整仓库 / NORMAL = 部分仓库，MQIA_CLUSTER_Q_TYPE
 * → MQQMT_* 的可读名）；[defType]：通道定义方式（CLUSSDR = 手动定义 / CLUSSDRA =
 * 集群接收方自动生成，MQIACH_DEFTYPE → 可读名）。
 */
data class ClusterMember(
    /** 成员队列管理器名。 */
    val qmgrName: String,
    /** 所属集群名。 */
    val clusterName: String,
    /** 通往该成员的集群发送通道名。 */
    val channelName: String,
    /** 成员仓库角色（REPOS / NORMAL）。 */
    val qmType: String,
    /** 通道定义方式（CLUSSDR / CLUSSDRA）。 */
    val defType: String,
    /** 通道连接名（host(port)），查询不到为 null。 */
    val conName: String?,
    /** 通道运行状态（RUNNING / RETRYING / STOPPED…），查询不到为 null。 */
    val status: String?,
)

/**
 * 集群关系查询结果（消息详情级的「一次查询全量返回」）：
 * 本地 QMgr 的集群角色 + 视角内的全部成员。
 *
 * [members] 是 DISPLAY CLUSQMGR 的实时结果（不经慢扫缓存）——成员关系要新鲜数据；
 * 本地 QMgr 自身不在列表里（它是视角本身，UI 单独画节点）。
 */
data class ClusterRelations(
    /** 本地 QMgr 的集群角色。 */
    val role: QmgrClusterRole,
    /** 完整仓库场景下的集群名（REPOS 字段值）；PARTIAL / NONE 为 null。 */
    val reposClusterName: String?,
    /** 本地队列管理器名（INQUIRE Q_MGR 的 QMNAME）。 */
    val localQmgrName: String,
    /** 视角内的集群成员（不含本地自身）。 */
    val members: List<ClusterMember>,
)
