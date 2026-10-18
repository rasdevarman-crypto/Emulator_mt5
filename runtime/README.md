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
