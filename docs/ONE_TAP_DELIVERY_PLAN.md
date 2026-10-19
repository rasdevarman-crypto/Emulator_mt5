# One-tap delivery implementation plan

## Delivery variants

- `allInOne`: bundles the exact validated `runtime-bundle-candidate.tar.xz` inside the app's assets. First launch extracts it to app-private storage. This variant is only buildable when the workflow downloads the verified runtime artifact and confirms its checksum/provenance first.
- `autoDownload`: downloads a version-pinned bundle from a stable HTTPS URL, verifies a pinned SHA-256 before extraction, then installs transactionally. The URL and SHA-256 must be supplied by a verified release workflow; no mutable `latest` URL or invented checksum is allowed.

## Build integration

The Android workflow should run after the runtime workflow succeeds, or be manually dispatched with an explicit successful runtime run ID. It must download `runtime-bundle-candidate` and `arm64-runtime-smoke-evidence` from that exact run, verify their SHA-256 evidence, and build both variants from the same commit. Never start a second runtime build just to package the APK.

The all-in-one build may package the runtime archive as an asset; check the resulting APK size. If it is too large for practical sideloading, prefer Android App Bundle + Play Asset Delivery for store distribution or the automatic-download variant for sideloading. Google Play documents a 100 MB legacy APK limit and separate Android App Bundle / asset-pack limits.

## Runtime download/install safety

1. Require HTTPS and an explicit pinned SHA-256.
2. Download to a temporary file with a finite timeout and maximum size; report progress and retry safely.
3. Verify SHA-256 before opening the archive.
4. Extract into a staging directory with path traversal and symlink escape checks.
5. Validate manifest, ARM64 Box64, both rootfs trees, Wine loader, provenance and expected checksums.
6. Atomically switch the active bundle; preserve the last known-good bundle if installation fails.
7. Initialize Wine prefix idempotently and run preflight. Never mark setup complete based only on successful extraction.

## Release gate

A candidate runtime and successful ARM64 Linux smoke test are necessary but not sufficient. Physical ARM64 Android tests must cover Android-native PRoot, Wine prefix initialization, 32-/64-bit PE fixtures, MT5 GUI/input/network/chart behavior and stability. Only after evidence review may the repository Actions variable `MT5_DEVICE_VALIDATION_PASSED=true` be set.

## Current limitation

The current runtime build is still running Wine compilation. The bundle and ARM64 smoke stage are not complete yet. The app still has manual archive and MT5 installer selection, so neither delivery variant is complete today.
