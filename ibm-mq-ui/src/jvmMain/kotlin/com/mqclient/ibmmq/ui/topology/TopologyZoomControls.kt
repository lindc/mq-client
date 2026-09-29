package com.mqclient.ibmmq.ui.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 右上角控件：重新扫描（数据强制刷新）+ 放大 / 缩小 / 适应视图 / 重置。
 *
 * 动作接线（kuiver 0.4.2 真实 API，`KuiverViewerState` 没有 fitToContent/resetZoom）：
 *  - 重新扫描 → 面板触一次 `scanObjects`（扫完经 OBJECTS_SCANNED 重建）；
 *  - 放大 / 缩小 → `zoomIn()` / `zoomOut()`（步长走 config.zoomStep）；
 *  - 适应视图 → `centerGraph(animated = true)`（按当前图内容居中适配）；
 *  - 重置 → `clearManualPositions()` + `centerGraph()`（回到布局初始态）。
 */
@Composable
internal fun TopologyZoomControls(
    onRescan: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitContent: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZoomButton(Icons.Filled.Sync, "重新扫描对象", onRescan)
        ZoomButton(Icons.Filled.ZoomIn, "放大", onZoomIn)
        ZoomButton(Icons.Filled.ZoomOut, "缩小", onZoomOut)
        ZoomButton(Icons.Filled.FitScreen, "适应视图", onFitContent)
        ZoomButton(Icons.Filled.Refresh, "重置", onReset)
    }
}

@Composable
private fun ZoomButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
