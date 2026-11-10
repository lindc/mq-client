package com.mqclient.starrocks.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.AdapterUI
import com.mqclient.starrocks.StarRocksConstants

/**
 * StarRocks UI 层的 [AdapterUI] SPI **空骨架**实现。
 *
 * Skeleton only, protocol logic pending：所有入口只返回 [StarRocksPlaceholder]，
 * 不做任何数据绑定、不读取连接状态、不触网。
 *
 * ## 为什么放在 jvmMain（而非 commonMain）
 *
 * 父接口 [AdapterUI] 定义在 app-core 的 **jvmMain** 源集（KMP commonMain
 * 看不到它，KafkaUI / IbmMqUI 同款位置）。纯 Composable 占位页放在
 * commonMain，SPI 装配类放 jvmMain。
 *
 * ## i18n
 *
 * 占位文案键 `olap.starrocks.*` 放在 ui-common 的全局语言表（本模块不注册
 * `i18nTables`）—— 空骨架没有 `META-INF/services` 注册文件，模块私有表
 * 不会被加载，全局表是唯一能正确渲染的落点。
 *
 * ## 刻意未注册 SPI
 *
 * 宿主通过 `META-INF/services/com.mqclient.core.AdapterUI` 发现 UI 模块。
 * 本模块不提供该注册文件，也不改 app-ui 的依赖 / 注册逻辑（超出空模块边界）。
 */
class StarRocksAdapterUI : AdapterUI {

    /** 与 service 侧 `ServiceAdapter.id` 一致。 */
    override val serviceId: String = StarRocksConstants.PROTOCOL_ID

    /** 标题为协议专有名词（与 KafkaUI.title 同口径，非可翻译文案）。 */
    override val title: String = StarRocksConstants.PROTOCOL_NAME

    /** 主内容 = 占位页。 */
    override val content: @Composable () -> Unit = { StarRocksPlaceholder() }

    /**
     * 任意导航项都返回占位页（不解析 objectType / connectionId）。
     * 返回 null 会让宿主回落到通用 coming-soon；这里显式给出占位页，
     * 保证空骨架的可见行为由本模块自己定义。
     */
    override fun contentFor(objectType: String, connectionId: String): (@Composable () -> Unit)? =
        { StarRocksPlaceholder() }
}
