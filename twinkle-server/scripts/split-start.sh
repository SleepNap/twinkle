#!/usr/bin/env bash
# ============================================================
# twinkle split 档启动脚本（架构 M6：单机多进程分布式特例，走 loopback）
#
# 拓扑：1 个 coordinator（管理进程：coordinator+login+admin+http+ai）
#      + N 个 channel 进程（每频道 1 个，端口 8584 + 频道序号）
#
# 环境变量覆盖（不设置用默认值）：
#   TWINKLE_CHANNEL_COUNT    频道进程数（默认 1）
#   TWINKLE_DB_URL           数据库 JDBC（默认 jdbc:sqlite:./data/twinkle.db，多进程共享同一库）
#   TWINKLE_COORDINATOR_PORT coordinator 内部端口（默认 8510）
#   TWINKLE_HTTP_HOST        管理 HTTP 绑定（默认 127.0.0.1，红线 20）
#   TWINKLE_WZ_PATH / TWINKLE_SCRIPT_PATH / TWINKLE_CHANNEL_HOST
#
# 注意：2C2G 强制单进程（红线 15）——split 仅限大内存机器/多机。
# ============================================================
set -euo pipefail

JAR="${TWINKLE_JAR:-target/twinkle-server.jar}"
[ -f "$JAR" ] || { echo "错误：找不到 $JAR，请先构建（mvn -B verify）"; exit 1; }

export TWINKLE_CHANNEL_COUNT="${TWINKLE_CHANNEL_COUNT:-1}"
export TWINKLE_DB_URL="${TWINKLE_DB_URL:-jdbc:sqlite:./data/twinkle.db}"
export TWINKLE_COORDINATOR_HOST="${TWINKLE_COORDINATOR_HOST:-127.0.0.1}"
export TWINKLE_COORDINATOR_PORT="${TWINKLE_COORDINATOR_PORT:-8510}"
export TWINKLE_HTTP_HOST="${TWINKLE_HTTP_HOST:-127.0.0.1}"
export TWINKLE_WZ_PATH="${TWINKLE_WZ_PATH:-./wz}"
export TWINKLE_SCRIPT_PATH="${TWINKLE_SCRIPT_PATH:-./scripts}"
export TWINKLE_CHANNEL_HOST="${TWINKLE_CHANNEL_HOST:-127.0.0.1}"

# ---- 1) coordinator（管理进程） ----
echo "==> 启动 coordinator（管理进程，内部端口 $TWINKLE_COORDINATOR_PORT）"
java -jar "$JAR" --twinkle.profile=split-channel --twinkle.role=coordinator \
  --twinkle.coordinator.host="$TWINKLE_COORDINATOR_HOST" --twinkle.coordinator.port="$TWINKLE_COORDINATOR_PORT" \
  > logs/coordinator.log 2>&1 &
COORD_PID=$!
echo "coordinator pid=$COORD_PID (log: logs/coordinator.log)"

# 等 coordinator 内部端口就绪（避免频道进程先连失败）
for i in $(seq 1 30); do
  if (exec 3<>/dev/tcp/"$TWINKLE_COORDINATOR_HOST"/"$TWINKLE_COORDINATOR_PORT") 2>/dev/null; then
    exec 3>&- 3<&-
    break
  fi
  sleep 0.5
done

# ---- 2) N 个频道进程 ----
PIDS=()
for ((cid=1; cid<=TWINKLE_CHANNEL_COUNT; cid++)); do
  port=$((8584 + cid - 1))
  echo "==> 启动频道 $cid（端口 $port）"
  java -jar "$JAR" --twinkle.profile=split-channel --twinkle.role=channel \
    --twinkle.net.channel.id="$cid" --twinkle.net.channel.host="$TWINKLE_CHANNEL_HOST" --twinkle.net.channel.port="$port" \
    --twinkle.coordinator.host="$TWINKLE_COORDINATOR_HOST" --twinkle.coordinator.port="$TWINKLE_COORDINATOR_PORT" \
    > "logs/channel-$cid.log" 2>&1 &
  PIDS+=($!)
  echo "channel-$cid pid=${PIDS[-1]} (log: logs/channel-$cid.log)"
done

echo ""
echo "==> split 档已启动：coordinator($COORD_PID) + $TWINKLE_CHANNEL_COUNT 频道进程"
echo "    管理控制台: http://$TWINKLE_HTTP_HOST:8080/admin/v1/health"
echo "    验证频道注册: curl http://$TWINKLE_HTTP_HOST:8080/admin/v1/channels"
echo "    停止: kill $COORD_PID ${PIDS[*]}"

trap 'echo "==> 停止全部进程"; kill $COORD_PID "${PIDS[@]}" 2>/dev/null || true; wait' INT TERM
wait
