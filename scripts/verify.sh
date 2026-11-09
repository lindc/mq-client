#!/usr/bin/env bash
# 质量门禁 —— **本地与 CI 跑的是同一份命令**。
#
# 为什么抽成脚本而不是把命令写死在 workflow 里：两边一旦各写一份，就会漂移
# （CI 多跑一步、本地少跑一步），而「本地通过但 CI 挂」是最浪费时间的一类失败。
# 这里只有一个真相：CI 调这个脚本，你本地也调这个脚本。
#
# 用法：
#   ./scripts/verify.sh               # 全量：分层检查 + 编译 + 全部单测 + i18n
#   ./scripts/verify.sh --i18n-only   # 只跑 i18n 检查（改文案时用，几秒钟）
#   ./scripts/verify.sh --arch-only   # 只跑分层架构检查（改 build.gradle.kts 时用）
#
# 退出码：0 = 全部通过；非 0 = 有失败（CI 据此判定）

set -euo pipefail

cd "$(dirname "$0")/.."

I18N_ONLY=0
ARCH_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --i18n-only) I18N_ONLY=1 ;;
        --arch-only) ARCH_ONLY=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

find_python() {
    if command -v python3 > /dev/null 2>&1; then
        echo python3
    elif command -v python > /dev/null 2>&1; then
        echo python
    else
        echo ""
    fi
}

# ---------- 分层架构检查 ----------
#
# README 写着「*-service 不依赖 Compose / *-ui 不依赖具体协议实现」。这两条曾各被
# 违反过一次（ibm-mq-service 依赖 mq-ui、MqscSyntaxHighlighter 在 service 里产出
# AnnotatedString）。文档没有约束力，所以做成会失败的检查。
run_arch_check() {
    PY="$(find_python)"
    if [ -z "$PY" ]; then
        echo "!! 找不到 python3 / python，跳过分层架构检查" >&2
        return 1
    fi
    echo "--- architecture check ---"
    PYTHONIOENCODING=utf-8 "$PY" scripts/check_architecture.py
}

if [ "$ARCH_ONLY" -eq 1 ]; then
    run_arch_check
    echo "OK: architecture checks passed"
    exit 0
fi

# ---------- i18n 键引用检查 ----------
#
# 覆盖三类问题：静态引用写错键名、运行时拼接的键查不到、同一张表两种语种键集合不相等。
# 两种语言各跑一次：脚本以 LANG 决定「已知键」的取表范围，只跑一种会漏掉另一种的差异。
run_i18n_check() {
    PY="$(find_python)"
    if [ -z "$PY" ]; then
        echo "!! 找不到 python3 / python，跳过 i18n 检查" >&2
        return 1
    fi
    # 脚本自身 print 中文，非 UTF-8 终端会 UnicodeEncodeError（Windows cp1252 实测）。
    for lang in zh-CN en-US; do
        echo "--- i18n check ($lang) ---"
        PYTHONIOENCODING=utf-8 "$PY" scripts/check_i18n_refs.py "$lang"
    done
}

if [ "$I18N_ONLY" -eq 1 ]; then
    run_i18n_check
    echo "OK: i18n checks passed"
    exit 0
fi

# ---------- 分层架构 ----------
run_arch_check

# ---------- 编译 + 全部单元测试 ----------
#
# `assemble` 保证所有模块的产物都能构建（含资源处理 / SQLDelight 生成）。
#
# **必须同时跑 `test` 与 `jvmTest`**：去 KMP 之后（be0076fb），18 个纯 kotlin-jvm
# 模块（ibm-mq-service / kafka-service / rabbitmq-service / rocketmq-service /
# mq-base / message-interpreter-* …）的测试任务是 `test`，只有 Compose 模块才有
# `jvmTest`。只跑 `jvmTest` 时这些模块的用例**一个都不会执行**，而输出仍然是
# BUILD SUCCESSFUL —— 门禁看起来是绿的，实际漏了三分之一以上的用例。
# Gradle 对没有该任务的项目会跳过，所以两条一起写是安全的。
#
# 真机测试（*LiveTest / *DiagTest）在没有 config.toml 时自行 SKIP，所以这条在
# 干净环境里也能过 —— 不会因为 CI 没有 broker 而红。
echo "--- assemble + test + jvmTest ---"
# GRADLE_CMD overrides the wrapper, for CI images that pre-install Gradle:
#   GRADLE_CMD=gradle ./scripts/verify.sh
# It must stay one executable (a name or a path), no arguments. Keep the version
# in sync with gradle/wrapper/gradle-wrapper.properties.
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
echo "gradle: $GRADLE_CMD"
"$GRADLE_CMD" assemble test jvmTest --console=plain

# ---------- i18n ----------
run_i18n_check

echo "OK: 全部通过（architecture + assemble + test + jvmTest + i18n）"
