# One-tap delivery plan

## User experience goal

A user installs the APK, opens it, waits for automatic preparation, and taps **Launch MT5**. No manually browsing for archives, extracting files, typing shell commands, or editing configuration files should be required.

Two delivery variants are required:

- **All-in-one APK:** package the exact validated runtime bundle and Android-native PRoot together with the app, subject to APK/Play delivery size constraints.
- **Automatic-download APK:** download a version-pinned runtime bundle from a stable HTTPS release endpoint, verify its expected SHA-256 and provenance before extraction, and install it into app-private storage. Show progress, allow retry, and preserve a previous known-good installation if an update fails.

## Required implementation work

1. Finish the current candidate runtime build and ARM64 Linux smoke stage. Reuse successful artifacts; do not rerun successful jobs just to repeat work.
2. Define and validate a versioned runtime manifest containing the runtime version, source commits, file hashes, expected directory layout, supported ABI, and minimum free-storage requirement.
3. Add an app-side runtime repository configuration with a pinned version and checksum. Do not trust an unversioned "latest" archive or accept an archive solely because it extracts.
4. Make installation transactional: download to a temporary file, verify hash/provenance, extract to a staging directory, validate required files, then atomically switch the active runtime. Preserve the last known-good runtime on failure.
5. Make first-run setup automatic and idempotent: storage checks, runtime installation, Wine prefix initialization, runtime preflight, and clear recovery messages.
6. Replace manual installer selection with an explicit, user-friendly MT5 installation step. Use an official MetaQuotes source; do not bundle a proprietary MT5 installer unless distribution rights and licensing have been reviewed.
7. Implement the primary Launch MT5 action only when the complete runtime and MT5 executable are present. Show meaningful process failures rather than claiming that a process launch proves GUI success.
8. Build both APK variants from the same tested app revision. The all-in-one build must consume the exact verified runtime artifact; the download build must reference the exact same version and checksum.
9. Record APK and runtime SHA-256 checksums and associate each test report with exact commit, APK hash, runtime version/hash, device model, Android version, and test date.

## Validation gates

- **Runtime candidate:** checksums/provenance and file-layout validation pass.
- **Linux ARM64 smoke:** Box64 runs; Wine initializes a disposable prefix; 32-bit and 64-bit PE fixtures pass.
- **Android integration:** app-private installation, Android-native PRoot, paths, permissions, lifecycle, and clean restart pass on a physical ARM64 Android device.
- **MT5 functional validation:** official installer/client starts; GUI renders; login/server UI is usable; demo-account connectivity, quote/chart rendering, touch/input, restart, and 30-minute stability pass.
- **Release:** only after the preceding evidence has been reviewed. GitHub Release publication is gated by the repository Actions variable `MT5_DEVICE_VALIDATION_PASSED=true`. Do not set it based on CI-only results.

## Current limitations

The existing app still has manual runtime archive and installer selection. The current runtime pipeline is experimental and has not yet established real-device MT5 compatibility. The two delivery variants are targets, not completed deliverables. No release APK should be described as fully functional until the physical-device gate passes.
