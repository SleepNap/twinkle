#!/usr/bin/env bash
# ============================================================
# newmaple 老库 → twinkle 新库一次性导入脚本（架构 M5-2 单库迁移）
#
# 前置：构建好 twinkle-server.jar（mvn -B verify 后取 bootstrap/target 产物）
# 用法：
#   ./migrate-newmaple.sh \
#     --source-url=jdbc:mysql://host:3306/newmaple --source-user=root --source-pass=xxx \
#     --target-url=jdbc:sqlite:./data/twinkle.db
# 可选：--truncate（目标库已有数据时清空重导）、--no-reset-passwords（保留老密码，注意会登不上）
#
# 流程：备份目标库 → 建库/清空 → 导入（migration 管结构 + seed 管内容）→ 校验行数 → 打印密码重置清单
# ============================================================
set -euo pipefail

JAR="${TWINKLE_JAR:-target/twinkle-server.jar}"
[ -f "$JAR" ] || { echo "错误：找不到 $JAR，请先构建"; exit 1; }

# 解析 --key=value 与 --flag 到变量
declare -A ARGS
for arg in "$@"; do
  case "$arg" in
    --*=*) ARGS["${arg%%=*}"]="${arg#*=}" ;;
    --*)   ARGS["$arg"]="true" ;;
  esac
done
: "${ARGS[--target-url]:?缺少 --target-url}"

TARGET_URL="${ARGS[--target-url]}"
# SQLite 目标库的物理路径（用于备份）：jdbc:sqlite:./data/twinkle.db → ./data/twinkle.db
if [[ "$TARGET_URL" == jdbc:sqlite:* ]]; then
  DB_FILE="${TARGET_URL#jdbc:sqlite:}"
  if [[ "$DB_FILE" != ":memory:" ]]; then
    BACKUP="${DB_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    echo "==> 备份目标库到 $BACKUP"
    cp "$DB_FILE" "$BACKUP"
  fi
fi

echo "==> 运行导入工具（目标库先迁移建结构，再拷贝内容）"
java -cp "$JAR" org.gms.bootstrap.tools.NewMapleImportMain "$@"

echo "==> 导入完成。请人工抽查：目标库 SELECT COUNT(*) FROM accounts/characters/queststatus/questprogress/inventoryitems"
echo "==> 注意：老库密码已重置为默认口令（见切换文档），好友关系需游戏中重建（buddies 结构不兼容被跳过）"
