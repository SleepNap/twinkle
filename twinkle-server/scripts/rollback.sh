#!/usr/bin/env bash
# ============================================================
# twinkle 回滚脚本（架构 M5-3 上线切换：灰度/回滚路径，CC 兜底）
#
# 场景：新版本启动后健康检查失败 / 行为异常，回滚到上一个稳定版本。
# 回滚 = 还原 jar + 还原 DB 备份 + 还原配置，再起旧版本（不依赖"等几分钟停服"）。
#
# 前置：切换前已备份（见 docs/ops/switch-to-production.md 的切换步骤）：
#   ./backup/twinkle-server.jar.bak    （旧 jar）
#   ./data/twinkle.db.bak.<时间戳>      （DB 备份，见 migrate-newmaple.sh 输出）
#   ./backup/application.yml.bak       （旧配置，可选）
# ============================================================
set -euo pipefail

JAR_BACKUP="${JAR_BACKUP:-./backup/twinkle-server.jar.bak}"
[ -f "$JAR_BACKUP" ] || { echo "错误：找不到旧 jar 备份 $JAR_BACKUP"; exit 1; }

# 还原 jar
echo "==> 还原旧 jar"
cp "$JAR_BACKUP" target/twinkle-server.jar

# 还原 DB（若提供最新备份）
LATEST_DB="$(ls -t data/twinkle.db.bak.* 2>/dev/null | head -1 || true)"
if [ -n "$LATEST_DB" ]; then
  echo "==> 还原 DB 备份 $LATEST_DB"
  cp "$LATEST_DB" data/twinkle.db
else
  echo "!! 未找到 DB 备份，跳过 DB 还原（若新版本跑过迁移/导入，数据可能已变更）"
fi

# 还原配置
if [ -f ./backup/application.yml.bak ]; then
  echo "==> 还原配置"
  cp ./backup/application.yml.bak ./application.yml 2>/dev/null || true
fi

echo "==> 回滚完成。启动旧版本："
echo "    ./scripts/start.sh"
echo "    然后验证：curl http://127.0.0.1:8080/admin/v1/health 返回 healthy"
