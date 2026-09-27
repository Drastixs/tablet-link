#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}" 
if [[ ! -f .local-secret ]]; then
  echo 'Run ./scripts/make-secret.sh first' >&2
  exit 1
fi
if [[ ! -f "$PLATFORM" || ! -x "$TOOLS/aapt2" ]]; then
  echo 'Android SDK platform 36 and build-tools 35.0.0 are required' >&2
  exit 1
fi
mkdir -p android/generated/dev/tabletlink android/build/classes android/build/dex
SECRET="$(cat .local-secret)"
if [[ ! "$SECRET" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'Invalid .local-secret' >&2
  exit 1
fi
cat > android/generated/dev/tabletlink/Secret.java <<EOF
package dev.tabletlink;
final class Secret { static final String HEX = "$SECRET"; }
EOF
"$JAVA_HOME/bin/javac" -source 8 -target 8 -Xlint:-options -classpath "$PLATFORM" -d android/build/classes android/src/dev/tabletlink/*.java android/generated/dev/tabletlink/Secret.java
"$JAVA_HOME/bin/jar" cf android/build/classes.jar -C android/build/classes .
"$TOOLS/d8" --min-api 29 --lib "$PLATFORM" --output android/build/dex android/build/classes.jar
"$TOOLS/aapt2" link -o android/build/base.apk --manifest android/AndroidManifest.xml -I "$PLATFORM" --min-sdk-version 29 --target-sdk-version 35
(cd android/build/dex && zip -q -u ../base.apk classes.dex)
"$TOOLS/zipalign" -f 4 android/build/base.apk android/build/aligned.apk
KEYSTORE="android/build/tablet-link.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias tablet-link -keyalg RSA -keysize 3072 -validity 3650 -storepass android -keypass android -dname 'CN=Tablet Link' >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias tablet-link --ks-pass pass:android --key-pass pass:android --out android/build/tablet-link.apk android/build/aligned.apk
"$TOOLS/apksigner" verify android/build/tablet-link.apk
echo 'Built android/build/tablet-link.apk'
