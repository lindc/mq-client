package com.mqclient.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mqclient.core.CoreModule
import com.mqclient.core.ModuleRegistry
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.dialog.DialogSizeClass
import com.mqclient.uicommon.dialog.rememberStandardDialogSize
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTableSection
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.logo
import org.jetbrains.compose.resources.painterResource

/**
 * 「关于」对话框：Material 3 关于卡片。
 *
 * 版式（自上而下）：
 *  1. 标题栏 —— 「关于」与关闭按钮，无彩色背景条，与卡片同色；
 *  2. 品牌区 —— 左侧 logo（72dp）+ 右侧三行文字（名称 / 版本 / 副标题），
 *     **整块水平居中**，文字块内部左对齐；
 *  3. 能力清单 —— 按类目拆两组（MQ 中间件 / AI 模型服务），各自一个
 *     [PropertyTableSection]（斑马纹 + Key 左 / Value 右），与全项目属性面板同款；
 *  4. 操作区 —— 「查看开源许可证」（描边次级）与「关闭」（实心主按钮）水平居中；
 *  5. 版权页脚 —— 与标题栏同色、居中一行小字。
 *
 * 品牌区为什么改成左右横排：单列居中时 logo 压在三行文字上方，72dp 的图形把整块
 * 拉得很高而文字每行都短，视觉上「上重下轻」；横排后 logo 与文字并置，高度从
 * ~190dp 降到 ~78dp，省下的垂直空间正好给两张属性表，整体更紧凑。
 *
 * 为什么不用 hand-rolled 列表：`ServiceRow` 用 `VerticalDivider` 当分隔线的写法与
 * 全项目的属性面板不是一套视觉（没有斑马纹、没有统一的 Key/Value 列宽）。改用
 * ui-common 的 [PropertyTableSection] 后，这里与 IBM MQ / RabbitMQ 的属性面板逐像素
 * 一致。
 *
 * 字体：**刻意不设 `fontFamily`** —— 全局 Typography 已经带了用户在设置里选的字体
 * （默认是内置的霞鹜文楷 Mono），在这里写死任何一个字族都会覆盖用户选择。
 *
 * 行数：6 行拆两段 = 4×40+3×8 + 2×40+8 = 272dp（[PropertyTableSection] 行高
 * `heightIn(min = 40.dp)` + 行距 8dp）。窗口尺寸走公共规范（FORM，屏幕 60%），
 * 不再写死 620×700 —— 内容用 `fillMaxSize` 跟随窗口自适应。
 */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    var showLicense by remember { mutableStateOf(false) }
    val dialogSize = rememberStandardDialogSize(DialogSizeClass.FORM)
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Root box so the license dialog can overlay the card.
        Box {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.size(dialogSize.width, dialogSize.height),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 1. 标题栏：无彩色底，标题直接写「关于」。
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource("about_title"),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    HorizontalDivider()

                    // 2–4. 品牌区 / 能力清单 / 操作按钮。
                    // 整块内容共用一个滚动容器：窗口被拖矮或模块变多时能滚，但正常
                    // 尺寸下不挂 VerticalScrollbar —— Compose Desktop 的 verticalScroll
                    // 自己只接管滚动、不画滚动条轨道，所以这里看不到任何滚动条。
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                    ) {
                        HeaderBlock()
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        if (mqServices.isNotEmpty()) {
                            PropertyTableSection(
                                title = stringResource("about_section_mq"),
                                items = mqServices.toPropertyItems(),
                            )
                        }
                        if (mqServices.isNotEmpty() && aiServices.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                        }
                        if (aiServices.isNotEmpty()) {
                            PropertyTableSection(
                                title = stringResource("about_section_ai"),
                                items = aiServices.toPropertyItems(),
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            SecondaryButton(
                                onClick = { showLicense = true },
                            ) { Text(stringResource("license_view")) }
                            Spacer(Modifier.width(8.dp))
                            PrimaryButton(onClick = onDismiss) {
                                Text(stringResource("common_close"))
                            }
                        }
                    }

                    // 5. 版权页脚。
                    HorizontalDivider()
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "© 2026 lindc. All rights reserved.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showLicense) {
                LicenseDialog(onDismiss = { showLicense = false })
            }
        }
    }
}

