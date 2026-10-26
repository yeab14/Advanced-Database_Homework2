#!/usr/bin/env bash
# Usage: ./scripts/run.sh Blocking|RestartBlocking|NoLiveLocks [java-system-props...]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASS="${1:?class name required}"
shift || true
"$ROOT/scripts/compile.sh"
exec java -cp "$ROOT/out:$ROOT/lib/postgresql-42.2.18.jar" "$@" "$CLASS"
