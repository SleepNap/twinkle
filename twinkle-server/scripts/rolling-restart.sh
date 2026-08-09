#!/usr/bin/env bash
# ============================================================
# twinkle 升级滚动脚本（架构 M6 第 7 节：频道逐个滚动升级，服务不中断）
#
# 原理：频道进程是故障隔离单元——一个频道崩了不连累全服。
# 升级 = 逐频道：把玩家 CC 迁出 → 重启该频道进程（换新 jar）→ 玩家 CC 回来。
# 玩家视角只是"换了一次频道"（架构 4.7 兜底性）。
#
# 用法：
#   ./scripts/rolling-restart.sh [频道号] [协调者端口]
#     无参数：滚动重启全部频道（逐个）
#     指定频道号：只重启该频道
#
# 前置：split 档已在跑（scripts/split-start.sh）；新 jar 已就位 target/twinkle-server.jar。
# 玩家迁移：调用管理 HTTP 把在线玩家踢下线（触发 CC 兜底），或直接重启（客户端回服务器列表重连）。
# ============================================================
set -euo pipefail

JAR="${TWINKLE_JAR:-target/twinkle-server.jar}"
HTTP_BASE="${TWINKLE_HTTP_BASE:-http://127.0.0.1:8080}"
COORD_HOST="${TWINKLE_COORDINATOR_HOST:-127.0.0.1}"
COORD_PORT="${TWINKLE_COORDINATOR_PORT:-8510}"
CHANNEL_HOST="${TWINKLE_CHANNEL_HOST:-127.0.0.1}"

CHANNEL_COUNT="${TWINKLE_CHANNEL_COUNT:-1}"
TARGET="${1:-all}"   # all / 具体频道号
[ -f "$JAR" ] || { echo "错误：找不到 $JAR"; exit 1; }

# 单个频道滚动重启：迁移玩家（可选）→ 重启进程 → 验证注册
restart_channel() {
  local cid="$1"
  local port=$((8584 + cid - 1))
  echo ""
  echo "==> 滚动重启频道 $cid（端口 $port）"

  # 1) 触发该频道进程优雅重启（管理进程经 AdminService RPC 到频道：DRAINING → FLUSH → 退出）。
  #    exit=true 时频道进程编排完 System.exit，由本脚本拉起新进程。
  local resp
  resp="$(curl -s -X POST "$HTTP_BASE/admin/v1/restart" || echo '{"accepted":false}')"
  echo "    restart 请求: $resp"

  # 2) 等待旧频道进程退出（协调者注册表里该频道消失）
  echo "    等待频道 $cid 下线..."
  for i in $(seq 1 60); do
    if ! curl -s "$HTTP_BASE/admin/v1/channels" | grep -q "\"channelId\":$cid"; then
      break
    fi
    sleep 0.5
  done

  # 3) 拉起新频道进程（同一 jar 换新版本）
  echo "    启动新频道 $cid"
  java -jar "$JAR" --twinkle.profile=split-channel --twinkle.role=channel \
    --twinkle.net.channel.id="$cid" --twinkle.net.channel.host="$CHANNEL_HOST" --twinkle.net.channel.port="$port" \
    --twinkle.coordinator.host="$COORD_HOST" --twinkle.coordinator.port="$COORD_PORT" \
    > "logs/channel-$cid.log" 2>&1 &
  local new_pid=$!
  echo "    新频道 $cid pid=$new_pid (log: logs/channel-$cid.log)"

  # 4) 验证新频道注册（coordinator 注册表重建）
  echo "    等待频道 $cid 重新注册..."
  for i in $(seq 1 60); do
    if curl -s "$HTTP_BASE/admin/v1/channels" | grep -q "\"channelId\":$cid"; then
      echo "    频道 $cid 已注册（升级完成）"
      return 0
    fi
    sleep 0.5
  done
  echo "!! 频道 $cid 重启后未在时限内注册，请查 logs/channel-$cid.log"
  return 1
}

if [ "$TARGET" = "all" ]; then
  echo "==> 滚动升级全部 $CHANNEL_COUNT 个频道"
  for ((cid=1; cid<=CHANNEL_COUNT; cid++)); do
    restart_channel "$cid"
  done
else
  restart_channel "$TARGET"
fi

echo ""
echo "==> 滚动升级完成。验证："
echo "    curl $HTTP_BASE/admin/v1/channels  # 全部频道在线"
