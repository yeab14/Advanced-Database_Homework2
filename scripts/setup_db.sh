#!/usr/bin/env bash
# Creates role/database if missing (needs local postgres superuser access).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi
USER_NAME="${HW2_DB_USER:-hw2}"
PASS="${HW2_DB_PASSWORD:-hw2}"
DB="${HW2_DB_NAME:-adb_hw2}"
PORT="${HW2_DB_PORT:-5432}"

sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='${USER_NAME}'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE USER ${USER_NAME} WITH PASSWORD '${PASS}' CREATEDB;"
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='${DB}'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE ${DB} OWNER ${USER_NAME};"

echo "OK: database ${DB} owner ${USER_NAME}"
PGPASSWORD="$PASS" psql -h 127.0.0.1 -p "$PORT" -U "$USER_NAME" -d "$DB" -c 'SELECT current_database(), current_user;'
