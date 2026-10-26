#!/usr/bin/env bash
# Usage:
#   ./hw Blocking
#   ./hw RestartBlocking
#   ./hw NoLiveLocks
#   ./hw all
#   ./hw RestartBlocking -Dhw2.tasks=40 -Dhw2.queries=5
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

usage() {
  cat <<'EOF'
Usage (from the homework2 folder):
  ./hw Blocking
  ./hw RestartBlocking
  ./hw NoLiveLocks
  ./hw all

Optional load knobs:
  ./hw RestartBlocking -Dhw2.tasks=40 -Dhw2.queries=5
  ./hw NoLiveLocks -Dhw2.tasks=40 -Dhw2.queries=5
EOF
}

resolve_class() {
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
    blocking) echo Blocking ;;
    restart|restartblocking) echo RestartBlocking ;;
    nolive|nolivelocks) echo NoLiveLocks ;;
    all) echo all ;;
    help|-h|"") echo help ;;
    *) echo "unknown:$1" ;;
  esac
}

TARGET="$(resolve_class "${1:-}")"
if [[ "$TARGET" == "help" ]]; then
  usage
  exit 0
fi
if [[ "$TARGET" == unknown:* ]]; then
  echo "Unknown demo: ${TARGET#unknown:}"
  usage
  exit 1
fi
shift || true

"$ROOT/scripts/compile.sh"
CP="$ROOT/out:$ROOT/lib/postgresql-42.2.18.jar"

run_one() {
  echo ""
  echo "---- $1 ----"
  java -cp "$CP" "$@" "$1"
}

if [[ "$TARGET" == "all" ]]; then
  run_one Blocking "$@"
  run_one RestartBlocking "$@"
  run_one NoLiveLocks "$@"
else
  exec java -cp "$CP" "$@" "$TARGET"
fi
