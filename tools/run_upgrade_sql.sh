#!/usr/bin/env bash
# ============================================================
# 一键把升级 SQL 打到微信云托管线上库
#
# 用法：
#   bash tools/run_upgrade_sql.sh <host> <port> <user> <db> <sql文件>
# 例：
#   bash tools/run_upgrade_sql.sh 1.2.3.4 3306 root elderly_ai \
#        src/main/resources/sql/upgrade-medication-taken-log.sql
#
# 密码不写在命令行里，执行时会交互式提示（避免留在 shell 历史）。
# host/账号/密码从「云托管控制台 → 后端服务 → 环境变量」里的
# DB_URL / DB_USERNAME / DB_PASSWORD 取。
# ============================================================
set -e

MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"

HOST="$1"; PORT="$2"; USER="$3"; DB="$4"; SQLFILE="$5"

if [ -z "$HOST" ] || [ -z "$SQLFILE" ]; then
  echo "用法: bash tools/run_upgrade_sql.sh <host> <port> <user> <db> <sql文件>"
  exit 1
fi
PORT="${PORT:-3306}"
DB="${DB:-elderly_ai}"

if [ ! -f "$SQLFILE" ]; then
  echo "找不到 SQL 文件: $SQLFILE"
  exit 1
fi

echo "目标: $USER@$HOST:$PORT/$DB"
echo "脚本: $SQLFILE"
echo "--------------------------------------------"

"$MYSQL_BIN" -h "$HOST" -P "$PORT" -u "$USER" -p "$DB" < "$SQLFILE"

echo "--------------------------------------------"
echo "执行结束，下面验证表是否存在："
"$MYSQL_BIN" -h "$HOST" -P "$PORT" -u "$USER" -p "$DB" \
  -e "SHOW TABLES LIKE 'medication_taken_log'; DESCRIBE medication_taken_log;"
