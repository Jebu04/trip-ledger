#!/usr/bin/env bash
# Build the debug APK and install it on the emulator only (never on a plugged-in phone).
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.6.10-hotspot}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
./gradlew.bat :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e: |error:|FAILED|What went" && exit 1
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
