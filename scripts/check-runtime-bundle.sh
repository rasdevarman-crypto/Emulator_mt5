#!/usr/bin/env bash
# Static checks for a locally supplied Wine/Box64 bundle. Does not run the payload.
set -Eeuo pipefail

bundle_dir="${1:-runtime/bundle}"
manifest="${bundle_dir}/manifest.json"
box64="${bundle_dir}/bin/box64"
wine="${bundle_dir}/bin/wine"
rootfs="${bundle_dir}/rootfs"
errors=0

fail() { printf 'FAIL: %s\n' "$*" >&2; errors=$((errors + 1)); }
pass() { printf 'PASS: %s\n' "$*"; }

if [[ ! -d "$bundle_dir" ]]; then
  printf 'No runtime bundle found at %s. This is expected for the current scaffold.\n' "$bundle_dir"
  printf 'No runtime compatibility claim can be made until a reviewed bundle is supplied.\n'
  exit 2
fi

for path in "$manifest" "$box64" "$wine"; do
  if [[ -f "$path" ]]; then pass "found $path"; else fail "missing $path"; fi
done
if [[ -d "$rootfs" ]]; then pass "found $rootfs"; else fail "missing $rootfs"; fi

if command -v jq >/dev/null 2>&1 && [[ -f "$manifest" ]]; then
  schema="$(jq -r '.schema_version // empty' "$manifest")"
  abi="$(jq -r '.target_android_abi // empty' "$manifest")"
  status="$(jq -r '.status // empty' "$manifest")"
  if [[ "$schema" == "1" ]]; then pass "manifest schema_version=1"; else fail "unsupported or missing manifest schema_version"; fi
  if [[ "$abi" == "arm64-v8a" ]]; then pass "target ABI is arm64-v8a"; else fail "target_android_abi must be arm64-v8a for this initial implementation"; fi
  if [[ "$status" == "example-only-not-runnable" ]]; then fail "manifest still marks this as an example; replace with reviewed bundle metadata"; fi
  for key in '.box64.version' '.box64.source_url' '.box64.sha256' '.wine.version' '.wine.source_url' '.wine.sha256'; do
    value="$(jq -r "$key // empty" "$manifest")"
    if [[ -z "$value" || "$value" == *REPLACE_* ]]; then fail "manifest field $key is not filled with verified metadata"; fi
  done
else
  if [[ ! -f "$manifest" ]]; then :; elif ! command -v jq >/dev/null 2>&1; then fail "jq is required to validate manifest JSON"; fi
fi

for binary in "$box64" "$wine"; do
  [[ -f "$binary" ]] || continue
  if [[ -x "$binary" ]]; then pass "$binary is executable"; else fail "$binary is not executable"; fi
  if command -v readelf >/dev/null 2>&1; then
    machine="$(readelf -h "$binary" 2>/dev/null | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' | head -n 1 || true)"
    if [[ -z "$machine" ]]; then
      fail "$binary is not a readable ELF file (or readelf could not inspect it)"
    elif [[ "$binary" == "$box64" && "$machine" != *AArch64* ]]; then
      fail "$binary must be an AArch64/ARM64 executable for this Android target; got: $machine"
    else
      pass "$binary ELF machine: $machine"
    fi
  else
    printf 'WARN: readelf unavailable; skipped ELF architecture inspection for %s\n' "$binary"
  fi
done

if (( errors > 0 )); then
  printf '\nCompatibility preflight failed with %d issue(s). This is a static check, not a runtime test.\n' "$errors" >&2
  exit 1
fi
printf '\nStatic preflight passed. This does NOT prove Wine starts or MT5 is compatible.\n'
