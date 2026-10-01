#!/usr/bin/env bash
# Build the debug APK and copy it to releases/.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew assembleDebug
mkdir -p releases
cp app/build/outputs/apk/debug/app-debug.apk releases/NovaStore-debug.apk
echo "APK: releases/NovaStore-debug.apk ($(stat -c%s releases/NovaStore-debug.apk) bytes)"
