# Runtime source selection and acquisition strategy

## Decision for the first proof of concept

Use upstream projects as source references, not a prebuilt Winlator APK as a drop-in runtime. Winlator demonstrates that an Android app can combine Wine with Box64, but its runtime is an integrated stack (root filesystem, Wine build, graphics drivers, translation layers and app-side orchestration). Copying only `box64` and `wine` from an arbitrary APK would not produce a reproducible or necessarily compatible bundle.

Reference projects:
- Box64 upstream: https://github.com/ptitSeb/box64
- Box64 Wine notes: https://github.com/ptitSeb/box64/blob/main/docs/WINE.md
- Winlator WB64 (architecture/integration reference): https://github.com/winebox64/winlator
- Wine upstream: https://gitlab.winehq.org/wine/wine

These links identify upstreams, not a selected release or a verified binary. Before pinning a release, inspect its license, source revision, release assets, target ABI, build configuration and dependency closure. Record immutable commit/tag identifiers and SHA-256 digests; do not use a moving `main`/ `master` reference in a release manifest.

## Why the first target is ARM64

The current Android app targets Android and must not assume that a Linux desktop Box64 build runs directly in Android's Bionic userspace. Box64 and Wine must be built/configured for the actual Android execution environment, or run inside a documented compatible userspace/rootfs. The runtime must include all required shared libraries and a process-launch strategy. A matching ELF machine value alone is insufficient.

## Staged acquisition and proof gates

1. **Source review:** pin exact Box64 and Wine revisions; record licenses/notices and any patches. Do not download opaque binaries until provenance is documented.
2. **Build environment:** choose one reproducible ARM64 build approach and document toolchain, libc/userspace, configure flags, patches and dependency versions. Keep proprietary MT5 installer files and credentials out of CI.
3. **Bundle assembly:** stage the actual executables and complete rootfs/dependencies under `runtime/bundle/`; generate `manifest.json` from verified artifacts. Never copy placeholder values from `runtime-manifest.example.json`.
4. **Provenance preflight:** run `bash scripts/check-runtime-bundle.sh runtime/bundle` and `bash scripts/verify-runtime-provenance.sh runtime/bundle`. These are static checks only.
5. **Execution proof:** on a compatible ARM64 Android device/emulator, capture `box64 --version`, `wine --version`, Wine prefix initialization, process exit codes and logs. Keep this separate from APK compile success.
6. **MT5 proof:** only after the Wine smoke test passes, test the official desktop installer/terminal without trading credentials. Record app version, device SoC/GPU, Android version, graphics backend, install/launch outcome and logs. Do not enable the launch button until this is repeatable.

## Explicit non-goals / limitations

- No runtime binaries are bundled by this document.
- No version or artifact is yet approved by this source review.
- Static manifest/hash checks do not establish that a binary is safe, correctly built, executable on Android, or compatible with MT5.
- Desktop MT5 support is a hypothesis to test, not a guarantee; graphics, input, network, 32-bit/64-bit components, Windows APIs and Android lifecycle can block it.
- Do not commit large runtime binaries to Git. Store reviewed build artifacts in release assets or a controlled artifact store with hashes and license notices.
