# Wine/Box64 Runtime Implementation Plan

## Status and scope

This repository currently contains an Android launcher scaffold. It does **not** contain a Wine runtime, Box64 binary, Windows root filesystem, MT5 installer, or graphics/input integration. The existing Android APK build proves only that the launcher compiles.

This plan defines a staged path toward testing Windows desktop MetaTrader 5 on Android ARM64. It is not a claim that MT5 currently runs, and it does not promise compatibility across devices.

## Proposed repository layout

```text
app/                              # Existing native Android launcher
runtime/
  README.md                       # Runtime bundle contract and limitations
  runtime-manifest.example.json   # Example metadata; not a real runtime
  bundle/                         # Generated/downloaded locally; do not commit binaries
    manifest.json
    bin/
      box64
      wine
    rootfs/                       # Wine runtime libraries and prefix template
    drivers/                      # Selected graphics/input integration, if required
scripts/
  check-runtime-bundle.sh         # Static bundle/ABI checks
docs/
  WINE_BOX64_IMPLEMENTATION_PLAN.md
```

Do not commit downloaded third-party binaries or a Wine prefix by default. Pin trusted upstream sources, record versions and hashes, review licenses, and distribute large runtime assets separately. A manifest example is metadata only; it is not an installable bundle.

## Compatibility gates

1. **Device and ABI discovery**
   - Record Android version, API level, CPU ABI, available RAM/storage, GPU and graphics driver.
   - Target ARM64 first. Do not assume an ARM64 Android device can directly execute an x86-64 Linux binary.
2. **Box64 feasibility**
   - Confirm the chosen Box64 build supports the device's ARM64 userspace/kernel and required instruction set.
   - Verify all native dependencies and dynamic loader paths. Static ELF inspection is necessary but not sufficient.
3. **Wine architecture**
   - Choose a Wine build and WoW64/architecture configuration compatible with the target MT5 Windows executable and Box64 strategy.
   - Confirm required 32-bit/64-bit Windows components and native libraries. A generic Wine package may not work on Android.
4. **Graphics and input**
   - Select and test the actual graphics path (for example, a compatible Vulkan/OpenGL translation stack where required), window surface integration, touch/mouse mapping, keyboard, clipboard, and orientation.
   - GPU/driver support varies by device; successful process startup does not prove usable rendering.
5. **MT5 installer and terminal**
   - Obtain the installer from MetaQuotes' official distribution channel.
   - Run it in a disposable test prefix; capture exit code and logs.
   - Verify terminal launch, login, live chart rendering, network access, shutdown and relaunch. Never test with trading credentials in CI.
6. **Android integration**
   - Implement a runtime manager with explicit states (missing, validating, installing, ready, running, failed).
   - Run long-lived processes off the UI thread; provide cancellation, lifecycle handling, log redaction and process cleanup.
   - Keep the launch action disabled until a real installed terminal and runtime pass checks.
7. **Release gate**
   - Keep the existing APK compile job.
   - Add static runtime-bundle checks only when a real bundle is supplied.
   - Run emulator/device smoke tests separately. A green Gradle build must never be labelled an MT5 compatibility pass.

## Proposed implementation phases

### Phase 0 — inventory and legal/source review
Identify the exact Wine 11.20 archive (if that version is intended), its source, license, checksums, architecture, and Android suitability. Identify a compatible Box64 build and dependency set. No archive is assumed to be present.

### Phase 1 — reproducible runtime bundle
Create a documented bundle layout, manifest, hash verification, extraction rules, and ABI/dependency checks. Reject unknown archives and path traversal; do not execute downloaded code before validation.

### Phase 2 — isolated command-line proof
On a target ARM64 device, execute Box64/Wine in a controlled test directory. Capture environment, dependency failures, process exit status, and logs. Prove a simple Windows test program first.

### Phase 3 — MT5 proof
Install the official MT5 Windows terminal in a disposable prefix. Validate launch, graphics/input, network, persistence, and recovery on specific physical devices.

### Phase 4 — Android app integration
Replace the current file-picker-only flow with safe installer import, runtime provisioning, progress/error states, process supervision, and real terminal launch. Keep user data and runtime assets separate and provide reset/uninstall behavior.

### Phase 5 — CI and release
Build the Android APK; run shell/static checks; validate manifests and pinned hashes. Only run MT5 smoke tests on controlled self-hosted ARM64 devices if available. Publish a compatibility matrix by device/GPU/Android version.

## Known limitations and risks

- Windows desktop MT5 on Android is not guaranteed by Wine or Box64 alone.
- ARM64 hardware, Android userspace restrictions, libc/loader assumptions, Wine build configuration, 32-bit support, graphics drivers, and terminal updates can all break compatibility.
- Android background execution and process lifecycle differ from desktop Linux.
- GitHub-hosted Linux CI cannot prove physical Android GPU/input compatibility.
- The repository currently has no runtime binaries or tests, so compatibility is **unverified**.
- The MetaTrader Android-native app is a simpler alternative if the goal is mobile trading rather than running the Windows desktop terminal.

## Acceptance criteria

A phase is complete only with evidence: exact source/version/hash, ABI and dependency report, logs and exit status, device details, and repeatable test steps. Never enable “MT5 চালু করুন” merely because files exist.
