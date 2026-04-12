#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export CODEX_HOME="$PROJECT_ROOT/.codex-home"

if [ ! -d "$CODEX_HOME/skills" ]; then
  echo "ERROR: $CODEX_HOME/skills not found" >&2
  exit 1
fi

exec codex -C "$PROJECT_ROOT" "$@"
