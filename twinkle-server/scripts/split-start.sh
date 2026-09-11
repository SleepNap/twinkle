#!/usr/bin/env bash
# twinkle split 启动：同一 JAR 启动一个 coordinator 与若干显式拓扑 worker。
#
# TWINKLE_WORKERS 格式：workerId=channelId:port,...;workerId=channelId:port,...
# 频道 ID 是可稀疏稳定标识，端口必须显式给出，脚本不再通过数量或 ID 推导。
# 示例：TWINKLE_WORKERS='worker-a=1:8584,8:9000;worker-b=21:10000'
set -euo pipefail

JAR="${TWINKLE_JAR:-target/twinkle-server.jar}"
[ -f "$JAR" ] || { echo "错误：找不到 $JAR，请先构建"; exit 1; }

export TWINKLE_WORKERS="${TWINKLE_WORKERS:-worker-1=1:8584}"
export TWINKLE_LOGIC_PATH="${TWINKLE_LOGIC_PATH:-$(dirname "$JAR")/logic}"

export TWINKLE_DB_URL="${TWINKLE_DB_URL:-jdbc:postgresql://127.0.0.1:5432/twinkle}"
export TWINKLE_COORDINATOR_HOST="${TWINKLE_COORDINATOR_HOST:-127.0.0.1}"
export TWINKLE_COORDINATOR_PORT="${TWINKLE_COORDINATOR_PORT:-8510}"
export TWINKLE_WZ_PATH="${TWINKLE_WZ_PATH:-./wz}"
export TWINKLE_SCRIPT_PATH="${TWINKLE_SCRIPT_PATH:-./scripts}"
export TWINKLE_CHANNEL_HOST="${TWINKLE_CHANNEL_HOST:-127.0.0.1}"

declare -a WORKER_IDS=() WORKER_CHANNELS=()
declare -A SEEN_WORKERS=() SEEN_CHANNELS=() SEEN_PORTS=()

parse_topology() {
  local entry worker channels endpoint cid port
  IFS=';' read -ra entries <<< "$TWINKLE_WORKERS"
  for entry in "${entries[@]}"; do
    worker="${entry%%=*}"
    channels="${entry#*=}"
    [ -n "$worker" ] && [ "$channels" != "$entry" ] && [ -n "$channels" ] || {
      echo "错误：无效 worker 拓扑项：$entry"; exit 1;
    }
    [ -z "${SEEN_WORKERS[$worker]:-}" ] || { echo "错误：worker ID 重复：$worker"; exit 1; }
    SEEN_WORKERS[$worker]=1
    WORKER_IDS+=("$worker")
    WORKER_CHANNELS+=("$channels")
    IFS=',' read -ra endpoints <<< "$channels"
    for endpoint in "${endpoints[@]}"; do
      cid="${endpoint%%:*}"; port="${endpoint#*:}"
      [[ "$cid" =~ ^[0-9]+$ && "$port" =~ ^[0-9]+$ ]] || {
        echo "错误：无效频道端点：$endpoint"; exit 1;
      }
      [ "$cid" -ge 1 ] && [ "$cid" -le 256 ] || {
        echo "错误：v83 频道 ID 必须在 1..256：$cid"; exit 1;
      }
      [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || {
        echo "错误：频道端口越界：$port"; exit 1;
      }
      [ -z "${SEEN_CHANNELS[$cid]:-}" ] || { echo "错误：频道 ID 重复：$cid"; exit 1; }
      [ -z "${SEEN_PORTS[$port]:-}" ] || { echo "错误：频道端口重复：$port"; exit 1; }
      SEEN_CHANNELS[$cid]="$worker"; SEEN_PORTS[$port]="$worker"
    done
  done
}

parse_topology
mkdir -p logs

echo "==> 启动 coordinator（内部端口 $TWINKLE_COORDINATOR_PORT）"
java -jar "$JAR" --twinkle.profile=split-channel --twinkle.role=coordinator \
  --twinkle.coordinator.host="$TWINKLE_COORDINATOR_HOST" \
  --twinkle.coordinator.port="$TWINKLE_COORDINATOR_PORT" \
  > logs/coordinator.log 2>&1 &
COORD_PID=$!

COORD_READY=false
for _ in $(seq 1 30); do
  if (exec 3<>/dev/tcp/"$TWINKLE_COORDINATOR_HOST"/"$TWINKLE_COORDINATOR_PORT") 2>/dev/null; then
    exec 3>&- 3<&-; COORD_READY=true; break
  fi
  sleep 0.5
done
[ "$COORD_READY" = true ] || {
  echo "错误：coordinator 启动超时，详见 logs/coordinator.log"
  kill -TERM "$COORD_PID" 2>/dev/null || true
  wait "$COORD_PID" 2>/dev/null || true
  exit 1
}

PIDS=()
for i in "${!WORKER_IDS[@]}"; do
  worker="${WORKER_IDS[$i]}"; channels="${WORKER_CHANNELS[$i]}"
  echo "==> 启动 $worker（$channels）"
  java -jar "$JAR" --twinkle.profile=split-channel --twinkle.role=channel \
    --twinkle.worker.id="$worker" --twinkle.worker.channels="$channels" \
    --twinkle.net.channel.host="$TWINKLE_CHANNEL_HOST" \
    --twinkle.coordinator.host="$TWINKLE_COORDINATOR_HOST" \
    --twinkle.coordinator.port="$TWINKLE_COORDINATOR_PORT" \
    > "logs/$worker.log" 2>&1 &
  PIDS+=("$!")
  echo "$worker pid=$! (log: logs/$worker.log)"
done

echo "==> 已启动 coordinator($COORD_PID) + ${#WORKER_IDS[@]} worker；频道拓扑：$TWINKLE_WORKERS"
echo "    管理控制台: http://127.0.0.1:8686/admin/v1/health"

shutdown_all() {
  trap - INT TERM
  # 先让 worker 断开玩家并完成存档，再关闭 coordinator，避免管理面先消失。
  kill -TERM "${PIDS[@]}" 2>/dev/null || true
  for pid in "${PIDS[@]}"; do
    for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  done
  kill -TERM "$COORD_PID" 2>/dev/null || true
  wait || true
}
trap shutdown_all INT TERM
wait
