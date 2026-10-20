#!/usr/bin/env python3
"""Regression guard: the Windows launch action must not open Android's file picker."""
from pathlib import Path
import sys

source_path = Path("app/src/main/java/com/ras/mt5runner/MainActivity.kt")
source = source_path.read_text(encoding="utf-8")
start = source.index("private fun launchWindowsOneTap()")
end = source.index("private fun installBundledRuntime()", start)
launch = source[start:end]

checks = {
    "launch action must not invoke installer picker": "chooseInstaller()" not in launch,
    "launch action still launches installed MT5": "runtimeManager.launchMt5" in launch,
    "missing MT5 branch gives an explanatory message": "MT5 ইনস্টল করা নেই" in launch,
    "installer picker has its own explicit button": 'text = "MT5 Installer নির্বাচন করুন"' in source,
    "explicit installer button invokes chooser": 'addLog("ব্যবহারকারী আলাদাভাবে MT5 installer নির্বাচন চেয়েছেন।")' in source and "chooseInstaller()" in source,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f'{"PASS" if passed else "FAIL"}: {name}')
if failed:
    print("Windows launch action regression detected.", file=sys.stderr)
    raise SystemExit(1)
print("Windows launch action regression checks passed.")
