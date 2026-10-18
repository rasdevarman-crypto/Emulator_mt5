# Runtime dependency matrix

This matrix is for the first ARM64 Android proof of concept. It is a build/test checklist, not evidence that the runtime exists.

## Proposed stack

- Host ABI: Android arm64-v8a.
- Linux userland: pinned glibc rootfs; do not assume Android Bionic can load desktop Linux builds.
- Translator: Box64 source candidate v0.4.4 from https://github.com/ptitSeb/box64/releases/tag/v0.4.4. Resolve the full commit SHA before building.
- Windows compatibility layer: official WineHQ x86_64 WoW64 build. Wine tag intentionally remains unpinned until the build configuration and dependency closure are verified.
- Process/filesystem isolation: PRoot-compatible launcher and explicit paths/environment.
- Graphics: keep optional and separately tested; Winlator-style stacks may need Mesa/Vulkan/Turnip or another backend depending on the device.

## Required dependency evidence before a bundle can be accepted

1. Rootfs distro/version and full package inventory.
2. Box64 full source commit, license, configure options, compiler/toolchain version and build log.
3. Wine full source commit/tag, license, WoW64 build mode, configure options, compiler/toolchain version and build log.
4. SHA-256 for every executable/archive included in the bundle.
5. Complete shared-library dependency resolution inside the rootfs (DT_NEEDED plus runtime-loaded libraries).
6. Runtime logs for box64 --version, wine --version, Wine prefix initialization and a basic Windows GUI application.
7. Device test record: Android version, ABI, SoC, GPU, Vulkan capability, RAM/storage, process exit codes and logs.

## Acceptance status

- Box64 version candidate identified: v0.4.4 (source only).
- Wine version: pending official stable tag/build verification.
- Rootfs version: pending pinned image/package selection.
- Binary checksums: unavailable until actual artifacts are built.
- Android execution and MT5: not tested.
