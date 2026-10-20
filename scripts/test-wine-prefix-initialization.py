#!/usr/bin/env python3
"""Regression checks for explicit Wine prefix initialization without MT5 auto-launch."""
from pathlib import Path
import sys

runtime = Path("app/src/main/java/com/ras/mt5runner/RuntimeManager.kt").read_text(encoding="utf-8")
activity = Path("app/src/main/java/com/ras/mt5runner/MainActivity.kt").read_text(encoding="utf-8")
checks = {
    "RuntimeManager exposes explicit wineboot initialization": "fun initializeWinePrefix(" in runtime and '"/x86root/opt/wine-11.0-wow64/bin/wineboot"' in runtime and '"--init"' in runtime,
    "prefix is bound to app-private storage": '"${prefix.absolutePath}:/wineprefix"' in runtime,
    "verification requires registry files and actual directories": "it.isFile" in runtime and "it.isDirectory" in runtime and all(f'File(prefix, "{name}")' in runtime for name in ("system.reg", "user.reg", "drive_c", "dosdevices")),
    "UI has an explicit prefix action": 'text = "Wine Prefix তৈরি / যাচাই"' in activity and "initializeWinePrefixManually()" in activity,
    "UI checks wineboot exit code and disk evidence": "process.waitFor()" in activity and "runtimeManager.verifyWinePrefix()" in activity,
    "prefix initialization path does not launch MT5": "runtimeManager.launchMt5" not in activity[activity.index("private fun initializeWinePrefixManually()"):activity.index("private fun installBundledRuntime()")],
    "MT5 remains a separate explicit launch action": "private fun launchWindowsOneTap()" in activity and "runtimeManager.launchMt5" in activity,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f'{"PASS" if passed else "FAIL"}: {name}')
if failed:
    print("Wine prefix initialization regression detected.", file=sys.stderr)
    raise SystemExit(1)
print("Wine prefix initialization regression checks passed.")
