#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
TOOLS="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-37.0/android.jar"
KEYSTORE=${ANDROID_DEBUG_KEYSTORE:-"$HOME/.android/debug.keystore"}
OUT="$ROOT/dist/liubai-journal-1.8-debug.apk"

for tool in "$JAVA_HOME/bin/javac" "$JAVA_HOME/bin/jar" "$JAVA_HOME/bin/keytool" \
  "$TOOLS/aapt" "$TOOLS/d8" "$TOOLS/zipalign" "$TOOLS/apksigner" "$ANDROID_JAR"; do
  if [ ! -f "$tool" ]; then echo "Missing build tool: $tool" >&2; exit 1; fi
done

export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname -- "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/liubai-apk.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex" "$ROOT/dist"

"$TOOLS/aapt" package -f -M "$ROOT/android/AndroidManifest.xml" \
  -S "$ROOT/android/res" -I "$ANDROID_JAR" -F "$WORK/unsigned.apk"

"$JAVA_HOME/bin/javac" --release 8 -classpath "$ANDROID_JAR" -d "$WORK/classes" \
  "$ROOT/android/src/app/liubai/journal/JournalStore.java" \
  "$ROOT/android/src/app/liubai/journal/PdfExporter.java" \
  "$ROOT/android/src/app/liubai/journal/MainActivity.java"
"$JAVA_HOME/bin/jar" cf "$WORK/classes.jar" -C "$WORK/classes" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/classes.jar"
(cd "$WORK/dex" && "$TOOLS/aapt" add "$WORK/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$OUT" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUT"
rm -f "$OUT.idsig"
echo "APK: $OUT"
