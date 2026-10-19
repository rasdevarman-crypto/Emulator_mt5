# MT5 RAS Runner

Android launcher scaffold for the experimental Wine 11.0 WoW64 + Box64 runtime project.

## Current verified status

- Android debug APK builds in GitHub Actions.
- Android-native AArch64 PRoot is built and packaged by the Android workflow.
- The runtime build workflow builds Box64 and Wine separately, then assembles a candidate dual-rootfs bundle and performs Linux/ARM64 smoke checks.
- The APK currently requires manual selection of a runtime archive and MT5 Windows installer. It is **not yet a one-tap, all-in-one MT5 app**.
- A successful APK build or Linux smoke test does not prove that Windows MT5 works on a physical Android device.

## Target delivery modes

1. **All-in-one APK** — include the verified runtime bundle in the APK when package size and Android delivery constraints permit.
2. **Automatic-download APK** — download a version-pinned runtime bundle, verify SHA-256 and provenance, then install it into app-private storage without requiring users to handle archives.
3. Both variants should expose a simple setup status and one primary **Launch MT5** action. MT5 account credentials must be entered by the user and must never be stored in build logs.

See [the one-tap delivery plan](docs/ONE_TAP_DELIVERY_PLAN.md) and [the physical Android validation plan](docs/ANDROID_MT5_TEST_PLAN.md).

## Release policy

GitHub Release publication is fail-closed. The repository Actions variable `MT5_DEVICE_VALIDATION_PASSED=true` must not be set until a real ARM64 Android device has passed the Wine/Box64 and interactive MT5 tests and the evidence has been reviewed. CI build success alone is insufficient.

The release APK is currently CI-debug-signed and is not a production-signed distribution build.
