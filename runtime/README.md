# Runtime bundle contract (scaffold only)

This folder documents the proposed Wine/Box64 bundle. **No runnable Wine/Box64 payload is included in this repository.**

A future test bundle should use this layout:

```text
bundle/
  manifest.json
  bin/box64
  bin/wine
  rootfs/...
```

Start from `runtime-manifest.example.json`, copy it to the bundle as `manifest.json`, and fill it only with verified values from the actual binaries. Do not leave example values in a release bundle.

Run the static checks with:

```bash
bash scripts/check-runtime-bundle.sh runtime/bundle
```

The check validates required files, executable bits, manifest metadata and (when `readelf` is available) ELF machine architecture. It does not execute binaries, verify provenance/signatures, resolve every shared-library dependency, or prove Wine/MT5 compatibility. Run it only after acquiring and reviewing a trusted bundle. Never include trading credentials in runtime tests.


## Next step: source provenance

Read [the source acquisition strategy](../docs/WINE_BOX64_SOURCE_STRATEGY.md) before selecting any binaries. The upstream links are reference points only; no release has been approved or bundled.

After a real candidate bundle has been staged, run:

```bash
bash scripts/check-runtime-bundle.sh runtime/bundle
bash scripts/verify-runtime-provenance.sh runtime/bundle
```

The provenance script requires `jq`, `sha256sum`, real HTTPS source URLs, non-placeholder version strings, and SHA-256 values matching the staged Box64 and Wine files. Set the manifest status to `candidate` only after the files and metadata have been assembled. Passing both scripts still does not establish runtime compatibility or safety.


## Source and dependency decision

- Read [the source strategy](../docs/WINE_BOX64_SOURCE_STRATEGY.md) before acquiring artifacts.
- Track required evidence in [the runtime dependency matrix](../docs/RUNTIME_DEPENDENCY_MATRIX.md).
- First Box64 source candidate: upstream v0.4.4 at commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`; it is source-only, not a built Android binary.
- Wine source/version selected: official WineHQ 11.0 stable with new WoW64 configure mode `--enable-archs=i386,x86_64`; the build and full dependency closure are not yet verified.
- Rootfs release remains unpinned until the runtime dependency closure can be verified.
- GitHub Actions checks shell syntax, the example manifest JSON, and that an absent bundle or placeholder metadata is rejected. The separate `Runtime Source Acquisition Audit` workflow acquires pinned source inputs only; it does not build or run Wine/MT5.


## Experimental build pipeline

See [the runtime build pipeline guide](../docs/RUNTIME_BUILD_PIPELINE.md) and workflow [runtime-build.yml](../.github/workflows/runtime-build.yml). The pipeline builds separate Box64 ARM64 and Wine x86_64 install-tree artifacts with logs and SHA-256 checksums. These are experimental build outputs only, not a ready-to-run Android runtime bundle; rootfs dependency closure and physical-device tests remain required.


## Dual-rootfs candidate layout

The experimental build workflow is designed to assemble both `rootfs-arm64/` (ARM64 glibc host environment for Box64) and `rootfs/` (amd64 glibc environment for Wine). Keeping these architectures separate is intentional: the native ARM64 Box64 executable cannot simply be run inside an amd64-only rootfs without ARM64 host libraries.

A candidate artifact is not an Android-ready release. Review the [build pipeline](../docs/RUNTIME_BUILD_PIPELINE.md) and [Android/MT5 test plan](../docs/ANDROID_MT5_TEST_PLAN.md). When the candidate and PE fixtures are available, the ARM64 PRoot smoke script is:

```bash
bash scripts/smoke-test-runtime-bundle.sh bundle
```

The script checks Box64, Wine prefix initialization and tiny 32-bit/64-bit PE programs. It does not run MT5 and does not prove Android app integration. The current Android app still has no PRoot/Wine launcher integration; the MT5 launch button remains disabled until that is implemented and tested.
