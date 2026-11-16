#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# ---------------------------------------------------------------------------
# Toolchain configuration
# ---------------------------------------------------------------------------
# Android SDK: set ANDROID_HOME or ANDROID_SDK_ROOT.
# Build tools: set ANDROID_BUILD_TOOLS to a directory name under build-tools/.
# Xposed API stub: default libs/xposed-api-stub.jar (included in this repo).
# Signing: set KEYSTORE / KEY_ALIAS / KEYSTORE_PASS / KEY_PASS.
# ---------------------------------------------------------------------------
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-35.0.0}"
BT="$SDK_ROOT/build-tools/$BUILD_TOOLS"
AJ="${ANDROID_JAR:-$SDK_ROOT/platforms/android-35/android.jar}"
XPOSED_API="${XPOSED_API_STUB:-$PWD/libs/xposed-api-stub.jar}"
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
KS="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KS_PASS="${KEYSTORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"

if [ ! -x "$BT/aapt2" ]; then
  echo "aapt2 not found at $BT/aapt2" >&2
  echo "Set ANDROID_HOME and ANDROID_BUILD_TOOLS (e.g. 35.0.0)." >&2
  exit 1
fi
if [ ! -f "$AJ" ]; then
  echo "android.jar not found at $AJ" >&2
  echo "Set ANDROID_HOME to an SDK with platforms/android-35." >&2
  exit 1
fi
if [ ! -f "$XPOSED_API" ]; then
  echo "Xposed API stub not found at $XPOSED_API" >&2
  echo "Set XPOSED_API_STUB to an xposed-api-stub.jar." >&2
  exit 1
fi

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "== aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o build/res.zip

echo "== aapt2 link"
"$BT/aapt2" link \
  -o build/unsigned.apk \
  -I "$AJ" \
  --manifest app/src/main/AndroidManifest.xml \
  --java build/gen \
  -A app/src/main/assets \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  --auto-add-overlay \
  build/res.zip

echo "== javac"
javac -source 17 -target 17 -nowarn \
  -cp "$AJ:$XPOSED_API" \
  -d build/classes \
  $(find build/gen app/src/main/java -name '*.java')

echo "== d8"
"$BT/d8" \
  --lib "$AJ" \
  --lib "$XPOSED_API" \
  --min-api 29 \
  --output build/dex \
  $(find build/classes -name '*.class')

echo "== shims dex"
# Only the two compatibility classes are injected into the target app.
mkdir -p build/shims build/assets
"$BT/d8" \
  --lib "$AJ" \
  --min-api 29 \
  --output build/shims \
  build/classes/huawei/android/widget/HwTextView.class \
  build/classes/huawei/android/widget/ListView.class
cp build/shims/classes.dex build/assets/shims.dex

echo "== package"
cp build/unsigned.apk build/app.apk
(cd build && zip -q -j app.apk dex/classes.dex && zip -q app.apk assets/shims.dex)

echo "== zipalign"
"$BT/zipalign" -f 4 build/app.apk build/app-aligned.apk

echo "== sign"
if [ ! -f "$KS" ]; then
  echo "Keystore not found at $KS" >&2
  echo "Set KEYSTORE, KEY_ALIAS, KEYSTORE_PASS and KEY_PASS." >&2
  exit 1
fi
"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out build/HiDiskHook.apk \
  build/app-aligned.apk

"$BT/apksigner" verify --print-certs build/HiDiskHook.apk | head -12
echo "BUILT: $(ls -lh build/HiDiskHook.apk | awk '{print $5}')"
