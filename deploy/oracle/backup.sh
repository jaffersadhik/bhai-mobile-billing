#!/usr/bin/env bash
# MySQL backup -> /opt/bmb/backup/bmb-YYYY-MM-DD_HHMM.sql.gz, keeps KEEP_DAYS (default 14).
# Optional off-site copy: install + configure rclone (e.g. "rclone config" -> Google Drive remote "gdrive"),
# then put  RCLONE_REMOTE=gdrive:bmb-backup  in .env
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
DIR="${MYSQL_BACKUP_DIR:-/opt/bmb/backup}"
KEEP_DAYS="${KEEP_DAYS:-14}"
F="$DIR/bmb-$(date +%F_%H%M).sql.gz"
mkdir -p "$DIR"
trap 'rm -f "$F.part"' EXIT
docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
  mysqldump -uroot --single-transaction --quick --routines --triggers --default-character-set=utf8mb4 bmb \
  | gzip -9 > "$F.part"
[ "$(gzip -dc "$F.part" | tail -1 | grep -c 'Dump completed')" = 1 ] || { echo "$(date) backup FAILED"; rm -f "$F.part"; exit 1; }
mv "$F.part" "$F"
find "$DIR" -name 'bmb-*.sql.gz' -mtime +"$KEEP_DAYS" -delete
echo "$(date) backup ok: $F ($(du -h "$F" | cut -f1))"
if [ -n "${RCLONE_REMOTE:-}" ] && command -v rclone >/dev/null; then
  rclone copy "$F" "$RCLONE_REMOTE" && echo "$(date) copied to $RCLONE_REMOTE"
  rclone delete --min-age "${KEEP_DAYS}d" "$RCLONE_REMOTE" || true
fi
