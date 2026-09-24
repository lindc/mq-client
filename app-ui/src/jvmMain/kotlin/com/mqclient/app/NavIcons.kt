package com.mqclient.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 导航树 iconId → Material Icons 矢量图的映射（渲染归宿主，见 [ModuleSidebar]）。
 *
 * iconId 由各模块的 `ServiceAdapter.navigationItems` 提供（ibm-mq 模块与条目 id 同值，
 * 见 [com.mqclient.core.NavigationItem.iconId]）；映射放宿主而不是模块 —— app-ui
 * 不依赖各业务 ui 模块，而 Emoji 方案（依赖系统字体，Linux 下是方块）已废弃。
 * 未知 iconId 回退 [Icons.Filled.Circle]，新模块没登记图标时树不会崩、只是图形中性。
 */
internal fun navigationIcon(iconId: String): ImageVector =
    when (iconId) {
        // ===== IBM MQ 工具类 =====
        "topology" -> Icons.Filled.Map
        "command" -> Icons.Filled.Terminal
        // ===== IBM MQ 对象类型 =====
        "queues" -> Icons.Filled.Inbox
        "topics" -> Icons.Filled.Campaign
        "subscriptions" -> Icons.Filled.MarkEmailUnread
        "channels" -> Icons.Filled.Link
        "listeners" -> Icons.Filled.Hearing
        "services" -> Icons.Filled.Settings
        "processDefinitions" -> Icons.Filled.Description
        "namelists" -> Icons.Filled.List
        "authInfo" -> Icons.Filled.Lock
        "commInfo" -> Icons.Filled.SettingsInputAntenna
        else -> Icons.Filled.Circle
    }
