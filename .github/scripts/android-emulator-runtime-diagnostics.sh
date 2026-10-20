#!/bin/sh
# reactivecircus/android-emulator-runner executes this script with /usr/bin/sh.
# Keep the script POSIX-shell compatible (no pipefail, [[ ]], or here-strings).
set -eu
mkdir -p "$RUNNER_TEMP/android-diagnostics"
report="$RUNNER_TEMP/android-diagnostics/summary.txt"
# EXIT trap captures evidence for both ordinary failures and explicit exits; all adb calls are bounded.
capture_on_exit() {
  rc=$?
  trap - 0
  {
    echo "Emulator script exit code: $rc"
    echo "Failure diagnostics capture triggered."
    echo "Captured at UTC: $(date -u +%FT%TZ)"
  } >> "$report" 2>&1 || true
  timeout 15s adb devices -l > "$RUNNER_TEMP/android-diagnostics/adb-devices-on-exit.txt" 2>&1 || true
  timeout 20s adb logcat -d -v threadtime > "$RUNNER_TEMP/android-diagnostics/logcat-on-exit.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity activities > "$RUNNER_TEMP/android-diagnostics/activities-on-exit.txt" 2>&1 || true
  timeout 15s adb shell dumpsys package "$PACKAGE_NAME" > "$RUNNER_TEMP/android-diagnostics/package-on-exit.txt" 2>&1 || true
  timeout 15s adb shell uiautomator dump /sdcard/error-window.xml > "$RUNNER_TEMP/android-diagnostics/ui-dump-command-on-exit.txt" 2>&1 || true
  timeout 15s adb pull /sdcard/error-window.xml "$RUNNER_TEMP/android-diagnostics/ui-on-exit.xml" >> "$RUNNER_TEMP/android-diagnostics/ui-dump-command-on-exit.txt" 2>&1 || true
  exit "$rc"
}
trap capture_on_exit 0
{
  echo "Source build run: $SOURCE_BUILD_RUN_ID"
  echo "APK SHA-256: $APK_SHA256"
  echo "Emulator ABI list: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "Emulator primary ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "Android API: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "Device: $(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "PRoot ELF ABI from APK: $PROOT_ABI"
  echo "Embedded runtime payload check: $RUNTIME_PAYLOAD"
} | tee "$report"

adb logcat -c
adb install -r "$APK_PATH" | tee "$RUNNER_TEMP/android-diagnostics/install.txt"
adb shell monkey -p "$PACKAGE_NAME" 1 | tee "$RUNNER_TEMP/android-diagnostics/launch.txt"
sleep 8
adb shell pidof "$PACKAGE_NAME" | tee "$RUNNER_TEMP/android-diagnostics/app-pid.txt" || true
adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$RUNNER_TEMP/android-diagnostics/initial-ui.xml" >/dev/null 2>&1 || true

# Run the in-app runtime download/install action through the real Android UI.
python3 - "$RUNNER_TEMP/android-diagnostics/initial-ui.xml" <<'PY' > "$RUNNER_TEMP/android-diagnostics/runtime-button.txt" || true
import re,sys,xml.etree.ElementTree as ET
try:
    root=ET.parse(sys.argv[1]).getroot()
    for n in root.iter('node'):
        t=n.attrib.get('text','')
        if 'Runtime Download / Install' in t:
            m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); raise SystemExit(0)
except Exception: pass
raise SystemExit(1)
PY
if test -s "$RUNNER_TEMP/android-diagnostics/runtime-button.txt"; then
  read -r x y < "$RUNNER_TEMP/android-diagnostics/runtime-button.txt"
  adb shell input tap "$x" "$y"
  echo "Tapped Runtime Download / Install at $x,$y" | tee -a "$report"
  # Allow network, manifest validation, download, checksum and install to report.
  for i in $(seq 1 36); do
    sleep 10
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
    adb pull /sdcard/window.xml "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" >/dev/null 2>&1 || true
    if grep -Eqi 'Runtime install সম্পন্ন|Automatic runtime setup ব্যর্থ|not verified|SHA-256 mismatch|XZ archive|Permission denied|error=13' "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" 2>/dev/null; then
      break
    fi
  done
  cp "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" "$RUNNER_TEMP/android-diagnostics/runtime-ui-after-download.xml" 2>/dev/null || true
