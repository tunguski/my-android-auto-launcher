#!/usr/bin/env bash
#
# Zero-Gradle build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Produces the same APK as "./gradlew assembleRelease", without a Gradle daemon,
# which is handy on a slow machine or inside a minimal container.
#
# Requirements:
#   ANDROID_HOME          Android SDK with the pinned platform and build-tools
#   JAVA_HOME (optional)  JDK 17 or 21; otherwise javac from PATH is used
#
# Signing (optional; without it the APK is only debug-signed with a throwaway key):
#   NAVI_KEYSTORE, NAVI_KEYSTORE_PASSWORD, NAVI_KEY_ALIAS, NAVI_KEY_PASSWORD
#
set -euo pipefail

PACKAGE=pl.matsuo.navi
PLATFORM=android-35
BUILD_TOOLS=35.0.0
MIN_SDK=26
TARGET_SDK=34
VERSION_CODE=13
VERSION_NAME=2.2

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    echo "ANDROID_HOME is not set." >&2
    exit 1
fi

BT="$SDK/build-tools/$BUILD_TOOLS"
JAR="$SDK/platforms/$PLATFORM/android.jar"

# On Windows the build tools are .exe or .bat; a POSIX shell resolves neither on
# its own, so every tool is looked up explicitly.
tool() {
    for candidate in "$BT/$1" "$BT/$1.exe" "$BT/$1.bat"; do
        if [ -f "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    echo "Missing $1 - install build-tools;$BUILD_TOOLS" >&2
    exit 1
}

AAPT2=$(tool aapt2)
D8=$(tool d8)
ZIPALIGN=$(tool zipalign)
APKSIGNER=$(tool apksigner)
if [ ! -f "$JAR" ]; then
    echo "Missing $JAR - install platforms;$PLATFORM" >&2
    exit 1
fi

JAVAC="javac"
KEYTOOL="keytool"
JAR_TOOL="jar"
if [ -n "${JAVA_HOME:-}" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    KEYTOOL="$JAVA_HOME/bin/keytool"
    JAR_TOOL="$JAVA_HOME/bin/jar"
fi

SRC=app/src/main
OUT=out
# The file name carries the version, so downloaded APKs stay distinguishable.
APK_OUT="$OUT/navi-$VERSION_NAME.apk"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

# The manifest carries no package attribute, because Gradle injects it from the
# namespace. aapt2 needs one, so put it in a copy.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

# Resources: compile, then link against the platform and generate R.java.
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT2" link \
    -I "$JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    -o "$OUT/base.apk" "$OUT/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME"

# Java sources plus the generated R class.
"$JAVAC" -source 11 -target 11 -nowarn -encoding UTF-8 \
    -classpath "$JAR" -d "$OUT/classes" \
    $(find "$SRC/java" "$OUT/gen" -name '*.java')

"$D8" --min-api "$MIN_SDK" --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
# jar, not zip: every JDK ships jar, while zip is missing on plain Windows shells.
"$JAR_TOOL" --update --file "$OUT/unsigned.apk" -C "$OUT/dex" classes.dex
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ -n "${NAVI_KEYSTORE:-}" ]; then
    "$APKSIGNER" sign \
        --ks "$NAVI_KEYSTORE" \
        --ks-pass "pass:${NAVI_KEYSTORE_PASSWORD:?NAVI_KEYSTORE_PASSWORD is required}" \
        --ks-key-alias "${NAVI_KEY_ALIAS:?NAVI_KEY_ALIAS is required}" \
        --key-pass "pass:${NAVI_KEY_PASSWORD:-$NAVI_KEYSTORE_PASSWORD}" \
        --out "$APK_OUT" "$OUT/aligned.apk"
else
    # No release key configured: sign with a local throwaway key so the APK can
    # be installed for testing. Never use this build for a release.
    echo "NAVI_KEYSTORE is not set - signing with a local debug key (out/debug.jks)."
    if [ ! -f "$OUT/debug.jks" ]; then
        "$KEYTOOL" -genkeypair -keystore "$OUT/debug.jks" -alias navi \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass android -keypass android -dname "CN=Navi debug" >/dev/null 2>&1
    fi
    "$APKSIGNER" sign --ks "$OUT/debug.jks" --ks-pass pass:android \
        --out "$APK_OUT" "$OUT/aligned.apk"
fi

"$APKSIGNER" verify "$APK_OUT"
ls -la "$APK_OUT"

# The user guide is part of the deliverable, exactly as in the Gradle build.
if [ -f tools/MdPdf.java ]; then
    JAVA_BIN="java"
    if [ -n "${JAVA_HOME:-}" ]; then JAVA_BIN="$JAVA_HOME/bin/java"; fi
    "$JAVA_BIN" -Djava.awt.headless=true tools/MdPdf.java \
        docs/USER_GUIDE.md docs/navi-user-guide.pdf
    "$JAVA_BIN" tools/MdHtml.java docs/USER_GUIDE.md docs/navi-user-guide.html
fi
