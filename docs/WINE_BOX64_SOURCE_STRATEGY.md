# Runtime source selection and acquisition strategy

## Selected proof-of-concept architecture

**Target host:** Android ARM64 (arm64-v8a), with a glibc userland/rootfs (Ubuntu/Debian-style) isolated through a documented PRoot-compatible approach. Do not execute arbitrary Linux/glibc binaries directly in Android's Bionic app process.

**Windows x64 translation path:** AArch64 Box64 + x86_64 Wine (prefer a WoW64 build for 32-bit Windows components) + glibc rootfs. This is the first experiment because Box64's own Wine guide describes x86_64 Wine running under Box64. It is not an ARM64 Wine binary: ARM64 describes the Android host, while the x86_64 Wine process is translated. Box64's docs separately note that native Arm64 Wine/WoW64 has additional cpu.dll/Arm64EC requirements and recommend Hangover for Arm64 Wine. See the upstream Wine notes before changing architecture.

## Source/version pins for the first experiment

- **Box64 source candidate:** upstream tag v0.4.4, release page: https://github.com/ptitSeb/box64/releases/tag/v0.4.4. The upstream release page identifies the release commit prefix as 2f130fa. Resolve and record the full commit SHA during acquisition; do not use the short SHA as a manifest pin.
- **Wine source:** https://gitlab.winehq.org/wine/wine. No Wine version is approved yet. Select a current stable WineHQ tag only after checking that its x86_64 WoW64 configuration builds cleanly in the chosen glibc rootfs and passes smoke tests. Do not silently substitute a community repack or a different Wine build.
- **Integration reference only:** https://github.com/winebox64/winlator and https://github.com/winebox64/winlator-wb64. These demonstrate the combined Android stack (Wine, Box64, rootfs/PRoot, graphics components); they are not a source of unreviewed binary files to copy into this app.

A source tag is not a runnable Android artifact. Build provenance, complete dependency closure, license notices and SHA-256 values must be recorded from the actual produced artifacts.

## Dependency and compatibility inventory

| Component | Requirement for the first PoC | Verification |
|---|---|---|
| Android host | ARM64 device, supported Android version, sufficient storage/RAM | Device ABI, Android version, kernel/page-size details |
| Box64 | AArch64 build for the selected glibc rootfs; Wine support configured as needed | file, readelf -h, box64 --version; inspect linked libraries |
| Wine | x86_64 WoW64 build, running inside the same glibc rootfs | file, wine --version, ldd inside rootfs, prefix initialization |
| Rootfs/libc | One pinned glibc distribution and its runtime libraries | Record release, package inventory and checksums; no host-Bionic assumptions |
| x86_64 runtime libraries | Wine-required x86_64 libraries, including C/C++ runtime/unwind libraries as required by the chosen build | Resolve every DT_NEEDED dependency inside rootfs; no unresolved libraries |
| Graphics/display | Start with the simplest documented X11/Vulkan path available for the device; GPU drivers are optional only if the selected path can run without them | Log graphics backend and run a basic Wine GUI smoke test |
| Audio/input | Add only after process launch succeeds; host integration is separate work | Manual touch/mouse/keyboard and audio checks |
| MT5 | Official desktop installer, separate from runtime artifacts | Install and launch only after Wine smoke test; no trading credentials |

## Reproducible acquisition steps

1. Fetch Box64 from its upstream Git repository at tag v0.4.4, resolve the full commit SHA, inspect its license and build docs, and build for the chosen glibc rootfs. The upstream compile guide has separate ARM64 and Termux paths; do not assume the native Termux build runs glibc Linux binaries.
2. Choose and record a stable WineHQ source tag after checking the WoW64 build mode and dependency list. Save full commit SHA, build flags, toolchain versions and package manifest.
3. Build/package in a pinned rootfs/container. Capture package list, build logs, license notices and SHA-256 for all artifacts.
4. Stage the real Box64 executable at runtime/bundle/bin/box64, the Wine launcher at runtime/bundle/bin/wine, and the rootfs at runtime/bundle/rootfs/. Keep the manifest's source URLs and hashes tied to the actual produced files.
5. Run bash scripts/check-runtime-bundle.sh runtime/bundle and bash scripts/verify-runtime-provenance.sh runtime/bundle. These are static preflights, not execution tests.
6. On a real ARM64 Android device, test Box64, Wine version output, prefix initialization, GUI/display, input and process shutdown. Then test MT5 installer/terminal. Keep device logs and test matrix; do not enable the app's launch button until the test is repeatable.

## Explicit limitations

- The repository currently contains no runnable Wine/Box64 binaries or rootfs. No runtime bundle is being claimed as built by this commit.
- Box64 v0.4.4 is a source candidate, not a verified Android binary.
- Wine is deliberately not pinned until a current official stable source tag and WoW64 build have been verified. A fabricated or stale version pin would reduce reliability.
- Passing JSON/hash/ELF checks does not establish source authenticity, malware safety, successful startup, GPU compatibility, or MT5 compatibility.
- MT5 Desktop on Android remains experimental; Windows API coverage, graphics, input, networking, device memory, Android lifecycle, and 32/64-bit components can prevent it from running.
- Do not commit large runtime binaries or any MT5 account credentials to Git. Use release assets or a controlled artifact store after review.
