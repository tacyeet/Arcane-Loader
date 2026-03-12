#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

expect_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "missing file: $path"
}

expect_dir() {
  local path="$1"
  [[ -d "$path" ]] || fail "missing directory: $path"
}

expect_dir "src/main/java"
expect_dir "src/main/resources"
expect_dir "examples/lua_mods"
expect_file "build.gradle"
expect_file "src/main/resources/manifest.json"

if [[ -n "${hytaleServerJar:-}" ]]; then
  [[ -f "$hytaleServerJar" ]] || fail "hytaleServerJar env points to missing file: $hytaleServerJar"
else
  for candidate in \
    "server/HytaleServer.jar" \
    "HytaleServer.jar" \
    "../HytaleServer.jar"
  do
    if [[ -f "$candidate" ]]; then
      found_jar="$candidate"
      break
    fi
  done
  [[ -n "${found_jar:-}" ]] || fail "could not find HytaleServer.jar in expected locations"
fi

mod_count=0
while IFS= read -r -d '' dir; do
  expect_file "$dir/manifest.json"
  expect_file "$dir/init.lua"
  mod_count=$((mod_count + 1))
done < <(find "examples/lua_mods" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

[[ "$mod_count" -ge 11 ]] || fail "expected curated example pack, found only $mod_count mods"

echo "OK: layout verified"
echo "OK: example mods=$mod_count"
echo "OK: HytaleServer.jar=${found_jar:-$hytaleServerJar}"
