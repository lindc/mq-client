package com.mqclient.starrocks.ui

import com.mqclient.starrocks.catalog.CatalogPartition

/** 分区加载状态：引擎不支持与「无分区」必须区分，不能都显示空。 */
internal sealed interface PartitionLoadState {
    data object Loading : PartitionLoadState
    data object Unsupported : PartitionLoadState
    data class Loaded(val partitions: List<CatalogPartition>) : PartitionLoadState
}

/** 右侧详情面板的选中节点。字段不再是节点 —— 树只负责导航，字段在右侧表格里展示。 */
internal sealed interface CatalogSelection {
    data object Database : CatalogSelection
    data class Table(val name: String) : CatalogSelection
}
