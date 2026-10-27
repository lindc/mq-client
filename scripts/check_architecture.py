#!/usr/bin/env python3
"""分层架构守卫：把 README 里的分层原则变成会失败的检查。

README 「分层原则」写着：`*-service` 不依赖 Compose，`*-ui` 不依赖具体协议实现。
这两条曾各被违反过一次（`ibm-mq-service` 依赖 `mq-ui` 只为拿命令控制台契约；
`MqscSyntaxHighlighter` 在 service 里产出 `AnnotatedString`）—— 文档没有约束力，
所以这里把它做成门禁。

检查项（全部为「机制」，不依赖人的自觉）：

  1. **service 不碰 Compose**：`*-service` 与 `mq-base` 的 build.gradle.kts 里
     不得出现 compose 依赖（`compose.` / `libs.compose*`），
     其 Kotlin 源码不得 `import androidx.compose.*`。
  2. **service 不依赖 UI 模块**：不得引用任何 `projects.*Ui` / `uiCommon` /
     `topologyUi` / `mqUi` / `graphCore`（这些要么是 Compose 模块，要么只服务 UI）。
  3. **protocol UI 不串协议**：`<proto>-ui` 只允许依赖 `<proto>-service`、`ai-service`
     （AI 面板要写 chat_history）与共享层；不得依赖别的协议的 service。
  4. **共享 UI 不依赖任何 service**：`ui-common` / `ui-test-kit` / `mq-ui` /
     `topology-ui` 不得依赖任何 `*-service`。

豁免：
  - `app-ui` 是宿主机，天然组装全部 service（不检查第 3 条）。
  - 单行注释 `// arch-allow: <reason>` 出现在同一行时放行该条（留下痕迹，
    而不是让人改脚本）。

用法：`python3 scripts/check_architecture.py`（verify.sh 会调用）。
退出码 0 = 通过，1 = 有违规（打印到 stderr）。
"""
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---- 模块分类 ---------------------------------------------------------------

SERVICE_MODULES = {"mq-base"}  # 另外所有 *-service 都算
SHARED_UI_MODULES = {"ui-common", "ui-test-kit", "mq-ui", "topology-ui"}
HOST_MODULE = "app-ui"

# service 不得引用的模块（UI 侧或只服务 UI 的模型层）
UI_MODULE_DEPS = {"ui-common", "ui-test-kit", "mq-ui", "topology-ui", "graph-core"}
UI_MODULE_PATTERN = re.compile(r"-[uU]i$")

COMPOSE_BUILD_PATTERN = re.compile(r"\b(compose\.|libs\.compose)")
COMPOSE_IMPORT_PATTERN = re.compile(r"^\s*import\s+androidx\.compose", re.M)
PROJECT_REF_PATTERN = re.compile(r"projects\.([A-Za-z0-9]+)")
ALLOW_COMMENT = "arch-allow:"


def kebab(name: str) -> str:
    """Gradle 类型安全访问器 `ibmMqService` → 模块目录名 `ibm-mq-service`。"""
    return re.sub(r"(?<!^)(?=[A-Z])", "-", name).lower()


def all_modules():
    return sorted(
        d
        for d in os.listdir(REPO)
        if os.path.isfile(os.path.join(REPO, d, "build.gradle.kts"))
    )


def is_service(m: str) -> bool:
    return m.endswith("-service") or m in SERVICE_MODULES


def project_deps(build_file: str):
    """返回 build 文件里声明的模块依赖（kebab 名），跳过带 arch-allow 的行。"""
    deps = set()
    with open(build_file, encoding="utf-8") as handle:
        for line in handle:
            if ALLOW_COMMENT in line:
                continue
            for ref in PROJECT_REF_PATTERN.findall(line):
                deps.add(kebab(ref))
    return deps


def kotlin_sources(module: str):
    root = os.path.join(REPO, module, "src")
    for base, _, files in os.walk(root):
        for name in files:
            if name.endswith(".kt"):
                yield os.path.join(base, name)


def compose_dep_lines(build_file: str):
    """build 文件里出现 compose 依赖的行（跳过注释与 arch-allow 行）。"""
    hits = []
    with open(build_file, encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            stripped = line.strip()
            if stripped.startswith("//") or ALLOW_COMMENT in line:
                continue
            if COMPOSE_BUILD_PATTERN.search(line):
                hits.append((lineno, stripped))
    return hits


def main() -> int:
    problems = []
    modules = all_modules()

    for module in modules:
        build_file = os.path.join(REPO, module, "build.gradle.kts")
        deps = project_deps(build_file)

        # ---- 1 & 2：service 层 ----
        if is_service(module):
            for lineno, line in compose_dep_lines(build_file):
                problems.append(
                    f"{module}/build.gradle.kts:{lineno}: service 层不得依赖 Compose —— {line}"
                )
            ui_deps = (deps & UI_MODULE_DEPS) | {d for d in deps if UI_MODULE_PATTERN.search(d)}
            for dep in sorted(ui_deps):
                problems.append(
                    f"{module}/build.gradle.kts: service 层不得依赖 UI 模块 `{dep}`"
                    "（命令控制台等契约实现请放对应 *-ui）"
                )
            for path in kotlin_sources(module):
                with open(path, encoding="utf-8", errors="replace") as handle:
                    text = handle.read()
                match = COMPOSE_IMPORT_PATTERN.search(text)
                if match:
                    rel = os.path.relpath(path, REPO)
                    line_no = text[: match.start()].count("\n") + 1
                    problems.append(
                        f"{rel}:{line_no}: service 层源码不得 import Compose"
                        "（语法高亮 / AnnotatedString 之类请放 *-ui）"
                    )

        # ---- 3：protocol UI 不串协议（宿主机豁免）----
        if module.endswith("-ui") and module != HOST_MODULE and module not in SHARED_UI_MODULES:
            own_service = module[: -len("-ui")] + "-service"
            for dep in sorted(d for d in deps if d.endswith("-service")):
                if dep in (own_service, "ai-service"):
                    continue
                problems.append(
                    f"{module}/build.gradle.kts: protocol UI 不得依赖别的协议的 service `{dep}`"
                    f"（只允许 `{own_service}` 与 `ai-service`）"
                )

        # ---- 4：共享 UI 不依赖任何 service ----
        if module in SHARED_UI_MODULES or module == "ui-common":
            for dep in sorted(d for d in deps if d.endswith("-service")):
                problems.append(
                    f"{module}/build.gradle.kts: 共享 UI 模块不得依赖 service `{dep}`"
                )

    if problems:
        print("架构检查失败：", file=sys.stderr)
        for problem in problems:
            print(f"  ✗ {problem}", file=sys.stderr)
        return 1

    print(f"OK：{len(modules)} 个模块的分层约束全部满足（service 无 Compose、UI 不串协议）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
