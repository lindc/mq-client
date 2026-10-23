#!/usr/bin/env bash
# 质量门禁 —— **本地与 CI 跑的是同一份命令**。
#
# 为什么抽成脚本而不是把命令写死在 workflow 里：两边一旦各写一份，就会漂移
# （CI 多跑一步、本地少跑一步），而「本地通过但 CI 挂」是最浪费时间的一类失败。
# 这里只有一个真相：CI 调这个脚本，你本地也调这个脚本。
#
# 用法：
#   ./scripts/verify.sh              # 全量：编译 + 全部单测 + i18n 检查
#   ./scripts/verify.sh --i18n-only  # 只跑 i18n 检查（改文案时用，几秒钟）
#
# 退出码：0 = 全部通过；非 0 = 有失败（CI 据此判定）

set -euo pipefail

cd "$(dirname "$0")/.."

I18N_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --i18n-only) I18N_ONLY=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# ---------- i18n 键引用检查 ----------
#
# 覆盖三类问题：静态引用写错键名、运行时拼接的键查不到、同一张表两种语种键集合不相等。
# 两种语言各跑一次：脚本以 LANG 决定「已知键」的取表范围，只跑一种会漏掉另一种的差异。
run_i18n_check() {
    if command -v python3 > /dev/null 2>&1; then
        PY=python3
    elif command -v python > /dev/null 2>&1; then
        PY=python
    else
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

# ---------- 编译 + 全部单元测试 ----------
#
# `assemble` 保证所有模块的产物都能构建（含资源处理 / SQLDelight 生成），
# `jvmTest` 跑全仓 508 个用例。真机测试（*LiveTest）在没有 config.toml 时自行 SKIP，
# 所以这条在干净环境里也能过 —— 不会因为 CI 没有 broker 而红。
echo "--- assemble + jvmTest ---"
./gradlew assemble jvmTest --console=plain

# ---------- i18n ----------
run_i18n_check

echo "OK: 全部通过（assemble + jvmTest + i18n）"
