#!/usr/bin/env bash
# 按显式 worker 拓扑滚动升级。参数为 all、workerId 或任意真实 channelId。
set -euo pipefail

JAR="${TWINKLE_JAR:-target/twinkle-server.jar}"
HTTP_BASE="${TWINKLE_HTTP_BASE:-http://127.0.0.1:8686}"
COORD_HOST="${TWINKLE_COORDINATOR_HOST:-127.0.0.1}"
COORD_PORT="${TWINKLE_COORDINATOR_PORT:-8510}"
CHANNEL_HOST="${TWINKLE_CHANNEL_HOST:-127.0.0.1}"
TWINKLE_WORKERS="${TWINKLE_WORKERS:-worker-1=1:8584}"
TARGET="${1:-all}"
[ -f "$JAR" ] || { echo "错误：找不到 $JAR"; exit 1; }
mkdir -p logs

declare -a WORKER_IDS=() WORKER_CHANNELS=()
declare -A SEEN_WORKERS=() CHANNEL_OWNER=() SEEN_PORTS=()
IFS=';' read -ra entries <<< "$TWINKLE_WORKERS"
for entry in "${entries[@]}"; do
  worker="${entry%%=*}"; channels="${entry#*=}"
  [ -n "$worker" ] && [ "$channels" != "$entry" ] && [ -n "$channels" ] || {
    echo "错误：无效拓扑：$entry"; exit 1;
  }
  [ -z "${SEEN_WORKERS[$worker]:-}" ] || { echo "错误：worker ID 重复：$worker"; exit 1; }
  SEEN_WORKERS[$worker]=1
  WORKER_IDS+=("$worker"); WORKER_CHANNELS+=("$channels")
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
    [ -z "${CHANNEL_OWNER[$cid]:-}" ] || { echo "错误：频道 ID 重复：$cid"; exit 1; }
    [ -z "${SEEN_PORTS[$port]:-}" ] || { echo "错误：频道端口重复：$port"; exit 1; }
    CHANNEL_OWNER[$cid]="$worker"; SEEN_PORTS[$port]="$worker"
  done
done

port_open() { (exec 3<>/dev/tcp/"$CHANNEL_HOST"/"$1") 2>/dev/null; }

restart_worker() {
  local worker="$1" channels="$2" endpoint cid port response
  IFS=',' read -ra endpoints <<< "$channels"
  echo "==> 滚动重启 $worker（$channels）"
  for endpoint in "${endpoints[@]}"; do
    cid="${endpoint%%:*}"
    response="$(curl -s -X POST "$HTTP_BASE/admin/v1/channels/$cid/terminate" || true)"
    echo "    terminate 频道 $cid: ${response:-请求失败}"
  done
  for _ in $(seq 1 60); do
    any=false
    for endpoint in "${endpoints[@]}"; do
      port="${endpoint#*:}"; if port_open "$port"; then any=true; break; fi
    done
    [ "$any" = false ] && break; sleep 0.5
  done
  for endpoint in "${endpoints[@]}"; do
    port="${endpoint#*:}"; ! port_open "$port" || { echo "!! $worker 端口 $port 仍开放"; return 1; }
  done
  java -jar "$JAR" --twinkle.profile=split-channel --twinkle.role=channel \
    --twinkle.worker.id="$worker" --twinkle.worker.channels="$channels" \
    --twinkle.net.channel.host="$CHANNEL_HOST" \
    --twinkle.coordinator.host="$COORD_HOST" --twinkle.coordinator.port="$COORD_PORT" \
    > "logs/$worker.log" 2>&1 &
  echo "    新 $worker pid=$!"
  for _ in $(seq 1 60); do
    all=true
    for endpoint in "${endpoints[@]}"; do
      port="${endpoint#*:}"; if ! port_open "$port"; then all=false; break; fi
    done
    [ "$all" = true ] && { echo "    $worker 已恢复"; return 0; }
    sleep 0.5
  done
  echo "!! $worker 启动超时"; return 1
}

matched=false
for i in "${!WORKER_IDS[@]}"; do
  worker="${WORKER_IDS[$i]}"
  if [ "$TARGET" = all ] || [ "$TARGET" = "$worker" ] || [ "${CHANNEL_OWNER[$TARGET]:-}" = "$worker" ]; then
    restart_worker "$worker" "${WORKER_CHANNELS[$i]}"; matched=true
    [ "$TARGET" = all ] || break
  fi
done
[ "$matched" = true ] || { echo "错误：找不到 worker 或频道：$TARGET"; exit 1; }
echo "==> 滚动升级完成"
