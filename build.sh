#!/usr/bin/env bash
# ============================================================
#  MQ Client Desktop - Linux / macOS 打包脚本
#
#  产出免安装 zip（jpackage app-image + 精简 JRE），解压后
#  ./MQClient/bin/MQClient 即可运行，无需安装 JDK。
#
#  用法：  ./build.sh [额外 gradle 参数]
#  示例：  ./build.sh                  正常打包（增量，最快）
#          ./build.sh --rerun-tasks    强制重跑打包链
#
#  依赖：JDK 25（必须带 jpackage —— jpackage 只能打包当前系统，
#  不能交叉打包；Linux 的 zip 必须在 Linux 上构建，macOS 同理）。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")"

# JAVA_HOME：优先用环境变量；未设则要求 PATH 上的 java 是 25
if [ -z "${JAVA_HOME:-}" ]; then
    if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version 25'; then
        echo "[INFO] 使用 PATH 上的 java: $(command -v java)"
    else
        echo "[ERROR] 未找到 JDK 25：请 export JAVA_HOME=<JDK25路径> 或把 JDK 25 放进 PATH。" >&2
        exit 1
    fi
fi

if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/jpackage" ]; then
    echo "[ERROR] $JAVA_HOME/bin/jpackage 不存在。" >&2
    echo "        打包必须由带 jpackage 的 JDK 25 运行（compose 桌面打包用的是运行 Gradle 的那个 JDK）。" >&2
    exit 1
fi

echo "[INFO] JAVA_HOME=${JAVA_HOME:-<PATH java>}"
echo "[INFO] 开始打包（首次构建约 1-2 分钟）..."
./gradlew :app-ui:zipDistributable "$@"

echo
echo "[OK] 产物："
ls -lh app-ui/build/compose/binaries/main/MQClient-*.zip
echo
echo 解压后运行 ./MQClient/bin/MQClient 即可（自带精简 JRE，无需装 JDK）。
