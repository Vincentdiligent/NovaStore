#!/usr/bin/env bash
# Verify the APK exists, is non-empty and belongs to com.novastore.app.
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:-releases/NovaStore-debug.apk}"
[ -f "$APK" ] || { echo "FAIL: $APK not found"; exit 1; }
SIZE=$(stat -c%s "$APK")
[ "$SIZE" -gt 1000000 ] || { echo "FAIL: $APK too small ($SIZE bytes)"; exit 1; }
AAPT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/build-tools/35.0.0/aapt2"
if [ -x "$AAPT" ]; then
  BADGE=$("$AAPT" dump badging "$APK" 2>/dev/null | head -1)
  echo "$BADGE" | grep -q "package: name='com.novastore.app'" \
    || { echo "FAIL: wrong package id: $BADGE"; exit 1; }
  echo "OK: $BADGE"
fi
echo "OK: $APK — $SIZE bytes, package com.novastore.app"
