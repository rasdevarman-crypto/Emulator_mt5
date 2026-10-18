# Android and MetaTrader 5 validation plan

## What CI can verify

The runtime build workflow is intended to produce a candidate with two different Linux userspaces:

- `rootfs-arm64/`: Ubuntu 24.04 ARM64 glibc environment containing the native Box64 binary and PRoot.
- `rootfs/`: Ubuntu 24.04 amd64 userspace containing Wine 11.0 new WoW64 and its x86_64 shared-library dependencies.

The CI smoke stage runs on an ARM64 Linux runner and attempts to:
1. execute Box64 in the ARM64 glibc rootfs;
2. report Wine's version under Box64;
3. initialize a disposable Wine prefix;
4. run test PE executables compiled for both i386 and x86_64 and verify their output.

This is a Linux/PRoot smoke test, not a physical Android device test.

## Physical Android test still required

No Android device is attached to this CI workflow, so it cannot establish Android kernel/PRoot behavior, graphics integration, touch input, audio, screen rendering, power-management behavior, or sustained stability. After a candidate artifact is produced:

1. Extract the artifact while preserving both `bundle/` and `provenance/`.
2. On an ARM64 Android environment with a working PRoot installation, run:
   `bash scripts/smoke-test-runtime-bundle.sh bundle`
3. Save the terminal output and `pe-smoke.sha256`; do not include broker credentials or account details in logs.
4. Verify the app can safely access the bundle from app-private storage. The current Android app does **not** yet launch PRoot, Box64, or Wine; its MT5 launch button is intentionally disabled until runtime integration is implemented.

## MetaTrader 5 test gate

A successful tiny Windows PE smoke test is not proof that MT5 works. A separate manual test needs the official MT5 installer from MetaQuotes, a GUI/display bridge, input handling, networking, and a clean disposable Wine prefix. Test installation, first launch, login screen only, chart rendering, mouse/touch interaction, shutdown/restart, and logs. Never commit trading credentials or automate a real-money trade. Do not label the runtime MT5-compatible until these tests have been completed on a physical device and their evidence reviewed.

## Current status labels

- `candidate`: CI assembled files and recorded hashes; compatibility is not implied.
- `Linux smoke passed`: Box64/Wine and 32-/64-bit PE test programs passed on an ARM64 Linux runner.
- `Android validated`: the physical-device smoke tests passed.
- `MT5 validated`: MT5 installation and interactive tests passed on a physical Android device.

These labels are deliberately separate.
