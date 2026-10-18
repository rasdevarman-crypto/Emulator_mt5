# Runtime dependency matrix

This matrix is for the first ARM64 Android proof of concept. It is a build/test checklist, not evidence that the runtime exists.

## Selected stack

- Host ABI: Android arm64-v8a.
- Linux userland: pinned glibc rootfs; do not assume Android Bionic can load desktop Linux builds.
- Translator: Box64 source candidate v0.4.4 from https://github.com/ptitSeb/box64/releases/tag/v0.4.4. Resolve the full commit SHA before building.
- Windows compatibility layer: **official WineHQ 11.0 stable** source archive at https://dl.winehq.org/wine/source/11.0/wine-11.0.tar.xz.
- Wine configuration: **new WoW64 mode** via `./configure --enable-archs=i386,x86_64`. This requests both i386 and x86_64 PE architectures and is distinct from `--enable-win64`. Wine 11.0 declares the new WoW64 mode fully supported. Pure 32-bit `WINEARCH=win32` prefixes are not supported in this mode; start with a fresh default 64-bit prefix.
- Process/filesystem isolation: PRoot-compatible launcher and explicit paths/environment.
- Graphics: keep optional and separately tested; Winlator-style stacks may need Mesa/Vulkan/Turnip or another backend depending on the device.

## Build configuration and dependency gates

1. Build environment must provide the host C/C++ toolchain, Wine build dependencies, and MinGW-compatible PE toolchains for both i386 and x86_64.
2. Capture the full Wine configure output. Fail the build if `--enable-archs=i386,x86_64` cannot configure both architectures; do not silently fall back to a Win64-only build.
3. Record the exact source archive SHA-256 and, if published, verify the upstream checksum/signature. Record Box64's full commit SHA rather than only its release tag/short hash.
4. Resolve the Wine Unix binary's full shared-library dependency closure inside the selected pinned glibc rootfs. Include runtime-loaded libraries and required data files, not just DT_NEEDED.
5. Do not assume the result of an x86_64 Linux build runs on Android just because Box64 is present. Validate process execution and filesystem paths within the rootfs.

## Required dependency evidence before a bundle can be accepted

1. Rootfs distro/version and full package inventory.
2. Box64 full source commit, license, configure options, compiler/toolchain version and build log.
3. Wine 11.0 source URL, source archive checksum, license, `--enable-archs=i386,x86_64` configure output, compiler/toolchain version and build log.
4. SHA-256 for every executable/archive included in the bundle.
5. Complete shared-library dependency resolution inside the rootfs (DT_NEEDED plus runtime-loaded libraries).
6. Runtime logs for `box64 --version`, `wine --version`, Wine prefix initialization and basic 32-bit and 64-bit Windows GUI/test applications.
7. Device test record: Android version, ABI, SoC, GPU, Vulkan capability, RAM/storage, process exit codes and logs.

## Acceptance status

- Box64 version candidate identified: v0.4.4 (source only); full commit SHA not yet recorded.
- Wine source/version selected: official WineHQ 11.0 stable; source archive/build not yet acquired or verified.
- Wine WoW64 configuration selected: `--enable-archs=i386,x86_64`; build not yet executed.
- Rootfs version: pending pinned image/package selection.
- Binary checksums: unavailable until actual artifacts are acquired/built.
- Android execution and MT5: not tested.
