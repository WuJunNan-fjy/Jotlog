#!/usr/bin/env bash
# ============================================================================
# Jotlog 数据库备份（Linux 服务器用，配 crontab 每天跑）
#
# 安装：
#   1. 把这个文件放到服务器（如 /opt/jotlog/scripts/backup-db.sh），chmod +x
#   2. crontab -e 加一行（每天 04:17 跑，错开整点）：
#        17 4 * * * /opt/jotlog/scripts/backup-db.sh >> /var/log/jotlog-backup.log 2>&1
#
# 配置走环境变量（与 systemd 里的 JOTLOG_DB_* 同名，可直接 source 同一个 env 文件）。
# 只依赖 mysqldump（mysql-client）和 gzip，不需要 Java 或应用在线。
# ============================================================================
set -euo pipefail

DB_HOST="${JOTLOG_DB_HOST:-127.0.0.1}"
DB_PORT="${JOTLOG_DB_PORT:-3306}"
DB_NAME="${JOTLOG_DB_NAME:-jotlog}"
DB_USER="${JOTLOG_DB_USER:-root}"
DB_PASS="${JOTLOG_DB_PASSWORD:-}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/jotlog}"
KEEP_DAYS="${KEEP_DAYS:-14}"

# 附件目录可选一并打包（jotlog.storage.attachment-dir 的本机存储路径）。
# 留空则只备份数据库；用 OSS 时不用管（对象存储自带冗余，想要异地副本再配 ossutil）。
ATTACHMENT_DIR="${ATTACHMENT_DIR:-}"

if [[ -z "$DB_PASS" ]]; then
  echo "[ERROR] 没给数据库密码：设 JOTLOG_DB_PASSWORD 环境变量后再跑" >&2
  exit 1
fi
if ! command -v mysqldump >/dev/null 2>&1; then
  echo "[ERROR] 找不到 mysqldump：Debian/Ubuntu 装 mysql-client，RHEL 装 mysql" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/jotlog-$STAMP.sql.gz"

# MYSQL_PWD 比 -p密码 干净：不会出现在进程参数里（ps aux 看不到）
export MYSQL_PWD="$DB_PASS"

# --single-transaction：InnoDB 一致性快照，不锁表；
# --routines/--triggers/--events：把存储过程、触发器、定时事件一起带上；
# --set-gtid-purged=OFF：在腾讯云这类开了 GTID 的实例上不加这行会报 warning 甚至失败
mysqldump \
  --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
  --single-transaction --routines --triggers --events \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 \
  "$DB_NAME" | gzip -9 > "$OUT"

unset MYSQL_PWD

SIZE="$(du -h "$OUT" | cut -f1)"
echo "[OK] $OUT ($SIZE)"

# 附件（如果用本机磁盘存储）：和数据库同一时间戳，恢复时版本对得上
if [[ -n "$ATTACHMENT_DIR" && -d "$ATTACHMENT_DIR" ]]; then
  ATT_OUT="$BACKUP_DIR/jotlog-attachments-$STAMP.tar.gz"
  tar -czf "$ATT_OUT" -C "$(dirname "$ATTACHMENT_DIR")" "$(basename "$ATTACHMENT_DIR")"
  echo "[OK] $ATT_OUT ($(du -h "$ATT_OUT" | cut -f1))"
  find "$BACKUP_DIR" -name 'jotlog-attachments-*.tar.gz' -mtime "+$KEEP_DAYS" -delete
fi

# 清理旧备份：+n 表示严格早于 n 天，今天的永远留着
find "$BACKUP_DIR" -name 'jotlog-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
echo "[OK] 已清理 $KEEP_DAYS 天前的备份，现存 $(find "$BACKUP_DIR" -name 'jotlog-*.sql.gz' | wc -l) 份"

# ----------------------------------------------------------------------------
# 想异地容灾（机器挂了备份也没了），装 ossutil 或 coscmd 后取消注释：
#   ossutil cp -f "$OUT" "oss://你的bucket/jotlog-backup/"
# 腾讯云 CVM 配 COS：
#   coscmd upload "$OUT" "jotlog-backup/"
# ----------------------------------------------------------------------------
