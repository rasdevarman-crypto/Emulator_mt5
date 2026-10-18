#!/usr/bin/env bash
# Smoke-test a staged dual-rootfs Wine WoW64 + Box64 candidate.
# Run from an ARM64 Linux/PRoot environment; this is not an MT5 compatibility test.
set -Eeuo pipefail

bundle_dir="${1:-bundle}"
bundle_dir="$(cd "$bundle_dir" && pwd)"
base_dir="$(dirname "$bundle_dir")"
host_rootfs="$bundle_dir/rootfs-arm64"
wine_rootfs="$bundle_dir/rootfs"
fixtures="$base_dir/provenance/test-fixtures"
prefix="${WINEPREFIX:-$base_dir/wine-smoke-prefix}"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

command -v proot >/dev/null 2>&1 || fail "proot is required"
[[ "$(uname -m)" == "aarch64" || "$(uname -m)" == "arm64" ]] || fail "must run on ARM64; host reports $(uname -m)"
[[ -x "$host_rootfs/usr/local/bin/box64" ]] || fail "missing ARM64 Box64 in rootfs-arm64"
[[ -x "$wine_rootfs/opt/wine-11.0-wow64/bin/wine" ]] || fail "missing Wine 11.0 install tree in x86_64 rootfs"
[[ -x "$wine_rootfs/opt/wine-11.0-wow64/bin/wineboot" ]] || fail "missing wineboot"
[[ -f "$fixtures/runtime-smoke-x86_64.exe" && -f "$fixtures/runtime-smoke-i386.exe" ]] || fail "missing x86/x64 PE smoke fixtures under $fixtures"
mkdir -p "$prefix"

proot_run() {
  proot -R "$host_rootfs" \
    -b "$wine_rootfs:/x86root" \
    -b "$fixtures:/tests" \
    -b "$prefix:/wineprefix" \
    /usr/bin/env \
    BOX64_LD_LIBRARY_PATH=/x86root/lib/x86_64-linux-gnu:/x86root/usr/lib/x86_64-linux-gnu:/x86root/lib64:/x86root/opt/wine-11.0-wow64/lib \
    WINEPREFIX=/wineprefix WINEDEBUG=-all \
    /usr/local/bin/box64 "$@"
}

proot_run --version
proot_run /x86root/opt/wine-11.0-wow64/bin/wine --version 2>&1 | grep -F 'wine-11.0'
proot_run /x86root/opt/wine-11.0-wow64/bin/wineboot --init
proot_run /x86root/opt/wine-11.0-wow64/bin/wine /tests/runtime-smoke-x86_64.exe | tee "$base_dir/pe-x86_64-smoke.txt"
grep -F 'RUNTIME_SMOKE_OK pointer_bits=64' "$base_dir/pe-x86_64-smoke.txt"
proot_run /x86root/opt/wine-11.0-wow64/bin/wine /tests/runtime-smoke-i386.exe | tee "$base_dir/pe-i386-smoke.txt"
grep -F 'RUNTIME_SMOKE_OK pointer_bits=32' "$base_dir/pe-i386-smoke.txt"
sha256sum "$base_dir/pe-x86_64-smoke.txt" "$base_dir/pe-i386-smoke.txt" > "$base_dir/pe-smoke.sha256"
pass "Box64 + Wine version/prefix + 32-bit and 64-bit PE smoke tests passed"
printf 'This does not prove Android UI integration or MetaTrader 5 compatibility.\n'
