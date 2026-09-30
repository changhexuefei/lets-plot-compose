#!/usr/bin/env bash
set -euo pipefail

# Resolve the checkout from this file, independently of the action's working directory.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

./consumer-smoke-mpp/gradlew \
  -p consumer-smoke-mpp \
  connectedDebugAndroidTest \
  --no-daemon \
  --stacktrace

evidence_dir="$PWD/consumer-smoke-mpp/build/evidence/android-emulator"
mkdir -p "$evidence_dir"

# The test writes evidence through Instrumentation.targetContext, so the files
# belong to the target APK package (the Android namespace), not testApplicationId.
package_name='org.jetbrains.letsplot.smoke.mpp'
remote_dir='files/android-consumer-smoke'

adb exec-out run-as "$package_name" \
  cat "$remote_dir/01-render.png" > "$evidence_dir/01-render.png"
adb exec-out run-as "$package_name" \
  cat "$remote_dir/02-drag-pan.png" > "$evidence_dir/02-drag-pan.png"
adb exec-out run-as "$package_name" \
  cat "$remote_dir/android-emulator-consumer-smoke.txt" \
  > "$evidence_dir/android-emulator-consumer-smoke.txt"

adb exec-out screencap -p > "$evidence_dir/emulator-final.png"
adb logcat -d > "$evidence_dir/logcat.txt"

test -s "$evidence_dir/01-render.png"
test -s "$evidence_dir/02-drag-pan.png"
test -s "$evidence_dir/android-emulator-consumer-smoke.txt"
