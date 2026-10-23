# Gitee 流水线接入（本仓库的 CI 落地说明）

本仓库的门禁命令只有一份：`scripts/verify.sh`（`assemble` + `jvmTest` + 两轮 i18n 检查）。
本地和 CI 调的是同一个脚本，所以「本地过、CI 挂」只可能来自环境差异，不来自命令漂移。

## 先说清楚一件事：Gitee 流水线没有「仓库内配置文件」

GitHub Actions 是 *配置即代码*：`.github/workflows/ci.yml` 提交进仓库，平台读文件执行。
Gitee 流水线（官方文档 `/enterprise/pipeline`）是 *控制台即配置*：**新建 → 选模版 → 选代码源 → 网页上编排阶段和任务**，
每一个任务绑定一个插件（`/enterprise/pipeline/plugin/*`），保存后平台自动向仓库注册 Webhook
（`/enterprise/pipeline/source/trigger`）。

结论：
- 仓库里**不存在**可以提交的 `.gitee/ci.yml` 一类文件；接入 CI 必须有人在网页上点一次，约 5 分钟。
- 本仓库能落地的是三样东西：门禁脚本（已有）、这份操作手册、以及让脚本能被插件环境驱动的开关
  （`verify.sh` 的 `GRADLE_CMD`）。
- `.github/workflows/ci.yml` 保留不动。它只在 GitHub 上生效；哪天 GitHub 通了，把模版做一次搬迁即可，
  命令部分完全复用 `scripts/verify.sh`。

## 一次性配置（网页操作）

1. 打开仓库页 `https://gitee.com/benbear_335/mq-client`，找顶部的「流水线」入口
   （项目设置 → 流水线设置 里可以建分组、变量、模版）。
2. 「新建流水线」→ 模版选 Gradle / Java → 确认。
3. **代码源**：Gitee → 本仓库 → 分支 `master`。
4. 建一个阶段「构建与测试」，往里加一个任务，插件选 **Gradle 构建**（`/enterprise/pipeline/plugin/gradle-compile`）。
   该插件的字段与本项目取值：

   - **JDK 版本号**：`25`（硬要求，全部模块 `jvmToolchain(25)`；下拉里没有 25 时见「风险 1」）
   - **Gradle 版本号**：`9.7.1`（与 `gradle/wrapper/gradle-wrapper.properties` 一致）
   - **构建命令**（执行目录 = 仓库根）：

     ```sh
     set -e
     chmod +x ./gradlew ./scripts/verify.sh
     command -v python3 >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq python3)
     ./scripts/verify.sh
     ```

     `python3` 那行是给 i18n 检查用的：插件镜像里若没有 python，`verify.sh` 会在 i18n 段失败。
   - **构建缓存**：填 `~/.gradle/caches` 和 `~/.gradle/wrapper` 两行。第一次冷构建要下几十 MB 依赖，
     缓存命中后每次省 10 分钟以上。
   - **暂存构建物**：本仓库不需要发布制品，留空。

   插件默认把依赖源换成阿里云镜像（文档「构建加速」一节）。本项目的依赖来自 Maven Central
   （Kotlin / Compose Multiplatform / Ktor / SQLDelight 都在 Central），阿里云 public 有镜像，够用。

5. **触发**：分支推送 → 精确匹配 `master`。可选文件过滤：`src/**.kt`、`gradle/**`、`scripts/**`，
   避免只改文档也跑一遍 20 分钟。
6. 保存。平台自动注册 Webhook。
7. 手动点一次「运行」，**逐行读日志**：确认 JDK 版本、Gradle 版本、`gradle: ./gradlew` 那行、以及最后的
   `OK: 全部通过（assemble + jvmTest + i18n）`。看到 `BUILD SUCCESSFUL` 不算通过 —— 任务是 `UP-TO-DATE`
   时 Gradle 也会打这行，而 `verify.sh` 自己的 OK 行只在三段全过之后才出现。

## 时长与额度

- 冷启动（无缓存）：依赖下载 + 编译 + 508 个用例，约 20~30 分钟。
- 命中缓存：约 5~10 分钟。
- 额度（`/enterprise/pipeline/billing`）：每月赠送 1000 核分 ≈ 2C4G 跑 500 分钟，月末清零；
  折合每月约 50 次（有缓存）到 20 次（无缓存）全量构建。个人项目做 push 校验够用。
  超出要买加时包（500 核分 15 元），或降频（只在推 `master` 时跑）。
