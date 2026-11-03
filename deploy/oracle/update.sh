#!/usr/bin/env bash
# Deploy a new version: copy the new project files over this folder (or git pull), then
#   bash deploy/oracle/update.sh
# Takes a backup first; data in /opt/bmb is kept.
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
[ -d .git ] && git pull --ff-only || true
sudo "$APP_DIR/deploy/oracle/backup.sh"
docker compose -f docker-compose.yaml -f docker-compose.https.yaml up -d --build
docker image prune -f >/dev/null || true
echo "updated - logs: docker compose logs -f app"
