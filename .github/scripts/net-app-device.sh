#!/usr/bin/env bash
set -euo pipefail

output=net-app-device
mkdir -p "$output"
sudo chown "$(id -u):$(id -g)" /dev/kvm
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK is missing}}"
sdkmanager_path="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager_path" ]]; then
  sdkmanager_path="$(find "$sdk_root/cmdline-tools" -type f -name sdkmanager | sort -V | tail -n 1)"
fi
test -x "$sdkmanager_path"
export PATH="$(dirname "$sdkmanager_path"):$sdk_root/platform-tools:$sdk_root/emulator:$PATH"
emulator -version
sdkmanager --install 'system-images;android-33;google_apis;x86_64'
echo no | avdmanager create avd --name usuals-evidence --package 'system-images;android-33;google_apis;x86_64'
emulator -avd usuals-evidence -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$output/emulator.log" 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT

timeout 180 adb wait-for-device
timeout 180 bash -c 'until [[ "$(adb shell getprop sys.boot_completed | tr -d "\r")" == 1 ]]; do sleep 2; done'
adb shell input keyevent 82
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$setting" 0
done
adb install -t app/build/outputs/apk/debug/app-debug.apk
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

failed=0
run_flow() {
  local class="$1" method="$2" flag="$3"
  local log="$output/$class.log"
  if ! timeout 120 adb shell am instrument -w -r \
      -e class "io.theficos.usuals.$class#$method" -e "$flag" synthetic \
      io.theficos.usuals.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"; then
    failed=1
    return
  fi
  if ! grep -qx 'INSTRUMENTATION_CODE: -1' "$log" ||
      [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$log")" != 1 ]] ||
      ! grep -qx 'OK (1 test)' "$log" ||
      grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[0-9]+$' "$log"; then
    failed=1
  fi
}
run_flow HistoryDeletionDeviceTest confirmDeletePreservesTheCurrentListAndUpdatesHistory historyDeleteQa
run_flow ShoppingFinishDeviceTest footerPreservesConcurrentEditsAndFilteredRowsWithUndo shoppingFinishQa
exit "$failed"
