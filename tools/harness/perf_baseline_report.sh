#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

logs_dir="${1:-logs}"
[[ -d "$logs_dir" ]] || {
  echo "ERROR: missing logs directory: $logs_dir" >&2
  exit 1
}

latest="$(find "$logs_dir" -maxdepth 1 -type f -name 'arcane-lua-profile-*.log' | sort | tail -n 1)"
[[ -n "$latest" ]] || {
  echo "ERROR: no arcane-lua-profile-*.log files found in $logs_dir" >&2
  exit 1
}

echo "Latest profile dump: $latest"
echo
awk '
  BEGIN {
    count = 0;
  }
  NF > 0 {
    count++;
    print;
    if (count >= 20) {
      exit 0;
    }
  }
' "$latest"