- 计费页面属于**企业版**定价表；`/enterprise/pipeline/use` 又写着「Gitee 项目下均有流水线功能」。
  两者是否都适用于个人仓库，需要在仓库页实际看一眼有没有入口 —— 这是唯一没法从文档确认的事。

## 风险与出路（按发生可能性排序）

### 1. JDK 25 不在插件的下拉里

现象：`No matching toolchains found for requested specification: {languageVersion=25, vendor=any}`。
原因：`gradle.properties` 里写了 `org.gradle.java.installations.auto-download=false`，工具链只能用镜像里已装的 JDK。

出路（代价从低到高）：
- 下拉里如果有 25 → 直接选，结束。
- 改用插件 **基于镜像的脚本执行**（`/enterprise/pipeline/plugin/image-script-run`），镜像 `eclipse-temurin:25-jdk`，
  构建命令同上。版本完全可控；代价是拉 docker.io 镜像（国内可能很慢，需要加速器）。
- 用 **Shell 脚本执行** 插件（`/enterprise/pipeline/plugin/shell-script-run`）把任务跑在自己纳管的主机上
  （需先在平台做主机纳管 + SSH Key；主机上要装 JDK 25）。
- 把 `jvmToolchain(25)` 降级 —— **不建议**：要改 30 个 build 文件，还会撞 Kotlin 2.4 / Compose 1.12 的版本矩阵。

### 2. `./gradlew` 要联网下 Gradle 发行包

现象：卡在 `Downloading https://services.gradle.org/distributions/gradle-9.7.1-bin.zip` 然后超时
（本机实测 services.gradle.org 不可达；Gitee 构建机在国内，未必通）。

出路：构建命令改成 `GRADLE_CMD=gradle ./scripts/verify.sh`，用插件下拉提供的 Gradle。
`verify.sh` 已支持这个开关，默认仍是 `./gradlew`。
注意下拉版本要与 wrapper 一致（9.7.1），否则 `settings.gradle.kts` 里的
`enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` 行为会漂。

### 3. 镜像里没有 python3

现象：`!! 找不到 python3 / python，跳过 i18n 检查` 之后脚本以非 0 退出。
出路：第 4 步的构建命令已经带 `apt-get install -y python3`；若镜像是无 apt 的精简发行版，
退而求其次改成 `./gradlew assemble jvmTest --console=plain` 先上编译+单测，i18n 检查留本地。

### 4. Compose UI 测试在无显示环境

只有 `ibm-mq-ui` / `kafka-ui` 的 `TopologyCanvasNodeDragUiTest` 走 `runComposeUiTest`。
已确认它们用 Compose 自带的输入注入，没有碰 `java.awt.Robot`，headless 下通常能过。
若真报 AWT / Display 相关错误，先试 `export JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`
（**未验证**：可能反过来让 Compose 的 AWT 渲染路径失败），再不行就把这两个测试留在本地跑。

## 和 GitHub Actions 的对应关系

- `on.push.branches` → 流水线的「触发」：分支推送 + 精确匹配 `master`
- `jobs` → 阶段
- `steps` → 阶段里的任务
- `run: ./scripts/verify.sh` → Gradle 构建插件的「构建命令」
- `setup-java` / `setup-gradle` → 插件的 JDK 版本号 / Gradle 版本号下拉
- `actions/upload-artifact` → 任务的「暂存构建物」（失败时把 `**/build/test-results/jvmTest/*.xml` 打包，
  便于下载后本地看；不填也行，日志里已经有失败用例名）

## 本地等价命令

```sh
./scripts/verify.sh              # 全量：assemble + jvmTest + i18n（Git Bash / Linux / macOS）
./scripts/verify.sh --i18n-only  # 只查 i18n，几秒钟
```

Windows PowerShell 侧对应 `scripts/verify.ps1`（`-I18nOnly` 同上）。

## 这份文档没验证到的部分

- Gitee 流水线的实际界面与下拉选项：没有账号权限，无法登录查看。JDK 25 是否存在、插件是否有
  「构建缓存」字段，都以网页实际渲染为准。
- 上述配置从未在 Gitee 上真跑过。第一次运行请按第 7 步逐行读日志，不要只看红绿。
- 500 分钟/月的额度换算基于文档的 1000 核分 ≈ 2C4G·500 分钟，实际扣减以平台账单为准。
