#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

expected="${1:-}"
root="build/harness/lua_mods"
[[ -d "$root" ]] || {
  echo "ERROR: missing $root; run ./gradlew harnessSeed first" >&2
  exit 1
}

count=0
prev=""
while IFS= read -r -d '' dir; do
  mod_id="$(basename "$dir")"
  manifest="$dir/manifest.json"
  init="$dir/init.lua"
  [[ -f "$manifest" ]] || { echo "ERROR: missing $manifest" >&2; exit 1; }
  [[ -f "$init" ]] || { echo "ERROR: missing $init" >&2; exit 1; }

  grep -q "\"id\": \"$mod_id\"" "$manifest" || {
    echo "ERROR: manifest id mismatch in $manifest" >&2
    exit 1
  }

  if [[ -n "$prev" ]]; then
    grep -q "\"loadAfter\": \\[\"$prev\"\\]" "$manifest" || {
      echo "ERROR: missing loadAfter chain from $mod_id to $prev" >&2
      exit 1
    }
  fi

  prev="$mod_id"
  count=$((count + 1))
done < <(find "$root" -mindepth 1 -maxdepth 1 -type d -name 'synthetic-*' -print0 | sort -z)

if [[ -n "$expected" ]] && [[ "$count" -ne "$expected" ]]; then
  echo "ERROR: expected $expected synthetic mods, found $count" >&2
  exit 1
fi

echo "OK: asserted $count synthetic mods in $root"
