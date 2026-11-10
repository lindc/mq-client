// TargetFormat 只有在显式配置 targetFormats(...) 时才需要（见文件末尾注释）。
//import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("mq.kotlin-jvm")
    id("mq.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.nucleus.core.runtime)
            implementation(libs.nucleus.window)
            implementation(libs.nucleus.application)
            implementation(libs.material.kolor)
            implementation(compose.material3)
            implementation(compose.components.resources)
            // Material Icons Extended：跨平台一致的矢量图标（Linux 无 Emoji 字体也能正常显示）。
            implementation(compose.materialIconsExtended)

            // Shared UI primitives (PressableButton, …) — both :app-ui and
            // :ai-panel consume these so they stay visually consistent.
            implementation(projects.uiCommon)

            // Right-side AI assistant panel.
            implementation(projects.aiUi)
            // AI 落库（ai_connection / chat_history）——bootstrapEvents 里注册订阅。
            implementation(projects.aiService)

            // AI 核心层：适配器契约 + 注册表 + HTTP 基建（设置页直接查注册表）。
            implementation(projects.aiCore)
            // OpenAI 兼容 provider（一个 provider 一个模块，与 anthropic 平级）。
            implementation(projects.openaiService)
            // OpenAI branding/resources (logo shown when creating a connection).
            implementation(projects.openaiUi)

            // Anthropic AI adapter module — same shape as the OpenAI pair above; both
            // are discovered via SPI at runtime and register into AiAdapterRegistry.
            implementation(projects.anthropicService)
            implementation(projects.anthropicUi)

            // Business modules (discovered via SPI at runtime).
            implementation(projects.ibmMqService)
            implementation(projects.ibmMqUi)
            implementation(projects.kafkaService)
            implementation(projects.kafkaUi)
            implementation(projects.rabbitmqService)
            implementation(projects.rabbitmqUi)
            implementation(projects.rocketmqService)
            implementation(projects.rocketmqUi)
            // OLAP 协议族（S1 骨架）：host 必须显式依赖 service + ui 两个模块，
            // 它们的 META-INF/services 注册文件才会进运行时 classpath 被
            // ModuleRegistry 的 ServiceLoader 扫到（新建连接弹窗的 OLAP 分类
            // 与 StarRocks 卡片由 SPI 动态合并，宿主不硬编码分类）。
            implementation(projects.starrocksService)
            implementation(projects.starrocksUi)
            // 解释器注册表：Main.kt 启动 / 换语言时把解释器的私有 i18n 表合进全局表
            // （ibmMqUi/kafkaUi 的 implementation 依赖不传递，宿主自己声明一份）。
            implementation(projects.messageInterpreterRegistry)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // FileKit：在 main() 里统一 FileKit.init(appId)，所有模块共用同一套文件选择器。
            implementation(libs.filekit.dialogs)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // ModuleI18nMergeTest 要解析各模块 i18n 表的原始 JSON 来算键交集
            // （模块表是全局合并的，重名会静默互相覆盖）。
            implementation(libs.kotlinx.serialization.json)
            // 「新窗口 = 新 Composition」的代价实测（见 DetailCompositionCostTest）。
            implementation(libs.compose.ui.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mqclient.app.MainKt"

        nativeDistributions {
            // 只产「免安装可运行文件夹」，不打 msi/deb/rpm 等安装包：
            //
            //   ./gradlew :app-ui:createDistributable
            //   → build/compose/binaries/main/app/MQClient/
            //       Windows: MQClient.exe + runtime\     双击即可运行
            //       Linux  : bin/MQClient + lib/runtime  ./bin/MQClient 即可运行
            //
            // 因此这里故意不调用 targetFormats(...)。原因有两个：
            //  1. 文件夹（app-image）由 createDistributable 生成，和 targetFormats 无关；
            //  2. compose 插件会在配置阶段拒绝「与当前系统不兼容」的格式
            //     （在 Linux 上写 Dmg 会直接 IllegalStateException），所以不能写死一份列表。
            // 以后需要安装包时再按宿主系统显式加，例如：
            //   targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            // Windows 上想再加安装器时用 TargetFormat.Exe（不要 Msi）。
            //
            // ⚠️ jpackage 只能打「当前系统」的包：Windows 的文件夹必须在 Windows 上构建，
            //    Linux 的必须在 Linux 上构建，不能交叉打包。

            packageName = "MQClient"

            // 版本号只影响包元数据（Linux 的 .desktop、Windows 的 exe 属性）。
            // 若以后要打 MSI，MSI 要求第一段不能是 0，那时再改成 "1.0.0"。
            packageVersion = "0.1.0"

            // 不用 includeAllModules = true，改用显式清单：把 jdk.compiler / jdk.javadoc /
            // jdk.jshell / jdk.jlink / jdk.jpackage 这些开发工具模块打进交付镜像没有意义。
            // 实测（JDK 25.0.5，jmods 齐全）：全部模块 174M，下面这份清单 132M。
            // 另外显式清单也更可预测：镜像里到底有什么，看这一页就知道。
            modules(
                // 聚合模块：一次性带上全部 java.*（java.base/sql/desktop/net.http/naming…）。
                "java.se",
                // TLS/ECC（OpenAI、Anthropic、Kafka SASL_SSL 几乎都会用到）
                "jdk.crypto.ec",
                "jdk.crypto.cryptoki",
                // 很多第三方库（Netty / Kryo / 部分 Kafka client）反射用到 Unsafe
                "jdk.unsupported",
                // GBK/GB18030/ISO-8859-1 等非 UTF-8 字符集：MQ 报文编码换算是刚需，
                // 缺了它 charset 查找会抛 UnsupportedCharsetException。
                "jdk.charsets",
                // 完整 locale 数据：中文日期/数字/排序规则，缺了只剩 en + root。
                "jdk.localedata",
                "jdk.naming.dns",
                "jdk.naming.rmi",
                "jdk.security.auth",
                "jdk.security.jgss",
                "jdk.net",
                "jdk.nio.mapmode",
                "jdk.sctp",
                "jdk.xml.dom",
                "jdk.unsupported.desktop",
                "jdk.accessibility",
                "jdk.management",
                "jdk.management.jfr",
                "jdk.jfr",
                "jdk.zipfs",
                // 部分库（jdk.jdi 同理）通过反射探测；留着以免运行时 NoClassDefFoundError。
                "jdk.httpserver",
                "jdk.jdi",
                "jdk.dynalink",
                "jdk.jsobject",
            )

            // 安装图标放在 packaging/ 而不是 composeResources/drawable/：
            // 同一个 drawable 目录下 logo.png/.ico/.icns 会被 Compose 资源系统当成
            // 同一个 ID `drawable:logo` 的多个候选文件，运行时 painterResource(Res.drawable.logo)
            // 直接抛 "Resource with ID='drawable:logo' has more than one file"。
            windows {
                iconFile.set(project.file("packaging/logo.ico"))
            }
            macOS {
                iconFile.set(project.file("packaging/logo.icns"))
                bundleID = "com.mqclient.app"
            }
            linux {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/logo.png"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Windows 免安装 zip 导出（app-image → zip，非 MSI）：
//
//   ./gradlew :app-ui:zipDistributable
//   → app-ui/build/compose/binaries/main/MQClient-0.1.0-windows-x64.zip
//     解压后 MQClient/MQClient.exe 双击即用（自带精简 runtime，无需装 JDK）。
//
// 为什么需要下面两步修复：WiX 工具集只服务于 msi/exe 安装器，本工程用不到；
// 但 compose 插件在 Windows 上仍会注册 downloadWix / unzipWix（要从 GitHub 下载，
// 内网环境必超时），并把 wixToolsetDir（<root>/build/wix311）注册成 jpackage
// 任务的输入校验 —— 目录不存在直接配置失败。
//
//  1) 禁用 downloadWix / unzipWix（app-image 不调 WiX；以后真要打 MSI 时删掉
//     这段，让 WiX 正常下载）。
//  2) ensureWixToolsetDir 在 jpackage 之前建空目录占位，绕过输入校验。
// ---------------------------------------------------------------------------

// 注意：compose 插件自带 wixToolsetDir 扩展属性，本变量刻意避开同名。
val wixDirPlaceholder = rootProject.layout.buildDirectory.dir("wix311")

// WiX 任务注册在根项目（:downloadWix / :unzipWix），必须用 rootProject.tasks 匹配。
rootProject.tasks.matching { it.name == "downloadWix" || it.name == "unzipWix" }.configureEach {
    enabled = false
}

// compose 插件在 afterEvaluate 才注册 createDistributable / WiX 任务，
// 所以全部接线必须包在 afterEvaluate 里（直接 tasks.named 会报 task not found）。
afterEvaluate {
    val ensureWixToolsetDir = tasks.register("ensureWixToolsetDir") {
        val dir = wixDirPlaceholder
        doLast { dir.get().asFile.mkdirs() }
    }

    tasks.named("createDistributable") {
        dependsOn(ensureWixToolsetDir)
    }

    tasks.register<Zip>("zipDistributable") {
        group = "distribution"
        description =
            "把 createDistributable 的免安装 app-image 压成 zip（Windows 解压即用，非 MSI）。"
        dependsOn(tasks.named("createDistributable"))

        // 产物名与 compose DSL 的 packageName / packageVersion 保持单一来源。
        // 局部变量避开 Zip 任务自带的 name 属性（CopySpec 闭包内会歧义解析）；
        // compose DSL 的两个属性声明为可空，上面已显式赋值，此处断言非空。
        val dist = compose.desktop.application.nativeDistributions
        val pkgName = dist.packageName ?: error("nativeDistributions.packageName is not set")
        val pkgVersion = dist.packageVersion ?: error("nativeDistributions.packageVersion is not set")

        // 按**当前宿主** OS/架构打标签：jpackage 只能打包当前系统（不能交叉），
        // 同一任务在 Linux/macOS 上运行时自动得到对应平台的 zip 名。
        val hostOs = providers.systemProperty("os.name").get().lowercase()
        val osTag = when {
            hostOs.contains("win") -> "windows"
            hostOs.contains("mac") || hostOs.contains("darwin") -> "macos"
            else -> "linux"
        }
        val archTag = when (providers.systemProperty("os.arch").get().lowercase()) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64"
        }
        archiveFileName.set("$pkgName-$pkgVersion-$osTag-$archTag.zip")
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main"))

        // 包一层顶层文件夹：解压后是 MQClient/MQClient.exe，不会把 exe / runtime
        // 散落进解压目标目录。
        from(layout.buildDirectory.dir("compose/binaries/main/app/$pkgName")) {
            into(pkgName)
        }
    }
}