else
  echo "::warning::Could not locate runtime button in UI hierarchy; app launch will still be recorded."
fi

# Trigger the app's own runtime preflight/PRoot execution path.
adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$RUNNER_TEMP/android-diagnostics/preflight-ui.xml" >/dev/null 2>&1 || true
python3 - "$RUNNER_TEMP/android-diagnostics/preflight-ui.xml" <<'PY' > "$RUNNER_TEMP/android-diagnostics/launch-button.txt" || true
import re,sys,xml.etree.ElementTree as ET
try:
    root=ET.parse(sys.argv[1]).getroot()
    for n in root.iter('node'):
        t=n.attrib.get('text','')
        if 'Windows চালু করুন' in t:
            m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); raise SystemExit(0)
except Exception: pass
raise SystemExit(1)
PY
if test -s "$RUNNER_TEMP/android-diagnostics/launch-button.txt"; then
  read -r x y < "$RUNNER_TEMP/android-diagnostics/launch-button.txt"
  adb shell input tap "$x" "$y"
  echo "Tapped Windows launch / runtime preflight at $x,$y" | tee -a "$report"
  sleep 25
fi

adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$RUNNER_TEMP/android-diagnostics/final-ui.xml" >/dev/null 2>&1 || true
adb logcat -d -v threadtime > "$RUNNER_TEMP/android-diagnostics/logcat.txt"
adb shell dumpsys activity activities > "$RUNNER_TEMP/android-diagnostics/activities.txt" || true
adb shell dumpsys package "$PACKAGE_NAME" > "$RUNNER_TEMP/android-diagnostics/package.txt" || true

permission_error=0
if grep -Eqi 'Permission denied|error=13|EACCES' "$RUNNER_TEMP/android-diagnostics/"*.xml "$RUNNER_TEMP/android-diagnostics/logcat.txt"; then
  echo "PRoot/permission error signature: DETECTED" | tee -a "$report"
  permission_error=1
else
  echo "PRoot/permission error signature: NOT DETECTED in captured UI/logcat (not proof of successful PRoot execution)" | tee -a "$report"
fi
if grep -Eqi 'Exec format error|wrong ELF class|cannot execute binary file' "$RUNNER_TEMP/android-diagnostics/"*.xml "$RUNNER_TEMP/android-diagnostics/logcat.txt"; then
  echo "Architecture mismatch / unsupported ELF execution signature: DETECTED" | tee -a "$report"
  echo "::warning::Native executable may not match emulator architecture or ABI translation capabilities."
fi
if grep -Eqi 'FATAL EXCEPTION|Process: com\.ras\.mt5runner' "$RUNNER_TEMP/android-diagnostics/logcat.txt"; then
  echo "Application crash: DETECTED" | tee -a "$report"
  echo "::error::Android app crash detected; see diagnostics artifact."
  exit 1
fi
if grep -qi 'AArch64' "$RUNNER_TEMP/proot-elf.txt" 2>/dev/null && ! printf '%s\n' "$(adb shell getprop ro.product.cpu.abilist)" | grep -qi 'arm64-v8a'; then
  echo "ABI note: PRoot ELF is AArch64, while emulator ABI list omits arm64-v8a; this is not by itself proof of failure because API/system-image translation may exist. Actual PRoot execution evidence is required." | tee -a "$report"
  echo "::warning::ARM64 PRoot execution depends on the API 35 x86_64 image's translation support; see actual preflight output and logs."
fi
echo "Android install/launch and diagnostics collection completed." | tee -a "$report"
if [ "$permission_error" -eq 1 ]; then
  echo "::error::PRoot permission error signature detected in app UI/logcat."
  exit 1
fi
