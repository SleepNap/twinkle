#!/usr/bin/env bash
# ============================================================
# twinkle 服务端启动脚本（架构 M5-3 上线切换：默认 single 档）
#
# 前置：构建好 twinkle-server.jar；WZ 数据在 ./wz，脚本在 ./scripts
# 环境变量覆盖（不设置用默认值）：
#   TWINKLE_DB_URL      数据库 JDBC（默认 jdbc:sqlite:./data/twinkle.db）
#   TWINKLE_HTTP_HOST   管理 HTTP 绑定（默认 127.0.0.1，红线 20 网络平面收敛）
#   TWINKLE_WZ_PATH     WZ 数据路径（默认 ./wz）
#   TWINKLE_SCRIPT_PATH 脚本路径（默认 ./scripts）
# ============================================================
set -euo pipefail

JAR="${TWINKLE_JAR:-target/twinkle-server.jar}"
[ -f "$JAR" ] || { echo "错误：找不到 $JAR，请先构建（mvn -B verify）"; exit 1; }

export TWINKLE_DB_URL="${TWINKLE_DB_URL:-jdbc:sqlite:./data/twinkle.db}"
export TWINKLE_HTTP_HOST="${TWINKLE_HTTP_HOST:-127.0.0.1}"
export TWINKLE_WZ_PATH="${TWINKLE_WZ_PATH:-./wz}"
export TWINKLE_SCRIPT_PATH="${TWINKLE_SCRIPT_PATH:-./scripts}"

echo "==> 启动 twinkle（profile=single，HTTP 绑 $TWINKLE_HTTP_HOST，DB=$TWINKLE_DB_URL）"
exec java -jar "$JAR" --profile=single
