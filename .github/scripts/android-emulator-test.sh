#!/bin/sh
# Executed as a real file so functions, traps, loops, and heredocs remain in one shell.
set -eu
mkdir -p "$RUNNER_TEMP/android-diagnostics"
report="$RUNNER_TEMP/android-diagnostics/summary.txt"

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
  echo "Source build run: ${SOURCE_BUILD_RUN_ID:-unknown}"
  echo "APK SHA-256: ${APK_SHA256:-unknown}"
  echo "Emulator ABI list: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "Emulator primary ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "Android API: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "Device: $(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "PRoot ELF ABI from APK: ${PROOT_ELF_ABI:-unknown}"
  echo "Embedded runtime payload check: ${RUNTIME_PAYLOAD_STATUS:-unknown}"
} | tee "$report"

# The emulator runner can report transient ADB startup errors while the emulator is
# still booting. Wait for a responsive device before installing or querying UI.
timeout 120s adb wait-for-device
booted=0
for i in $(seq 1 60); do
  if [ "$(timeout 8s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    booted=1
    break
  fi
  sleep 5
done
test "$booted" -eq 1 || { echo "::error::Android emulator did not finish booting"; exit 1; }

adb logcat -c
adb install -r "$APK_PATH" | tee "$RUNNER_TEMP/android-diagnostics/install.txt"
adb shell monkey -p "$PACKAGE_NAME" 1 | tee "$RUNNER_TEMP/android-diagnostics/launch.txt"
sleep 5
adb shell pidof "$PACKAGE_NAME" | tee "$RUNNER_TEMP/android-diagnostics/app-pid.txt" || true
adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$RUNNER_TEMP/android-diagnostics/initial-ui.xml" >/dev/null 2>&1 || true

# Runtime installation starts automatically in MainActivity.onCreate. Prefer observing
# that work before tapping the manual retry button; a UI-only detector missed completed
# staging when the log view was not present in the initial hierarchy.
success_re='Runtime install সম্পন্ন|Embedded runtime checksum already verified|Runtime checksum যাচাই ও private staging সম্পন্ন|Verified runtime private staging complete'
failure_re='Automatic runtime setup ব্যর্থ|not verified|SHA-256 mismatch|XZ archive|Permission denied|error=13|Runtime staging failed'
tap_done=0
for i in $(seq 1 90); do
  timeout 12s adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  timeout 12s adb pull /sdcard/window.xml "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" >/dev/null 2>&1 || true
  timeout 15s adb logcat -d -v threadtime > "$RUNNER_TEMP/android-diagnostics/runtime-logcat-poll.txt" 2>/dev/null || true
  if grep -Eqi "$success_re" "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" "$RUNNER_TEMP/android-diagnostics/runtime-logcat-poll.txt" 2>/dev/null; then
    echo "Runtime staging completion observed from UI or logcat on poll $i." | tee -a "$report"
    break
  fi
  if grep -Eqi "$failure_re" "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" "$RUNNER_TEMP/android-diagnostics/runtime-logcat-poll.txt" 2>/dev/null; then
    echo "Runtime setup failure signature observed on poll $i." | tee -a "$report"
    break
  fi
  # Startup auto-install is the normal path. If the manual button is visible, wait
  # 90 seconds for automatic setup before tapping it as a fallback/retry.
  if [ "$tap_done" -eq 0 ] && [ "$i" -ge 9 ]; then
    python3 - "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" <<'PY' > "$RUNNER_TEMP/android-diagnostics/runtime-button.txt" 2>/dev/null || true
import re,sys,xml.etree.ElementTree as ET
try:
    root=ET.parse(sys.argv[1]).getroot()
    for n in root.iter('node'):
        if 'runtime download / install' in n.attrib.get('text','').lower():
            m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); raise SystemExit(0)
except Exception: pass
raise SystemExit(1)
PY
    if test -s "$RUNNER_TEMP/android-diagnostics/runtime-button.txt"; then
      read -r x y < "$RUNNER_TEMP/android-diagnostics/runtime-button.txt"
      timeout 10s adb shell input tap "$x" "$y" || true
      echo "Tapped Runtime Download / Install fallback at $x,$y after waiting for startup auto-install." | tee -a "$report"
      tap_done=1
    fi
  fi
  sleep 10
done
cp "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" "$RUNNER_TEMP/android-diagnostics/runtime-ui-after-download.xml" 2>/dev/null || true
timeout 20s adb logcat -d -v threadtime > "$RUNNER_TEMP/android-diagnostics/runtime-logcat-final.txt" 2>/dev/null || true
if ! grep -Eqi "$success_re" "$RUNNER_TEMP/android-diagnostics/runtime-ui.xml" "$RUNNER_TEMP/android-diagnostics/runtime-logcat-poll.txt" "$RUNNER_TEMP/android-diagnostics/runtime-logcat-final.txt" 2>/dev/null; then
  echo "::error::Runtime staging completion was not observed in UI hierarchy or logcat after automatic install/retry window."
  exit 1
