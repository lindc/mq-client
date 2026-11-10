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
 * ## 分类与卡片元数据的归属（契约事实）
 *
 * [AdapterUI] 只声明 `serviceId` / `title` / `i18nTables` / `content` /
 * `contentFor` —— **没有 `category` / `name` / `description`**。新建连接弹窗的
 * "OLAP" 分类由配对的 `StarRocksServiceAdapter.category` 提供（值严格引用
 * `StarRocksConstants.CATEGORY` → `OlapCategory.CATEGORY`，不硬编码），
 * 卡片名称 / 描述同样来自 `ServiceAdapter.name` / `description`（描述是
 * i18n 键，宿主 `toMiddlewareType` 查表翻译）。
 * 本类通过 [serviceId] 与它配对（`ModuleRegistry` 按 id 校验）。
 *
 * ## SPI 注册
 *
 * 已通过 `src/jvmMain/resources/META-INF/services/com.mqclient.core.AdapterUI`
 * 注册；宿主还需在 `app-ui/build.gradle.kts` 声明 `implementation` 依赖，
 * 注册文件才会进入运行时 classpath 被 ServiceLoader 发现。
 */
class StarRocksAdapterUI : AdapterUI {

    /** 与 service 侧 `ServiceAdapter.id` 一致。 */
    override val serviceId: String = StarRocksConstants.PROTOCOL_ID

    /** 标题为协议专有名词（与 KafkaUI.title 同口径，非可翻译文案）。 */
    override val title: String = StarRocksConstants.PROTOCOL_NAME

    /**
     * 分类（**配对契约自证**）：宿主分组实际读取 `ServiceAdapter.category`，
     * 本属性供装配测试断言 UI 与 service 两侧分类一致（防漂移），值严格引用
     * [StarRocksConstants.CATEGORY] → `OlapCategory.CATEGORY`，不硬编码。
     */
    val category: String = StarRocksConstants.CATEGORY

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
