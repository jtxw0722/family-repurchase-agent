#!/usr/bin/env bash
set -euo pipefail

DB="${DB:-/opt/family-repurchase-agent/data/family-repurchase.sqlite}"
SERVICE="${SERVICE:-family-repurchase-agent}"
BACKUP_DIR="${BACKUP_DIR:-/opt/family-repurchase-agent/data/backups}"

if [[ "${1:-}" != "--yes" ]]; then
  echo "Refusing to reset database without explicit confirmation."
  echo
  echo "Usage:"
  echo "  sudo bash scripts/reset-remote-sqlite-data.sh --yes"
  echo
  echo "Environment variables:"
  echo "  DB=/path/to/family-repurchase.sqlite"
  echo "  SERVICE=family-repurchase-agent"
  echo "  BACKUP_DIR=/path/to/backups"
  exit 1
fi

if [[ ! -f "$DB" ]]; then
  echo "SQLite database not found: $DB"
  exit 1
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "sqlite3 is not installed. Install it first:"
  echo "  sudo apt update && sudo apt install -y sqlite3"
  exit 1
fi

mkdir -p "$BACKUP_DIR"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP="$BACKUP_DIR/family-repurchase.sqlite.bak.$TIMESTAMP"

echo "Database: $DB"
echo "Service:  $SERVICE"
echo "Backup:   $BACKUP"
echo

echo "Creating SQLite backup..."
sqlite3 "$DB" ".backup '$BACKUP'"

echo "Stopping service..."
systemctl stop "$SERVICE"

echo "Resetting business tables..."
sqlite3 "$DB" <<'SQL'
PRAGMA foreign_keys = OFF;

BEGIN;

DELETE FROM review_items;
DELETE FROM purchase_records;
DELETE FROM raw_import_batches;
DELETE FROM agent_events;

DELETE FROM sqlite_sequence
WHERE name IN (
  'review_items',
  'purchase_records',
  'raw_import_batches',
  'agent_events'
);

COMMIT;

VACUUM;
SQL

echo "Starting service..."
systemctl start "$SERVICE"

echo
echo "Database reset completed."
echo
echo "Current table counts:"
sqlite3 "$DB" <<'SQL'
.headers on
.mode column

SELECT 'raw_import_batches' AS table_name, COUNT(*) AS cnt FROM raw_import_batches
UNION ALL
SELECT 'purchase_records', COUNT(*) FROM purchase_records
UNION ALL
SELECT 'review_items', COUNT(*) FROM review_items
UNION ALL
SELECT 'agent_events', COUNT(*) FROM agent_events
UNION ALL
SELECT 'product_aliases', COUNT(*) FROM product_aliases;
SQL

echo
echo "Service status:"
systemctl status "$SERVICE" --no-pager