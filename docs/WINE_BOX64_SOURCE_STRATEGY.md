# Runtime source selection and acquisition strategy

## Selected proof-of-concept architecture

**Target host:** Android ARM64 (arm64-v8a), with a glibc userland/rootfs (Ubuntu/Debian-style) isolated through a documented PRoot-compatible approach. Do not execute arbitrary Linux/glibc binaries directly in Android's Bionic app process.

**Windows x64 translation path:** AArch64 Box64 + x86_64 Wine + glibc rootfs. This is the first experiment because Box64's own Wine guide describes x86_64 Wine running under Box64. ARM64 describes the Android host; the x86_64 Wine process is translated. Box64's docs separately note that native Arm64 Wine/WoW64 has additional cpu.dll/Arm64EC requirements and recommend Hangover for Arm64 Wine. See upstream notes before changing architecture.

## Selected official WineHQ version and WoW64 configuration

- **Selected Wine release:** **Wine 11.0 stable** (official WineHQ stable release announced 2026-01-13), source archive: https://dl.winehq.org/wine/source/11.0/wine-11.0.tar.xz
- **Why this version:** choose the official stable release as the reproducible baseline rather than the current 11.20 development release. This is a source/version selection, not a claim that an Android-ready binary has been built or tested.
- **Selected WoW64 mode:** Wine's **new WoW64** architecture, built from the x86_64 Wine source tree with both i386 and x86_64 PE architectures enabled. Wine 11.0 release notes say the new WoW64 mode is fully supported and supports 16-bit applications; pure 32-bit `WINEARCH=win32` prefixes are deprecated/unsupported in this mode.
- **Configure choice:** `./configure --enable-archs=i386,x86_64` in a build environment with the required native build tools and i386/x86_64 MinGW PE toolchains. Do **not** use `--enable-win64` as a substitute: that option builds a Win64-only emulator and is not the selected dual-architecture PE build.
- **Prefix/smoke-test choice:** use a fresh default 64-bit prefix; test `WINEARCH=wow64` only where supported by the build/prefix workflow. Confirm `wine --version`, initialize the prefix, and launch known 32-bit and 64-bit Windows test applications before attempting MT5.
- **Important platform constraint:** Box64 translates x86_64 Linux userspace binaries; it does not make a Wine build automatically compatible with Android/Bionic. The Wine Unix side, x86_64 libraries, glibc, display/audio and process environment must be provided consistently inside the selected rootfs. A successful configure/build on an x86_64 Linux machine does not prove the packaged runtime works on Android.

## Box64 source pin

- **Box64 source candidate:** upstream tag v0.4.4, release page: https://github.com/ptitSeb/box64/releases/tag/v0.4.4. Full upstream tag commit selected and pinned: `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a` (tag `v0.4.4`). The source-acquisition script verifies that exact commit.
- Integration references only: https://github.com/winebox64/winlator and https://github.com/winebox64/winlator-wb64. These demonstrate combined Android stacks (Wine, Box64, rootfs/PRoot, graphics components); they are not a source of unreviewed binary files to copy into this app.

## Dependency and compatibility inventory

| Component | Requirement for the first PoC | Verification |
|---|---|---|
| Android host | ARM64 device, supported Android version, sufficient storage/RAM | Device ABI, Android version, kernel/page-size details |
| Box64 | AArch64 build for the selected glibc rootfs; Wine support configured as needed | file, readelf -h, box64 --version; inspect linked libraries |
| Wine | Official WineHQ 11.0 source; x86_64 Unix build with `--enable-archs=i386,x86_64` | Record configure output; file, wine --version, dependency resolution, prefix initialization, 32-bit and 64-bit PE smoke tests |
| Rootfs/libc | One pinned glibc distribution and its runtime libraries | Record release, package inventory and checksums; no host-Bionic assumptions |
| x86_64 runtime libraries | Wine-required x86_64 libraries, including C/C++ runtime/unwind libraries as required by the chosen build | Resolve every DT_NEEDED dependency inside rootfs and inspect runtime-loaded libraries |
| PE toolchains | i386 and x86_64 MinGW-compatible compiler/linker support for `--enable-archs` | Capture toolchain versions and configure/build logs; fail if either requested PE architecture is silently omitted |
| Graphics/display | Start with the simplest documented X11/Vulkan path available for the device; GPU drivers are optional only if the selected path can run without them | Log graphics backend and run a basic Wine GUI smoke test |
| Audio/input | Add only after process launch succeeds; host integration is separate work | Manual touch/mouse/keyboard and audio checks |
| MT5 | Official desktop installer, separate from runtime artifacts | Install and launch only after Wine smoke test; no trading credentials |

## Reproducible acquisition steps

1. Fetch Box64 from its upstream Git repository at tag v0.4.4 and commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`; inspect its license and build docs, and build for the chosen glibc rootfs. The upstream compile guide has separate ARM64 and Termux paths; do not assume the native Termux build runs glibc Linux binaries.
2. Fetch the official WineHQ 11.0 source archive above and verify its official source URL and checksum/signature if published. Record the archive SHA-256 locally; do not invent a checksum or substitute a third-party binary.
3. Configure Wine for the **new WoW64** mode with `--enable-archs=i386,x86_64`, using a pinned build environment with the required PE toolchains. Record the exact configure output, compiler/toolchain versions, package inventory and build log. If this configuration cannot be built reproducibly, stop and document the missing dependency rather than silently switching modes.
4. Build/package in a pinned rootfs/container. Capture package list, license notices and SHA-256 for all produced artifacts.
5. Stage the real Box64 executable at `runtime/bundle/bin/box64`, the Wine launcher at `runtime/bundle/bin/wine`, and the rootfs at `runtime/bundle/rootfs/`. Keep the manifest's source URLs and hashes tied to the actual produced files.
6. Run `bash scripts/check-runtime-bundle.sh runtime/bundle` and `bash scripts/verify-runtime-provenance.sh runtime/bundle`. These are static preflights, not execution tests.
7. On a real ARM64 Android device, test Box64, Wine version output, prefix initialization, 32-bit and 64-bit PE programs, GUI/display, input and process shutdown. Then test the MT5 installer/terminal. Keep device logs and test matrix; do not enable the app's launch button until the test is repeatable.

## Explicit limitations

- The repository currently contains no runnable Wine/Box64 binaries or rootfs. No runtime bundle is being claimed as built by this commit.
- Box64 v0.4.4 is a source candidate pinned to commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`, not a verified Android binary.
- Wine 11.0 stable and the new WoW64 configure mode are selected from official WineHQ release information, but the cross-platform build, dependency closure, Android execution and MT5 compatibility are not yet verified.
- Passing JSON/hash/ELF checks does not establish source authenticity, malware safety, successful startup, GPU compatibility, or MT5 compatibility.
- MT5 Desktop on Android remains experimental; Windows API coverage, graphics, input, networking, device memory, Android lifecycle, and 32/64-bit components can prevent it from running.
- Do not commit large runtime binaries or any MT5 account credentials to Git. Use release assets or a controlled artifact store after review.


## Automated source acquisition

Run `bash scripts/acquire-runtime-sources.sh build/runtime-sources` to acquire the pinned Box64 source tree and official WineHQ 11.0 source archive, check the Box64 commit and Wine source version, and produce `source-audit.json` with the downloaded Wine archive SHA-256. The `Runtime Source Acquisition Audit` workflow runs the same procedure and uploads source inputs plus the audit as a short-lived CI artifact. This is source acquisition only: it does not build Wine/Box64 executables, select a final rootfs, or create `runtime/bundle`.
