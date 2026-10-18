#!/usr/bin/env bash
# Acquire source code only; this script does NOT build or stage a runnable runtime.
set -Eeuo pipefail

out_dir="${1:-build/runtime-sources}"
box64_commit="2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a"
box64_tag="v0.4.4"
wine_version="11.0"
wine_url="https://dl.winehq.org/wine/source/11.0/wine-11.0.tar.xz"

for tool in git curl tar sha256sum jq; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done

mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"
box_dir="$out_dir/box64"
wine_archive="$out_dir/wine-$wine_version.tar.xz"
wine_dir="$out_dir/wine-$wine_version"

if [[ -e "$box_dir" || -e "$wine_archive" || -e "$wine_dir" ]]; then
  echo "Refusing to overwrite existing source paths in $out_dir; use a fresh output directory." >&2
  exit 2
fi

git clone --filter=blob:none https://github.com/ptitSeb/box64.git "$box_dir"
git -C "$box_dir" checkout --detach "$box64_commit"
actual_commit="$(git -C "$box_dir" rev-parse HEAD)"
[[ "$actual_commit" == "$box64_commit" ]] || { echo "Box64 commit mismatch: $actual_commit" >&2; exit 1; }
git -C "$box_dir" describe --tags --exact-match HEAD | grep -Fx "$box64_tag" >/dev/null

curl --fail --location --proto '=https' --tlsv1.2 "$wine_url" --output "$wine_archive"
archive_sha="$(sha256sum "$wine_archive" | awk '{print $1}')"
mkdir "$wine_dir"
tar -xJf "$wine_archive" --strip-components=1 -C "$wine_dir"
grep -Fxq 'Wine version 11.0' "$wine_dir/VERSION" || {
  echo "Wine source version check failed: expected VERSION file to declare version 11.0." >&2
  exit 1
}

box64_tree="$(git -C "$box_dir" rev-parse HEAD^{tree})"
jq -n \
  --arg status "sources-acquired-not-built" \
  --arg box64_tag "$box64_tag" \
  --arg box64_commit "$actual_commit" \
  --arg box64_tree "$box64_tree" \
  --arg box64_url "https://github.com/ptitSeb/box64" \
  --arg wine_version "$wine_version" \
  --arg wine_url "$wine_url" \
  --arg wine_archive_sha256 "$archive_sha" \
  --arg created_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{
    schema_version: 1,
    status: $status,
    created_utc: $created_utc,
    box64: {tag: $box64_tag, commit: $box64_commit, tree: $box64_tree, source_url: $box64_url},
    wine: {version: $wine_version, source_archive_url: $wine_url, source_archive_sha256: $wine_archive_sha256},
    notes: [
      "Source acquisition only; no executable runtime was built.",
      "Wine archive SHA-256 is calculated from the downloaded file; verify against an independently published WineHQ checksum/signature if available.",
      "A source checksum does not prove runtime compatibility or MT5 support."
    ]
  }' > "$out_dir/source-audit.json"

printf 'Source acquisition complete. Audit: %s/source-audit.json\n' "$out_dir"
printf 'Box64 commit: %s\nWine archive SHA-256: %s\n' "$actual_commit" "$archive_sha"
printf 'No Wine/Box64 binaries or Android runtime bundle were built.\n'
