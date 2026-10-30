#!/usr/bin/env bash
#
# Refreshes every preview in docs/previews from a real device or emulator.
#
# It installs the debug APK and the test APK, runs the ScreenshotTest class,
# which walks all panels, all four size variants and all warning states, and
# copies the resulting PNGs over the stored previews. Nothing is drawn by hand,
# so the pictures in the user guide cannot drift away from the build.
#
# Usage:
#   tools/screenshots.sh            # uses the only attached device
#   ANDROID_SERIAL=emulator-5554 tools/screenshots.sh
#
# Requirements: ANDROID_HOME, a JDK 21 in JAVA_HOME, and a 1280x720 landscape
# device (the head unit resolution; tools/avd.sh creates a matching emulator).
#
set -euo pipefail

cd "$(dirname "$0")/.."
OUT=docs/previews
PKG=pl.matsuo.navi
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    echo "ANDROID_HOME is not set." >&2
    exit 1
fi
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$ADB.exe"

# Git Bash would rewrite every /data/... argument into a Windows path.
export MSYS2_ARG_CONV_EXCL="*"

echo "== building the APKs"
./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest

APK=$(ls app/build/outputs/apk/debug/*-debug.apk | head -1)
TEST_APK=$(ls app/build/outputs/apk/androidTest/debug/*-androidTest.apk | head -1)

echo "== installing $(basename "$APK")"
"$ADB" install -r -g "$APK" >/dev/null
"$ADB" install -r -g "$TEST_APK" >/dev/null

# A previous run must not leave pictures behind that this one does not produce.
"$ADB" shell "run-as $PKG rm -rf files/shots" || true

echo "== taking the screenshots"
"$ADB" shell "am instrument -w -r -e class pl.matsuo.navi.ScreenshotTest $RUNNER" \
    | grep -E "INSTRUMENTATION_STATUS: (test|stream)=|OK \(|FAILURES" || true

echo "== copying into $OUT"
mkdir -p "$OUT"
rm -f "$OUT"/*.png
COUNT=0
for name in $("$ADB" shell "run-as $PKG ls files/shots" | tr -d '\r'); do
    case "$name" in
        *.png) ;;
        *) continue ;;
    esac
    "$ADB" exec-out "run-as $PKG cat files/shots/$name" > "$OUT/$name"
    COUNT=$((COUNT + 1))
    echo "   $OUT/$name"
done

if [ "$COUNT" -eq 0 ]; then
    echo "No screenshots were produced - see the instrumentation output above." >&2
    exit 1
fi
echo "== $COUNT previews refreshed"
