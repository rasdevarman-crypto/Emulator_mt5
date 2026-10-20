#!/usr/bin/env bash
# Verify declared source metadata and SHA-256 values for a staged runtime bundle.
# This is a provenance/integrity preflight, not a malware or compatibility scan.
set -Eeuo pipefail

bundle_dir="${1:-runtime/bundle}"
manifest="${bundle_dir}/manifest.json"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

[[ -f "$manifest" ]] || fail "missing manifest: $manifest"
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

status="$(jq -r '.status // empty' "$manifest")"
[[ "$status" == "candidate" || "$status" == "arm64-smoke-tested" || "$status" == "ready-for-review" ]] || fail "manifest status must be candidate, arm64-smoke-tested, or ready-for-review (got: ${status:-empty})"

if [[ "$status" == "arm64-smoke-tested" ]]; then
  smoke_passed="$(jq -r '.verification.arm64_smoke_test // false' "$manifest")"
  [[ "$smoke_passed" == "true" ]] || fail "arm64-smoke-tested status requires verification.arm64_smoke_test=true"
fi

for component in box64 wine; do
  path="$(jq -r --arg c "$component" '.[$c].path // empty' "$manifest")"
  version="$(jq -r --arg c "$component" '.[$c].version // empty' "$manifest")"
  source_url="$(jq -r --arg c "$component" '.[$c].source_url // empty' "$manifest")"
  expected="$(jq -r --arg c "$component" '.[$c].sha256 // empty' "$manifest")"

  [[ -n "$path" && "$path" != /* && "$path" != *".."* ]] || fail "$component has an unsafe/missing relative path"
  [[ -n "$version" && "$version" != *"REPLACE_WITH"* ]] || fail "$component version is a placeholder"
  [[ "$source_url" == https://* ]] || fail "$component source_url must use HTTPS"
  [[ "$expected" =~ ^[[:xdigit:]]{64}$ ]] || fail "$component SHA-256 must be 64 hex characters"

  file="${bundle_dir}/${path}"
  [[ -f "$file" ]] || fail "missing $component payload: $file"
  actual="$(sha256sum "$file" | awk '{print $1}')"
  [[ "${actual,,}" == "${expected,,}" ]] || fail "$component SHA-256 mismatch"
  pass "$component file hash matches manifest"
done

printf 'Integrity metadata checks passed. This does NOT prove source authenticity, safety, Android execution, or MT5 compatibility.\n'
