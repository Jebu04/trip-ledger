#!/usr/bin/env bash
# Builds the app and its instrumented tests, installs both on the emulator only, and runs them.
# Usage: tools/run_android_tests.sh [fully.qualified.TestClass[#method]]
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.6.10-hotspot}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="-s emulator-5554"
if ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q 2>&1 | grep -E "^e: |error:|FAILED|What went"; then exit 1; fi
"$ADB" $S install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
"$ADB" $S install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | tail -1
ARGS=""
[ -n "$1" ] && ARGS="-e class $1"
MSYS_NO_PATHCONV=1 "$ADB" $S shell am instrument -w $ARGS app.tripledger.test/androidx.test.runner.AndroidJUnitRunner
