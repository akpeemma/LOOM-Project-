#!/bin/bash
# LOOM manual APK build — used while the Gradle daemon is broken on this VM
# (daemon starts but the client cannot talk to it; aapt2 -R also SIGABRTs here).
# Single resource table, one aapt2 link; the generated R class is duplicated
# into the designsystem package so module sources stay pristine.
set -e
source ~/.loom_env
export PATH="$HOME/workspace/brahma/loom/toolchain/jdk-17/bin:$PATH"

LOOM=~/workspace/brahma/loom
PROJ=$LOOM/build/loom-android
SDK="$ANDROID_HOME"
BT=$SDK/build-tools/35.0.0
ANDROID_JAR=$SDK/platforms/android-35/android.jar
KOTLINC=$LOOM/toolchain/kotlin/kotlinc/bin/kotlinc
STDLIB=$LOOM/toolchain/kotlin/kotlinc/lib/kotlin-stdlib.jar
WORK=$PROJ/build-manual
DS=$PROJ/presentation/designsystem/src/main
APP=$PROJ/app/src/main

command -v "$KOTLINC" >/dev/null || { echo "FATAL: kotlinc not found at $KOTLINC"; exit 1; }

rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/gen" "$WORK/classes" "$WORK/dex" "$WORK/res" "$WORK/r-ds"

echo "== [1/6] merge resources (designsystem + app)"
cp -r "$DS"/res/* "$WORK/res/"
cp -r "$APP"/res/* "$WORK/res/" 2>/dev/null || true

echo "== [2/6] aapt2 compile + link (single table)"
"$BT/aapt2" compile --dir "$WORK/res" -o "$WORK/compiled.zip"
# app manifest has no package attr (Gradle supplies namespace); add it for aapt2.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.loom.assistant">|' \
  "$APP/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"
"$BT/aapt2" link -o "$WORK/loom-unsigned.apk" \
  --manifest "$WORK/AndroidManifest.xml" \
  --java "$WORK/gen" \
  -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code 7 --version-name "0.7.0-aoie" \
  "$WORK/compiled.zip"
# Duplicate the R class into the designsystem package (same constants).
R_JAVA=$(find "$WORK/gen" -name R.java | head -1)
mkdir -p "$WORK/r-ds/com/loom/assistant/presentation/designsystem"
sed 's/^package com\.loom\.assistant;/package com.loom.assistant.presentation.designsystem;/' \
  "$R_JAVA" > "$WORK/r-ds/com/loom/assistant/presentation/designsystem/R.java"
grep -m1 "^package" "$WORK/r-ds/com/loom/assistant/presentation/designsystem/R.java"

echo "== [3/6] kotlinc: designsystem + app + phase3/phase4/phase5 modules + generated R"
find "$DS/java" "$APP/java" "$WORK/gen" "$WORK/r-ds" \
  "$PROJ/core/common/src/main/java" \
  "$PROJ/presentation/home/src/main/java" \
  "$PROJ/presentation/chat/src/main/java" \
  "$PROJ/presentation/modes/src/main/java" \
  "$PROJ/presentation/dashboard/src/main/java" \
  "$PROJ/presentation/settings/src/main/java" \
  "$PROJ/presentation/panels/src/main/java" \
  "$PROJ/interaction/voice/src/main/java" \
  "$PROJ/interaction/edgelight/src/main/java" \
  "$PROJ/interaction/pointer/src/main/java" \
  "$PROJ/interaction/notifications/src/main/java" \
  "$PROJ/intelligence/llm/src/main/java" \
  "$PROJ/intelligence/memory/src/main/java" \
  "$PROJ/intelligence/skilllab/src/main/java" \
  "$PROJ/intelligence/authgate/src/main/java" \
  "$PROJ/intelligence/planner/src/main/java" \
  "$PROJ/execution/screenawareness/src/main/java" \
  "$PROJ/execution/search/src/main/java" \
  "$PROJ/execution/messaging/src/main/java" \
  "$PROJ/execution/tasks/src/main/java" \
  "$PROJ/execution/devicecontrol/src/main/java" \
  "$PROJ/execution/interaction/src/main/java" \
  "$PROJ/platform/services/src/main/java" \
  "$PROJ/platform/storage/src/main/java" \
  \( -name '*.kt' -o -name '*.java' \) > "$WORK/sources.txt"
wc -l < "$WORK/sources.txt"
"$KOTLINC" -cp "$ANDROID_JAR" -d "$WORK/classes" -jvm-target 17 \
  -no-reflect @"$WORK/sources.txt"

echo "== [4/6] d8: classes -> dex"
(cd "$WORK/classes" && zip -q -r "$WORK/classes.jar" .)
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$WORK/dex" \
  "$WORK/classes.jar" "$STDLIB"
(cd "$WORK/dex" && zip -q -X "$WORK/loom-unsigned.apk" classes.dex)

echo "== [5/6] zipalign + apksigner"
if [ ! -f "$LOOM/toolchain/debug.keystore" ]; then
  keytool -genkeypair -keystore "$LOOM/toolchain/debug.keystore" \
    -alias loomdebug -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=LOOM Debug, OU=LOOM, O=LOOM, C=NG" -noprompt
fi
"$BT/zipalign" -f 4 "$WORK/loom-unsigned.apk" "$WORK/loom-debug.apk"
"$BT/apksigner" sign --ks "$LOOM/toolchain/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$PROJ/loom-aoie-debug.apk" "$WORK/loom-debug.apk"
"$BT/apksigner" verify --print-certs "$PROJ/loom-aoie-debug.apk" | head -3

echo "== [6/6] badging =="
"$BT/aapt2" dump badging "$PROJ/loom-aoie-debug.apk" | head -6
ls -la "$PROJ/loom-aoie-debug.apk"
echo "BUILD OK"
