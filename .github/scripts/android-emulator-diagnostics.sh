#!/usr/bin/env bash
set -Eeuo pipefail

DIAG_DIR="$RUNNER_TEMP/android-diagnostics"
mkdir -p "$DIAG_DIR"
report="$DIAG_DIR/summary.txt"

capture_on_exit() {
  rc=$?
  trap - EXIT
  {
    echo "Emulator script exit code: $rc"
    echo "Failure diagnostics capture triggered."
    echo "Captured at UTC: $(date -u +%FT%TZ)"
  } >> "$report" 2>&1 || true
  timeout 15s adb devices -l > "$DIAG_DIR/adb-devices-on-exit.txt" 2>&1 || true
  timeout 20s adb logcat -d -v threadtime > "$DIAG_DIR/logcat-on-exit.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity activities > "$DIAG_DIR/activities-on-exit.txt" 2>&1 || true
  timeout 15s adb shell dumpsys package "$PACKAGE_NAME" > "$DIAG_DIR/package-on-exit.txt" 2>&1 || true
  timeout 15s adb shell uiautomator dump /sdcard/error-window.xml > "$DIAG_DIR/ui-dump-command-on-exit.txt" 2>&1 || true
  timeout 15s adb pull /sdcard/error-window.xml "$DIAG_DIR/ui-on-exit.xml" >> "$DIAG_DIR/ui-dump-command-on-exit.txt" 2>&1 || true
  exit "$rc"
}
trap capture_on_exit EXIT

{
  echo "Source build run: ${SOURCE_BUILD_RUN_ID:-unknown}"
  echo "APK SHA-256: ${APK_SHA256:-unknown}"
  echo "Emulator ABI list: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "Emulator primary ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "Android API: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "Device: $(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "PRoot ELF ABI from APK: ${PROOT_ABI:-unknown}"
  echo "Embedded runtime payload check: ${RUNTIME_PAYLOAD:-unknown}"
} | tee "$report"

adb logcat -c
adb install -r "$APK_PATH" | tee "$DIAG_DIR/install.txt"
adb shell monkey -p "$PACKAGE_NAME" 1 | tee "$DIAG_DIR/launch.txt"
sleep 8
adb shell pidof "$PACKAGE_NAME" | tee "$DIAG_DIR/app-pid.txt" || true
adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$DIAG_DIR/initial-ui.xml" >/dev/null 2>&1 || true

python3 - "$DIAG_DIR/initial-ui.xml" > "$DIAG_DIR/runtime-button.txt" <<'PY' || true
import re,sys,xml.etree.ElementTree as ET
try:
    root=ET.parse(sys.argv[1]).getroot()
    for n in root.iter('node'):
        if 'Runtime Download / Install' in n.attrib.get('text',''):
            m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); raise SystemExit(0)
except Exception: pass
raise SystemExit(1)
PY
if [[ -s "$DIAG_DIR/runtime-button.txt" ]]; then
  read -r x y < "$DIAG_DIR/runtime-button.txt"
  adb shell input tap "$x" "$y"
  echo "Tapped Runtime Download / Install at $x,$y" | tee -a "$report"
  for i in $(seq 1 36); do
    sleep 10
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
    adb pull /sdcard/window.xml "$DIAG_DIR/runtime-ui.xml" >/dev/null 2>&1 || true
    if grep -Eqi 'Runtime install সম্পন্ন|Automatic runtime setup ব্যর্থ|not verified|SHA-256 mismatch|XZ archive|Permission denied|error=13' "$DIAG_DIR/runtime-ui.xml" 2>/dev/null; then break; fi
  done
  cp "$DIAG_DIR/runtime-ui.xml" "$DIAG_DIR/runtime-ui-after-download.xml" 2>/dev/null || true
else
  echo "::warning::Could not locate runtime button in UI hierarchy; app launch will still be recorded." | tee -a "$report"
fi

adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$DIAG_DIR/preflight-ui.xml" >/dev/null 2>&1 || true
python3 - "$DIAG_DIR/preflight-ui.xml" > "$DIAG_DIR/launch-button.txt" <<'PY' || true
import re,sys,xml.etree.ElementTree as ET
try:
    root=ET.parse(sys.argv[1]).getroot()
    for n in root.iter('node'):
        if 'Windows চালু করুন' in n.attrib.get('text',''):
            m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); raise SystemExit(0)
except Exception: pass
raise SystemExit(1)
PY
if [[ -s "$DIAG_DIR/launch-button.txt" ]]; then
  read -r x y < "$DIAG_DIR/launch-button.txt"
  adb shell input tap "$x" "$y"
  echo "Tapped Windows launch / runtime preflight at $x,$y" | tee -a "$report"
  sleep 25
fi

adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$DIAG_DIR/final-ui.xml" >/dev/null 2>&1 || true
adb logcat -d -v threadtime > "$DIAG_DIR/logcat.txt"
adb shell dumpsys activity activities > "$DIAG_DIR/activities.txt" || true
adb shell dumpsys package "$PACKAGE_NAME" > "$DIAG_DIR/package.txt" || true

permission_error=0
if grep -Eqi 'Permission denied|error=13|EACCES' "$DIAG_DIR/"*.xml "$DIAG_DIR/logcat.txt"; then
  echo "PRoot/permission error signature: DETECTED" | tee -a "$report"
  permission_error=1
else
  echo "PRoot/permission error signature: NOT DETECTED in captured UI/logcat (not proof of successful PRoot execution)" | tee -a "$report"
fi
if grep -Eqi 'Exec format error|wrong ELF class|cannot execute binary file' "$DIAG_DIR/"*.xml "$DIAG_DIR/logcat.txt"; then
  echo "Architecture mismatch / unsupported ELF execution signature: DETECTED" | tee -a "$report"
  echo "::warning::Native executable may not match emulator architecture or ABI translation capabilities."
fi
if grep -Eqi 'FATAL EXCEPTION|Process: com\.ras\.mt5runner' "$DIAG_DIR/logcat.txt"; then
  echo "Application crash: DETECTED" | tee -a "$report"
  echo "::error::Android app crash detected; see diagnostics artifact."
  exit 1
fi
if grep -qi 'AArch64' "$RUNNER_TEMP/proot-elf.txt" 2>/dev/null && ! adb shell getprop ro.product.cpu.abilist | tr -d '\r' | grep -qi 'arm64-v8a'; then
  echo "ABI note: PRoot ELF is AArch64, while emulator ABI list omits arm64-v8a; actual PRoot execution evidence is required." | tee -a "$report"
  echo "::warning::ARM64 PRoot execution depends on API 35 x86_64 image translation support."
fi
echo "Android install/launch and diagnostics collection completed." | tee -a "$report"
if [[ "$permission_error" -eq 1 ]]; then
  echo "::error::PRoot permission error signature detected in app UI/logcat."
  exit 1
fi
