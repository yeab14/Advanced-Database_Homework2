#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/out"
javac -cp "$ROOT/lib/postgresql-42.2.18.jar" -d "$ROOT/out" "$ROOT"/src/*.java
echo "Compiled into $ROOT/out"