fi
# Do NOT tap the Windows launch button: it can launch MT5 if an existing prefix is found.
# MainActivity performs a guarded startup preflight itself; collect its UI/logcat evidence only.
echo "Windows launch button intentionally NOT tapped; MT5 must not auto-launch during CI diagnostics." | tee -a "$report"
sleep 25

adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml "$RUNNER_TEMP/android-diagnostics/final-ui.xml" >/dev/null 2>&1 || true
adb logcat -d -v threadtime > "$RUNNER_TEMP/android-diagnostics/logcat.txt"
app_pid="$(adb shell pidof "$PACKAGE_NAME" | tr -d '\r' | awk '{print $1}')"
if test -n "$app_pid"; then
  adb logcat -d -v threadtime --pid="$app_pid" > "$RUNNER_TEMP/android-diagnostics/logcat-app.txt"
else
  : > "$RUNNER_TEMP/android-diagnostics/logcat-app.txt"
fi
adb shell dumpsys activity activities > "$RUNNER_TEMP/android-diagnostics/activities.txt" || true
adb shell dumpsys package "$PACKAGE_NAME" > "$RUNNER_TEMP/android-diagnostics/package.txt" || true

permission_error=0
if grep -Eqi 'Permission denied|error=13' "$RUNNER_TEMP/android-diagnostics/"*.xml "$RUNNER_TEMP/android-diagnostics/logcat-app.txt" || grep -Eq '(^|[^[:alnum:]_])EACCES([^[:alnum:]_]|$)' "$RUNNER_TEMP/android-diagnostics/logcat-app.txt"; then
  echo "PRoot/permission error signature: DETECTED in app UI or app-process logcat" | tee -a "$report"
  permission_error=1
else
  echo "PRoot/permission error signature: NOT DETECTED in app UI/app-process logcat (runtime probe evidence is reported separately)" | tee -a "$report"
fi
if grep -Eqi 'Exec format error|wrong ELF class|cannot execute binary file' "$RUNNER_TEMP/android-diagnostics/"*.xml "$RUNNER_TEMP/android-diagnostics/logcat-app.txt"; then
  echo "Architecture mismatch / unsupported ELF execution signature: DETECTED" | tee -a "$report"
  echo "::warning::Native executable may not match emulator architecture or ABI translation capabilities."
fi
if grep -Eqi 'FATAL EXCEPTION|Process: com\.ras\.mt5runner' "$RUNNER_TEMP/android-diagnostics/logcat.txt"; then
  echo "Application crash: DETECTED" | tee -a "$report"
  echo "::error::Android app crash detected; see diagnostics artifact."
  exit 1
fi

# Surface the known Wine rootfs library failures and manifest state in a stable summary.
if grep -Eqi 'libfontconfig\.so\.1|libfreetype\.so\.6|libbrotlidec\.so\.1|Wine rootfs dependency missing' "$RUNNER_TEMP/android-diagnostics/"*.xml "$RUNNER_TEMP/android-diagnostics/logcat-app.txt"; then
  echo "Wine dependency diagnostic: one or more observed runtime libraries are missing; inspect app logcat/UI." | tee -a "$report"
else
  echo "Wine dependency diagnostic: no known missing-library signature observed in captured app UI/logcat (not proof that all dependencies resolve)." | tee -a "$report"
fi
if grep -Eqi 'manifest status=pending-runtime-build|not verified|pending-runtime-build' "$RUNNER_TEMP/android-diagnostics/"*.xml "$RUNNER_TEMP/android-diagnostics/logcat-app.txt"; then
  echo "Runtime manifest status: pending/unverified; auto-download must refuse this manifest." | tee -a "$report"
fi
if grep -Eqi 'private staging|runtime/bundle|bundle\.installing|Runtime staging failed' "$RUNNER_TEMP/android-diagnostics/"*.xml "$RUNNER_TEMP/android-diagnostics/logcat-app.txt"; then
  echo "Private staging diagnostics: evidence found; inspect full logs for completed/failed staging state." | tee -a "$report"
else
  echo "Private staging diagnostics: no staging path signature captured." | tee -a "$report"
fi
if grep -qi 'AArch64' "$RUNNER_TEMP/proot-elf.txt" 2>/dev/null && ! printf '%s\n' "$(adb shell getprop ro.product.cpu.abilist)" | grep -qi 'arm64-v8a'; then
  echo "ABI note: PRoot ELF is AArch64, while emulator ABI list omits arm64-v8a; actual PRoot execution evidence is required." | tee -a "$report"
  echo "::warning::ARM64 PRoot execution depends on API 35 x86_64 image translation support; see actual preflight output and logs."
fi
echo "Android install/launch and diagnostics collection completed." | tee -a "$report"
if [ "$permission_error" -eq 1 ]; then
  echo "::error::PRoot permission error signature detected in app UI/logcat."
  exit 1
fi
