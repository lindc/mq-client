package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mqclient.core.AdapterUI
import com.mqclient.starrocks.StarRocksConstants
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

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
 * ## 创建表单（S2）
 *
 * 「新建连接」第二步的创建面板**不是** [AdapterUI] 的方法，而是独立的
 * `ConnectionFormUi` SPI：[StarRocksConnectionForm] 经
 * `META-INF/services/com.mqclient.core.ConnectionFormUi` 注册后，宿主
 * `NewConnectionDialog` 按 [serviceId] 装配它，原先的
 * 「该类型的创建表单将在后续版本提供」占位提示随之消失。
 * [content] / [contentFor] 仍返回 [StarRocksPlaceholder] —— 那是**主内容区**
 * （连接建好后的 Tab 页面），与创建表单是两条不同的路径。
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
     * 按导航项（objectType）分发主内容区：
     * - `console` → SQL 控制台 Tab 布局占位页（[StarRocksSqlConsolePlaceholder]）；
     * - 其余四个已知导航项 → 「模块开发中」占位（标题按各节点的 i18n 键翻译）；
     * - 未知 objectType → 模块通用占位页。
     *
     * 导航项 id 与 `StarRocksServiceAdapter.navigationItems` 严格对齐；真实面板
     * 落地时逐项替换（与 RocketMQ 的 when(objectType) 分发同构）。
     */
    override fun contentFor(objectType: String, connectionId: String): (@Composable () -> Unit)? =
        when (objectType) {
            "console" -> { { StarRocksSqlConsolePlaceholder() } }
            "overview", "catalog", "topology", "queries" -> {
                { DevelopingPlaceholder("olap.starrocks.menu.$objectType") }
            }
            else -> { { StarRocksPlaceholder() } }
        }

    /** 「XX 模块开发中」占位：标题按导航项自己的 i18n 键翻译后拼进模板。 */
    @Composable
    private fun DevelopingPlaceholder(titleKey: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource("olap.starrocks.menu.developing")
                    .withArg(stringResource(titleKey)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
