#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (emulator booted, adb up).
# Captures logcat for the whole run, records the device profile the results
# were measured on, and keeps Gradle's exit code as the step's verdict.
set -u
out=device-evidence
mkdir -p "$out"
{
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  adb shell wm size | tr -d '\r'
  adb shell wm density | tr -d '\r'
} | tee "$out/device.txt"
adb logcat -c || true
adb logcat -v threadtime > "$out/logcat.txt" 2>&1 &
logcat_pid=$!
start=$(date +%s)
./gradlew --no-daemon --stacktrace :jsonuitestrunner:connectedDebugAndroidTest
rc=$?
echo "connectedDebugAndroidTest rc=$rc in $(( $(date +%s) - start ))s" | tee -a "$out/device.txt"
kill "$logcat_pid" 2>/dev/null || true
exit "$rc"
