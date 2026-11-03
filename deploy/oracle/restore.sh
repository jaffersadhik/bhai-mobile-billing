#!/usr/bin/env bash
# Restore a backup made by backup.sh:   bash deploy/oracle/restore.sh /opt/bmb/backup/bmb-2026-11-03_0230.sql.gz
# REPLACES all current data in database bmb.
set -euo pipefail
F="${1:?usage: restore.sh <backup.sql.gz>}"
[ -f "$F" ] || { echo "no file $F"; exit 1; }
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
read -r -p "All current data will be replaced by $F. Type YES to continue: " ok
[ "$ok" = YES ] || exit 1
docker compose stop app
gzip -dc "$F" | docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 bmb
docker compose start app
echo "restored $F"
