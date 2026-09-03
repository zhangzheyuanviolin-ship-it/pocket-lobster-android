#!/usr/bin/env bash
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$ANDROID_DIR/showerserver"
TARGET_ASSET="$ANDROID_DIR/showerclient/src/main/assets/shower-server.jar"

"$ANDROID_DIR/gradlew" -p "$SERVER_DIR" assembleRelease
OUTPUT_APK="$(find "$SERVER_DIR/build/outputs/apk/release" -maxdepth 1 -type f -name '*-release-unsigned.apk' | head -n 1)"
test -s "$OUTPUT_APK"
cp "$OUTPUT_APK" "$TARGET_ASSET"
test -s "$TARGET_ASSET"
