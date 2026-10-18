# Experimental Wine WoW64 + Box64 build pipeline

## What this workflow builds

The GitHub Actions workflow `.github/workflows/runtime-build.yml` is a source-pinned build pipeline with two separate build lanes:

1. **Box64 ARM64 glibc Linux executable** from upstream tag `v0.4.4`, commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`, built on an ARM64 Ubuntu runner with the ARM dynamic recompiler enabled.
2. **Wine 11.0 x86_64 Linux install tree** configured with `--enable-archs=i386,x86_64` for the new WoW64 PE architecture mode. The Wine source archive is pinned by SHA-256:
   `c07a6857933c1fc60dff5448d79f39c92481c1e9db5aa628db9d0358446e0701`.

The workflow records compiler/toolchain versions, installed Ubuntu package versions, configure/build/install logs, ELF architecture for Box64, and checksums for produced install-tree archives. If both builds succeed, later stages assemble two separate Ubuntu 24.04 root filesystems: ARM64 glibc for native Box64/PRoot and amd64 glibc for Wine. The amd64 rootfs installs runtime libraries and audits Wine ELF dependencies with `ldd`. A candidate bundle manifest and provenance records are then generated and statically checked. Artifacts expire after seven days. The build lanes are independent after source verification so that failures can be diagnosed separately.

## Trigger and outputs

Run it manually from **Actions → Experimental Wine WoW64 + Box64 Build → Run workflow**, or push a change to the workflow or source-acquisition script.

Expected artifacts when stages succeed:

- `box64-arm64-build-output`
- `wine-11.0-wow64-x86_64-build-output` (also contains small i386/x86_64 Windows PE smoke fixtures)
- `arm64-host-rootfs`
- `runtime-bundle-candidate`
- `arm64-runtime-smoke-evidence`

A successful candidate package must pass manifest/provenance checks and an ARM64 Linux PRoot smoke stage that attempts Wine prefix initialization and executes both PE fixtures under Box64. A failed lane uploads any logs and partial output that exist, for diagnosis.

## Reproducibility boundary

This pipeline pins the upstream source bytes and Box64 commit, and records the build environment. It is **source-pinned and repeatable, but not yet guaranteed bit-for-bit reproducible**: GitHub-hosted runner images and Ubuntu package repositories evolve, and this workflow records installed package versions rather than resolving every dependency to an immutable container digest and package snapshot. Build outputs may differ between dates.

The Wine archive hash is pinned to the bytes captured during the successful source-acquisition run on 2026-10-18. This SHA-256 check detects changed/corrupt archive bytes, but it is not an independent WineHQ signature verification. Revalidate the upstream release and signature/checksum policy before promoting artifacts.

## What this does not deliver

These are separate build outputs, **not a combined Android runtime bundle**. In particular, this workflow does not yet:

- assemble and pin a complete x86_64 glibc rootfs and all runtime shared libraries;
- package Android graphics/display/audio/input integration;
- prove all dynamic dependencies resolve inside the intended rootfs;
- test the binaries on a real Android ARM64 device under the intended PRoot/rootfs setup;
- initialize a Wine prefix and pass 32-bit and 64-bit Windows PE smoke tests under Box64;
- install or run MetaTrader 5.

Do not label either artifact as Android-ready, and do not set the runtime manifest to `ready` based only on a successful compile. The next release gate is dependency closure + rootfs packaging + on-device smoke tests. Keep all build outputs as CI artifacts until that gate passes.


## Android and MT5 release gate

The current Android app does not launch PRoot, Box64, or Wine; its MT5 launch button is intentionally disabled. A CI Linux/PRoot smoke test is not equivalent to physical Android validation. Follow [the Android and MT5 test plan](ANDROID_MT5_TEST_PLAN.md) and use `bash scripts/smoke-test-runtime-bundle.sh bundle` on an ARM64 PRoot environment. Do not mark a candidate as Android-validated or MT5-validated without that evidence.