/**
 * 品牌区：左 logo、右三行文字。
 *
 * 对齐方式分两层，别混为一谈：
 *  - **外层 `Row(Arrangement.Center)`**：Row 撑满宽度后把「logo + 间距 + 文字块」
 *    这**一整块**放在水平正中 —— 居中的是内容块，不是每一行文字。
 *  - **内层 `Column(Alignment.Start)`**：三行文字的**左边缘**对齐成一条竖线；这个
 *    左对齐只作用于 Column 内部，不会把整块从窗口中心挪走。
 *
 * 之所以用「Row 居中」而不是「Column 居中 + 文字居中对齐」：三行文字长度差别很大
 * （名称短、副标题长），文字若也居中，视觉上会散成三块；让它们左对齐成一条基线才
 * 读得出「这是一个信息块」。
 *
 * `FontWeight.Bold` 在默认内置字体下**不会真的变粗**：`Theme.kt` 用
 * `FontSynthesis.None`（内置字族只有 Regular，合成假粗会糊成另一种字体），所以
 * Bold 请求会回落到 Regular；层级靠 `headlineSmall` 的字号撑起来。保留 Bold 是为了
 * 让意图留在代码里 —— 用户若在设置里选了带 Bold 的系统字体，它就会真的生效。
 */
@Composable
private fun HeaderBlock() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.logo),
            contentDescription = stringResource("about_logo_description"),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                stringResource("about_app_name"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource("about_version"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource("about_description_line1"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 类目名。字面量而非引用 `MQConstants.CATEGORY` / 适配器常量：app-ui 通过
 * `implementation(projects.ibmMqService)` 依赖各模块，拿不到它们自身的编译类路径
 * （`mq-base` 是那些模块的 implementation 依赖，不外泄）。取值与
 * `ServiceAdapter.category` 的文档约定一致：`"MQ"`（mq-base 各模块）/ `"AI"`
 * （openai / anthropic 适配器）。
 */
private const val CategoryMq = "MQ"
private const val CategoryAi = "AI"

/**
 * 能力清单：按 [ServiceAdapter.category][com.mqclient.core.ServiceAdapter.category]
 * 分组，数据仍来自 SPI 扫描（新增模块自动出现），只是把「MQ」与「AI」分开呈现 ——
 * 把 Anthropic / OpenAI 列在「中间件」标题下是错的。按名称排序，保证跨次启动稳定。
 */
private val servicesByCategory: Map<String, List<Library>> by lazy {
    ModuleRegistry.services
        .sortedBy { it.name }
        .groupBy { it.category }
        .mapValues { (_, list) -> list.map { Library(it.name, it.protocol) } }
}

/**
 * 「MQ 中间件」组：MQ 类目 + **任何既非 MQ 也非 AI 的类目**。
 *
 * 兜底是刻意的：将来若新增第三个类目而不改这里，那些模块至少还能出现在 About 里。
 * 静默丢失（分组过滤掉但没人展示）比归错组更难被发现。
 */
private val mqServices: List<Library> by lazy {
    servicesByCategory
        .filterKeys { it != CategoryAi }
        .values
        .flatten()
        .sortedBy { it.name }
}

/** 「AI 模型服务」组。 */
private val aiServices: List<Library> by lazy {
    servicesByCategory[CategoryAi].orEmpty()
}

/**
 * 转成属性表行。`copyable = false`：复制「IBM MQ」这种键名没有意义，而且会多出
 * 一个行尾图标与组件内置的「已复制」Snackbar；关掉后 [PropertyTableSection] 会用
 * 同宽 Spacer 占位，分栏线仍然逐行对齐。
 */
private fun List<Library>.toPropertyItems(): List<PropertyItem> =
    map { PropertyItem(key = it.name, value = it.protocol, copyable = false) }

/**
 * 「开源许可证」弹窗：分两组展示 —— 运行时环境（内置 JVM，运行时现读）与第三方
 * 依赖（随源码提交的 manifest）。
 *
 * 运行时那一行为什么不写进 `third-party-component.txt`：manifest 是静态清单，而
 * jpackage 打进安装包的 JDK 随打包平台 / 版本而变。写死在文本里迟早与现实脱节，
 * 所以改成从 [System.getProperty] 现读。
 *
 * 两组共用 [LicenseList] 里的**同一个**滚动容器：分组是逻辑分区，不是两个滚动区。
 *
 * 运行时那一行的路径下面还有一个「查看全文」按钮（[FullLicenseDialog]）：合规上
 * 要求用户能看到许可正文，光是告知路径等于把“去找文件”这件非必需工作交给用户。
 */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    // Load the third-party list lazily from the packaged resource. The file is
    // bundled under composeResources/files/ so the Compose Resources Gradle
    // plugin picks it up; each line is "name,license,copyright".
    val components by produceState<List<ThirdPartyComponent>>(initialValue = emptyList()) {
        value = loadThirdPartyComponents()
    }
    // JVM 在进程生命周期内不会变，读一次就够。
    val runtimeEntry = remember { runtimeLicenseEntry() }
    // 点「查看全文」时把定位结果传出去；null = 没打开。
    var openedLicense by remember { mutableStateOf<BundledLicense?>(null) }
    val sections =
        listOf(
            LicenseSection(
                title = stringResource("license_section_runtime"),
                entries = listOf(runtimeEntry),
            ),
            LicenseSection(
                title = stringResource("license_section_third_party"),
                entries =
                    components.map {
                        LicenseEntry(name = it.name, license = it.license, copyright = it.copyright)
                    },
            ),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { SecondaryButton(onClick = onDismiss) { Text(stringResource("common_close")) } },
        title = { Text(stringResource("license_title")) },
        text = {
            LicenseList(
                sections = sections,
                onShowFullText = { openedLicense = it },
                modifier = Modifier.size(460.dp, 420.dp),
            )
        },
    )
    // 嵌套弹窗声明在本 composable 里（而 LicenseDialog 本身在 AboutDialog 的 Dialog
    // content lambda 内）：窗口归属不能跑出承载它的那个窗口，参见 ObjectDetailDialog。
    openedLicense?.let { license ->
        FullLicenseDialog(license = license, onDismiss = { openedLicense = null })
    }
}

/**
 * 内置运行时的许可摘要行。
 *
 * 三个属性都取自当前进程，不硬编码 "Temurin 25"：
 *  - `java.vm.name` → `OpenJDK 64-Bit Server VM`（VM 实现名；OpenJ9 则是
 *    `Eclipse OpenJ9 VM`）；
 *  - `java.version` → `25.0.4.1`（运行时版本号）；
 *  - `java.vendor.version` → `Temurin-25.0.4.1`（发行版标识）。
 *
 * 为什么要带 vendor 版本：`java.vm.name` 只说明 VM 实现，Temurin / Zulu / Corretto
 * 报的是同一个名字，合规上「说明来源发行版」需要这一项。
 *
 * 三个属性在规范里都允许缺失（裁剪过的自定义运行时），所以各自带兜底 —— 宁可显示
 * "unknown"，也不让界面出现 "null"。
 *
 * 协议与版权是**字面量**，无法推导：没有任何系统属性会告诉你这份 JDK 用的是哪份
 * 许可。GPLv2+CE 是 OpenJDK 系发行版（Temurin / Zulu / Corretto …）的通行许可；
 * 若将来换成商业发行版（Oracle JDK 等），这两行必须人工更正 —— 这是合规点。
 *
 * 名称拼装：有 vendor 版本时写成 `OpenJDK 64-Bit Server VM 25.0.4.1
 * (Temurin-25.0.4.1)`，缺失时退回 `OpenJDK 64-Bit Server VM 25.0.4.1`，不留一对
 * 空括号。
 *
 * 地址以**纯文本**展示（不可点）：弹窗内可点文字需要 hover 反馈与无障碍语义，而
 * 合规要求的只是「让用户看到来源地址」；需要访问时复制即可。
 *
 * 另外单独给出完整许可证文本的**相对位置**（见 [locateBundledLicense]）：GPLv2
 * 要求随二进制分发完整许可文本，只写一句「GPLv2+CE」是摘要，不是许可正文。
 */
private fun runtimeLicenseEntry(): LicenseEntry {
    val vmName = System.getProperty("java.vm.name").orEmpty().trim().ifBlank { "Java Virtual Machine" }
    val version = System.getProperty("java.version").orEmpty().trim().ifBlank { "unknown" }
    val vendorVersion = System.getProperty("java.vendor.version").orEmpty().trim()
    return LicenseEntry(
        name = if (vendorVersion.isEmpty()) "$vmName $version" else "$vmName $version ($vendorVersion)",
        license = "GPLv2 + Classpath Exception (GPLv2+CE)",
        copyright = "Copyright © 2026 Eclipse Foundation, Inc.",
        bundledLicense = locateBundledLicense(),
        url = "https://adoptium.net",
    )
}

/**
 * 完整许可证文本相对**当前软件目录**的位置，例如 `runtime/legal/java.base/LICENSE`。
 *
 * 三条硬约束，缺一条这个提示就成了假的：
 *  1. **不输出绝对路径** —— `java.home` 在开发态是构建用的 JDK，绝对路径会带出
 *     `C:\Development\…` 这类本机目录，发给用户既无意义又泄露开发机结构；
 *  2. **基准目录必须是用户能打开的那个** —— jpackage 布局下是安装目录（`runtime/`
 *     的上一级），开发态没有安装目录，基准退回 `java.home`（运行时目录），文案
 *     跟着换；绝不把 JDK 里的位置说成「本软件安装目录下」；
 *  3. **显示前再校验一次** —— 路径里一旦出现 `..`，就说明文件不在基准目录下面，
 *     贴进安装目录是找不到的，那就整行不显示。不做这一步，前面的探测只是一次猜，
 *     而路径一旦印在界面上就会被当成事实。
 *
 * 探测两份已知布局：jlink 镜像自身（`java.home/legal/java.base/LICENSE`，jpackage
 * 打包后即 `<安装目录>/runtime/legal/…`）与 `legal/` 被提到安装根目录的布局。
 *
 * 都找不到时返回 null 并报错误通道：宁可这一行不显示，也不给一条指向空位置的路径
 * —— 同时这条错误能让打包侧发现 `legal/` 被裁剪了。
 */
private fun locateBundledLicense(): BundledLicense? {
    val rawHome = System.getProperty("java.home").orEmpty().trim()
    val javaHome = rawHome.takeIf { it.isNotEmpty() }?.let { path -> runCatching { Paths.get(path) }.getOrNull() }
    if (javaHome == null) {
        reportMissingLicense(rawHome)
        return null
    }
    // jpackage 把运行时镜像放在 <安装目录>/runtime；开发态 / 直接跑 jlink 镜像时
    // java.home 本身就是基准，没有可退的上级。
    val installRoot = javaHome.parent?.takeIf { javaHome.fileName?.toString() == RuntimeDirName }
    val found =
        buildList {
            add(javaHome.resolve(LicenseRelativePath))
            installRoot?.let { add(it.resolve(LicenseRelativePath)) }
        }.firstOrNull { Files.isRegularFile(it) }
    if (found == null) {
        reportMissingLicense(rawHome)
        return null
    }
    val base = installRoot ?: javaHome
    val relative = base.relativize(found)
    // 真正的校验：相对路径里一旦出现 `..`，就说明文件并不在基准目录下面，把这条
    // 路径贴到安装目录里是找不到的 —— 宁可整行不显示。正常布局不会走到这里，但它
    // 拦的是「上游把 java.home 指到别处」这类改动，而不是靠约定。
    if (relative.any { it.toString() == ".." } || !Files.isRegularFile(base.resolve(relative))) {
        reportMissingLicense(rawHome)
        return null
    }
    return BundledLicense(
        relativePath = relative.toString().replace(File.separatorChar, '/'),
        directory = found.parent,
        insideInstallDir = installRoot != null,
    )
}

/**
 * 许可正文的定位结果。
 *
 * 指向的是 `legal/java.base/` **目录**而不是单个文件：GPLv2+CE 的“CE”是独立文件
 * （`ASSEMBLY_EXCEPTION`），只读 `LICENSE` 只能展示一半正文，完整许可需要把同目录下
 * 的必需文件都读出来（见 [LicenseFileNames]）。
 */
private data class BundledLicense(
    /** 相对安装目录（jpackage 布局）或 `java.home`（开发态）的路径，分隔符统一为 `/`。 */
    val relativePath: String,
    /** 真实目录（`legal/java.base`）。**不展示**给用户，只给读文件用。 */
    val directory: Path,
    /** 基准是安装目录（true）还是运行时目录（false）—— 决定文案怎么说。 */
    val insideInstallDir: Boolean,
)

/** 找不到就把原因报到错误通道：界面不显示路径，但打包侧能从这里发现 `legal/` 被裁了。 */
private fun reportMissingLicense(javaHome: String) {
    val homeText = javaHome.ifEmpty { "缺失" }
    CoreModule.reportError(
        message = "内置运行时的完整许可证文件未找到（java.home=$homeText）",
        source = "app-ui",
    )
}

/** jpackage 安装目录里运行时镜像的固定目录名。 */
private const val RuntimeDirName = "runtime"

/** jlink 镜像里 java.base 许可正文的固定相对路径（`legal/` 由 jlink 默认拷贝）。 */
private const val LicenseRelativePath = "legal/java.base/LICENSE"

/** Path to the bundled third-party-component manifest inside composeResources. */
private const val ThirdPartyComponentPath = "files/third-party-component/third-party-component.txt"

/**
 * Parses the third-party-component manifest into [ThirdPartyComponent] rows.
 *
 * Uses an RFC 4180 subset: a field wrapped in double quotes may contain
 * commas and newlines; a literal `"` inside a quoted field is escaped as `""`.
 * Records that don't split into the three expected fields are skipped so a
 * stray blank line or a malformed entry never crashes the dialog.
 */
private suspend fun loadThirdPartyComponents(): List<ThirdPartyComponent> =
    try {
        parseComponents(Res.readBytes(ThirdPartyComponentPath).decodeToString())
    } catch (error: Exception) {
        // 读不出来 = 对话框空白，用户只会当成程序坏了 —— 报到错误通道上去。
        CoreModule.reportError(
            message = "第三方组件清单读取失败，许可证列表暂时为空",
            source = "app-ui",
            error = error,
        )
        emptyList()
    }

/** One row in the About dialog's capability tables (name → protocol). */
private data class Library(
    val name: String,
    val protocol: String,
)

/** 许可证弹窗的一组：一个标题 + 若干条目。 */
private data class LicenseSection(
    val title: String,
    val entries: List<LicenseEntry>,
)

/**
 * 许可证弹窗里的一行。
 *
 * [bundledLicense] / [url] 目前只有运行时那一行有：manifest 是三列格式
 * （name,license,copyright），不值得为这几行信息去改文件格式；第三方条目两者均为
 * null，渲染时整行省略。
 *
 * [bundledLicense] 是**已校验过**的相对位置（基准目录由 [BundledLicense.insideInstallDir]
 * 区分）加一个用于读文件的实际位置；展示只用相对路径。
 */
private data class LicenseEntry(
    val name: String,
    val license: String,
    val copyright: String,
    val bundledLicense: BundledLicense? = null,
    val url: String? = null,
)

/**
 * 滚动条占位（放在滚动内容内部，而不是给 `VerticalScrollbar` 加 padding）。
 *
 * Compose Desktop 的 `VerticalScrollbar` 是**盖在内容上**画的，不占布局空间：默认宽约
 * 12dp。内容如果不给自己留右边距，行尾字符就落在滚动条下面 —— 看起来就是「半截
 * 字」。取 16dp = 12dp 条宽 + 4dp 间隙，拇指 hover 变宽时也不会蹭到文字。
 *
 * 为什么加在滚动内容上：`Modifier.verticalScroll(...).padding(end = ...)` 的 padding
 * 位于滚动容器的**内部**，内容宽度因此缩短；反过来先 padding 再 verticalScroll，
 * padding 会被算进滑动区，滚动时内容仍会滑到条下面。
 */
private val ScrollbarGutter = 16.dp

/**
 * 分组许可证列表，斑马纹逐行交替 —— 每组从非斑马色重新起算，组内自成节奏：
 * 组与组之间因此多出一道同色底的呼吸缝，标题头也永远落在素底上。
 *
 * 两组合用**同一个** [ScrollState] 与 `Column`：分组是逻辑分区，不是两个滚动区。
 * 两个 `verticalScroll` 会各画一条滚动条，还会把「翻到第二组」拆成两段式操作。
 *
 * 标题沿用 `PropertyTableSection` 的样式（titleSmall + primary + 8dp 下距），与
 * About 主页面那两张能力表、以及各属性面板的分组标题一致。没有复用 `PropertyTable`
 * 本体：它是 Key/Value 两列 + 斑马纹的表格，而这里一条要三行（名称 / 版权 / 许可），
 * 塞进两列会丢内容。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LicenseList(
    sections: List<LicenseSection>,
    onShowFullText: (BundledLicense) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        // [ScrollbarGutter] 不能省：滚动条是 overlay，不留槽就会压住行尾文字。
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = ScrollbarGutter)) {
            sections.forEachIndexed { sectionIndex, section ->
                // 顶部不留空行：第一组标题紧贴视口上沿。
                if (sectionIndex > 0) Spacer(Modifier.height(16.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                section.entries.forEachIndexed { index, entry ->
                    LicenseRow(entry = entry, zebra = index % 2 == 1, onShowFullText = onShowFullText)
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 单条：名称 / 版权 / 许可（+ 可选完整许可证路径与来源地址），底色与内边距与改动前
 * 逐像素一致。
 *
 * 完整许可证提示分成**两行**：一行说明（普通 Text，不可选）+ 一行路径（包在
 * [SelectionContainer] 里）。分开是刻意的 —— 拖选复制要拿到的必须只有路径本身，
 * 连说明文案一起带走就得手工裁剪，而用户复制它的目的就是去文件管理器里粘。
 *
 * 路径行**不做截断**（无 `maxLines` / `Ellipsis`）：截断后的字符串含省略号，复制
 * 出去就是个无效路径，与「复制后一定找得到」直接冲突。相对路径只有三四个段，
 * 460dp 下不会有观感问题。
 *
 * 路径下面再接一个「查看全文」（[SecondaryButton]，与 About 页的「查看开源许可证」
 * 同级）：告知路径只能算「告知」，合规要求的是用户能看到正文；点了直接渲染文件
 * 内容，不用离开应用、也不用去翻文件管理器。
 *
 * 地址用 `onSurfaceVariant` 而不是 `primary`：它不可点，涂成主色会让人以为能点。
 */
@Composable
private fun LicenseRow(
    entry: LicenseEntry,
    zebra: Boolean,
    onShowFullText: (BundledLicense) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (zebra) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.copyright,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "License: ${entry.license}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entry.bundledLicense?.let { license ->
            Text(
                text =
                    stringResource(
                        if (license.insideInstallDir) "license_full_text_install" else "license_full_text_runtime",
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(
                    text = license.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            SecondaryButton(
                onClick = { onShowFullText(license) },
                standardMinWidth = false,
            ) { Text(stringResource("license_full_text_view")) }
        }
        entry.url?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 要读的许可文件，按展示顺序。
 *
 * `LICENSE` + `ASSEMBLY_EXCEPTION` 是一对：前者是 GPLv2 正文，后者是 Classpath
 * Exception 例外条款；只有两份都在，界面上那句「GPLv2 + Classpath Exception」才
 * 有正文支撑。`ADDITIONAL_LICENSE_INFO` 说明哪些文件适用 CPE、以及包里还带了哪
 * 些其它许可，属同一法律文件族，存在就一并展示。
 */
private val LicenseFileNames = listOf("LICENSE", "ASSEMBLY_EXCEPTION", "ADDITIONAL_LICENSE_INFO")

/** 缺少任一就视为“许可正文不完整”，整个弹窗报错，而不是默默展示半份。 */
private val RequiredLicenseFileNames = setOf("LICENSE", "ASSEMBLY_EXCEPTION")

/** 许可正文的读取上限：GPLv2 全文约 19KB，1MB 已足够宽松，超出说明不是许可文件。 */
private const val MaxLicenseTextBytes = 1_000_000L

/** 正文行高与字号的比例。GPLv2 是密排法条，1.5 倍行距是能连续读下去的下限。 */
private const val LicenseLineHeightRatio = 1.5f

/**
 * 当成“重复标题”看待的首行长度上限。
 *
 * 设这道阀是为了让 [isDuplicateTitle] 的 `endsWith` 不至于误删正文：GPLv2 正文里有
 * 很多以 "…General Public License" 结尾的句子，但它们都是整句，远超这个长度；
 * 真正的标题行（如 `OPENJDK ASSEMBLY EXCEPTION`，25 字符）远低于它。
 */
private const val MaxDuplicateTitleLength = 60

/** 一份许可文件的内容（[body] 已经过 [normalizeLicenseBody] 清理）。 */
private data class LicensePart(
    val fileName: String,
    val body: String,
)

/**
 * 展示前的文本清理：去掉首尾空白、把连续空行折叠成一个、删掉与文件名重复的首行标题。
 *
 * 三者各自解决一个具体问题，且都只动排版、不动条款：
 *  1. **首行标题去重** —— `ASSEMBLY_EXCEPTION` 正文的第一行就是
 *     `OPENJDK ASSEMBLY EXCEPTION`，而上面已经渲染了文件名标题，两行叠在一起看着
 *     像出错。规则见 [isDuplicateTitle]，匹配不上时保留原行。
 *  2. **连续空行折叠** —— 文件里段落间偶尔有两个、三个空行（换行符风格混用也会
 *     造成），渲染后就是一大片白。统一折成一个空行：段落分隔保留，空白不留。
 *  3. **行尾空白清除** —— 法务文本里常见行尾空格，对显示无影响，但会让 `Text`
 *     的行宽计算偏大、右对齐处出现细碎空隙。
 *
 * **行首空白不动**：GPLv2 用缩进区分子条款（如 `    This program is free software;`），
 * 吃掉它会把版式结构改掉。
 */
private fun normalizeLicenseBody(fileName: String, raw: String): String {
    val lines = raw.lines().map { it.trimEnd() }
    val firstContent = lines.indexOfFirst { it.isNotBlank() }
    if (firstContent < 0) return ""
    val skipHead = if (isDuplicateTitle(lines[firstContent], fileName)) 1 else 0
    val out = StringBuilder()
    var pendingBlank = false
    for (line in lines.drop(firstContent + skipHead)) {
        if (line.isBlank()) {
            pendingBlank = out.isNotEmpty()
            continue
        }
        if (pendingBlank) out.append('\n').append('\n')
        out.append(line).append('\n')
        pendingBlank = false
    }
    // StringBuilder.trimEnd() 返回 CharSequence（Kotlin 的 CharSequence.trimEnd 扩展），
    // 而函数声明返回 String，必须显式 toString() —— 少了它在 25 号工具链上是硬编译错误。
    return out.trimEnd().toString()
}

/**
 * 首行是否只是文件名的重复标题。
 *
 * 比较前把两边都归一化成“只留字母数字 + 小写”，所以 `ASSEMBLY_EXCEPTION` /
 * `assembly-exception` / `Assembly Exception` 视为同一个。再用 `endsWith` 容一层
 * 发行版前缀：`openjdkassemblyexception` 以 `assemblyexception` 结尾，于是被识别为
 * 重复。
 *
 * `LICENSE` 的首行 `The GNU General Public License (GPL)` 归一化后是
 * `thegnugeneralpubliclicensegpl`，不以 `license` 结尾，所以它会被保留 —— 这是对的：
 * 那是法条自己的正式名称（与文件名 `LICENSE` 含义不同），删掉反而丢信息。
 *
 * 长度阀 [MaxDuplicateTitleLength] 是不可省的：没有它，`endsWith("license")` 会把
 * 正文里以 “GNU General Public License” 结尾的短句也当成重复标题删掉。
 */
private fun isDuplicateTitle(line: String, fileName: String): Boolean {
    if (line.length > MaxDuplicateTitleLength) return false
    val title = line.filter { it.isLetterOrDigit() }.lowercase()
    val name = fileName.filter { it.isLetterOrDigit() }.lowercase()
    return name.isNotEmpty() && (title == name || title.endsWith(name))
}

/**
 * 读取 `legal/java.base/` 下所有必需与可选许可文件。
 *
 * 在 `Dispatchers.IO` 上读：项目约定 `JobScheduler` 不做线程切换，阻塞 IO 由调用方
 * 自己切出去（见 CLAUDE.md）；这里虽是几 KB 的小文件，但「小」是文件布局的属性、
 * 不是 API 保证。
 *
 * 必需文件缺失时抛异常（而不是继续渲染）：展示一半正文而标题仍写 GPLv2+CE，比前者
 * 更糟 —— 它会让人以为已经看到了全部条款。
 *
 * 读出来的原文直接过 [normalizeLicenseBody]：清理放在这里而不是渲染处，是为了让
 * 「屏幕上的文字」与「复制的文字」永远是同一份字符串，两边不同步是这类弹窗最容易
 * 埋下的坑。
 */
private suspend fun readLicenseParts(license: BundledLicense): List<LicensePart> =
    withContext(Dispatchers.IO) {
        LicenseFileNames.mapNotNull { fileName ->
            val file = license.directory.resolve(fileName)
            if (!Files.isRegularFile(file)) {
                check(fileName !in RequiredLicenseFileNames) { "缺少必需的许可文件：$fileName" }
                return@mapNotNull null
            }
            val size = Files.size(file)
            require(size <= MaxLicenseTextBytes) { "$fileName 过大，拒绝渲染（$size 字节）" }
            LicensePart(fileName = fileName, body = normalizeLicenseBody(fileName, Files.readString(file)))
        }
    }

/**
 * 「复制全文」用的纯文本：每份文件前加一行文件名做分隔，粘出去后仍能分清哪段是哪份。
 * 复制内容与屏幕上的分段一一对应，不存在“屏幕上看到但复制不到”的段落。
 */
private fun List<LicensePart>.toPlainText(): String =
    joinToString(separator = "\n\n") { part -> "${part.fileName}\n${part.body}" }

/**
 * 「完整许可证文本」只读弹窗。
 *
 * 三态显式分开，而不是一条 try/catch 撑完：
 *  - 加载中：`null`，一行提示（文件在本地磁盘，这个态通常一闪而过）；
 *  - 成功：可拖选、可滚动的分段正文；
 *  - 失败：报错文案，用户可以退回上一层按路径直接打开 —— 路径提示的价值就在这。
 *
 * 「复制全文」放在 `dismissButton` 槽位（AlertDialog 的左位），与右下角的「关闭」
 * 分开：一个是拿文本走，一个是关窗，语义不同，不应该挤在一起。文本未加载完时
 * 按钮置灰 —— 复制空字符串只会让你以为它坏了。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullLicenseDialog(license: BundledLicense, onDismiss: () -> Unit) {
    val loadResult by produceState<Result<List<LicensePart>>?>(initialValue = null, license) {
        value =
            runCatching { readLicenseParts(license) }
                .onFailure { error ->
                    CoreModule.reportError(
                        message = "完整许可证文本读取失败",
                        source = "app-ui",
                        error = error,
                    )
                }
    }
    val parts = loadResult?.getOrNull()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // stringResource 不能在 onClick lambda 里调（那里不是 @Composable 上下文），
    // 所以先在这里取出来。
    val copiedMessage = stringResource("msg_detail_copied")
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            SecondaryButton(
                onClick = {
                    clipboard.setText(AnnotatedString(parts.orEmpty().toPlainText()))
                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                },
                enabled = parts != null,
            ) { Text(stringResource("license_full_text_copy")) }
        },
        confirmButton = { SecondaryButton(onClick = onDismiss) { Text(stringResource("common_close")) } },
        title = { Text(stringResource("license_full_text_title")) },
        text = {
            Box(Modifier.size(560.dp, 480.dp)) {
                // `when (val result = ...)` 而不是无主语的 `when`：只有带主语 + `null`
                // 分支，Kotlin 才会在 else 分支里把 `result` 智能转换成非空。无主语的
                // `when` 各分支条件互不推导，`result.isSuccess` 会因可空接收者报错。
                when (val result = loadResult) {
                    null ->
                        Text(
                            text = stringResource("license_full_text_loading"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else ->
                        if (result.isSuccess) {
                            LicenseTextBody(
                                parts = result.getOrThrow(),
                                relativePath = license.relativePath,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = stringResource("license_full_text_failed"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                }
                // Snackbar 贴在正文区底部居中：AlertDialog 的按钮行在窗口底部边缘，
                // 直接放进 content 才能看见「已复制」。
                ThemedSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    )
}

/**
 * 许可正文的阅读排版。
 *
 * 字体固定为 [FontFamily.SansSerif]，**故意不跟随全局字体**：全局默认是霞鹜文楷 Mono
 * （等宽），适合表格与日志，不适合连续法条正文 —— 等宽字体的字面宽窄一致、单词间
 * 空隙变弱，长段落里眼睛难定位行首。
 *
 * 字号取主题的 `bodyMedium` 实际值（默认 14sp，会跟随用户设置的全局字号缩放）
 * 而不是硬写 14.sp：用户在设置里把字号调大到 18sp，许可正文应当一起变大，
 * 否则就成了全应用唯一读不清的一段。行高按 [LicenseLineHeightRatio] 取字号 1.5 倍
 * （默认 14sp → 21sp）。
 */
@Composable
private fun licenseBodyStyle(): TextStyle {
    val base = MaterialTheme.typography.bodyMedium
    val fontSize = base.fontSize.takeIf { it.isSpecified } ?: 14.sp
    return base.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = fontSize,
        // 用 `.value * ratio` 再 `.sp`，不用 `fontSize * ratio`：TextUnit 的乘号是
        // androidx.compose.ui.unit 下的顶层扩展，得额外 import 才能用；而 `Float.sp`
        // 已被本文件的 `import androidx.compose.ui.unit.sp` 覆盖。两者结果完全一致。
        lineHeight = (fontSize.value * LicenseLineHeightRatio).sp,
    )
}

/**
 * 许可正文的可滚动渲染：逐份文件渲染（标题 + 正文，段间分割线），尾部一行来源目录。
 *
 * 为什么不用 Tab / 下拉：正文很长，Tab 一次只能看一份，用户要翻两份才能确定「GPLv2
 * 到底附了什么例外」；顺序排列时例外条款就在正文后面，滑到底就是。而且展开全文只有
 * 一个滚动源，与上一层的列表保持一致。
 *
 * 外层 [SelectionContainer] 包住所有段落：用户可能要把 GPLv2 与 CPE 一起拷给法务。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LicenseTextBody(
    parts: List<LicensePart>,
    relativePath: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val bodyStyle = licenseBodyStyle()
    Box(modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(end = ScrollbarGutter),
        ) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    parts.forEachIndexed { index, part ->
                        if (index > 0) {
                            Spacer(Modifier.height(20.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(16.dp))
                        }
                        Text(
                            text = part.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(
                            text = part.body,
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
