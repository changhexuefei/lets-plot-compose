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

remote_dir='/data/local/tmp/android-consumer-smoke'

adb exec-out cat "$remote_dir/01-render.png" > "$evidence_dir/01-render.png"
adb exec-out cat "$remote_dir/02-drag-pan.png" > "$evidence_dir/02-drag-pan.png"
adb exec-out cat "$remote_dir/android-emulator-consumer-smoke.txt" \
  > "$evidence_dir/android-emulator-consumer-smoke.txt"

adb exec-out screencap -p > "$evidence_dir/emulator-final.png"
adb logcat -d > "$evidence_dir/logcat.txt"

test -s "$evidence_dir/01-render.png"
test -s "$evidence_dir/02-drag-pan.png"
test -s "$evidence_dir/android-emulator-consumer-smoke.txt"
