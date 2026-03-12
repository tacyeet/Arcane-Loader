#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

count="${1:-20}"
if ! [[ "$count" =~ ^[0-9]+$ ]] || [[ "$count" -le 0 ]]; then
  echo "ERROR: mod count must be a positive integer" >&2
  exit 1
fi

out_dir="build/harness/lua_mods"
mkdir -p "$out_dir"
find "$out_dir" -mindepth 1 -maxdepth 1 -type d -name 'synthetic-*' -exec rm -rf {} +

for i in $(seq 1 "$count"); do
  mod_id="$(printf 'synthetic-%03d' "$i")"
  mod_dir="$out_dir/$mod_id"
  mkdir -p "$mod_dir"

  load_after=""
  if [[ "$i" -gt 1 ]]; then
    prev_id="$(printf 'synthetic-%03d' $((i - 1)))"
    load_after=",
  \"loadAfter\": [\"$prev_id\"]"
  fi

  cat > "$mod_dir/manifest.json" <<EOF
{
  "id": "$mod_id",
  "name": "Synthetic $i",
  "version": "1.1.0",
  "entry": "init.lua"$load_after
}
EOF

  cat > "$mod_dir/init.lua" <<EOF
local M = {}

function M.onEnable(ctx)
  log.info("enabled $mod_id")
end

function M.onDisable(ctx)
  log.info("disabled $mod_id")
end

return M
EOF
done

echo "OK: seeded $count synthetic mods into $out_dir"
