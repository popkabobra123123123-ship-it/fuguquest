#!/usr/bin/env bash
# Build fugu-root.apk — the on-device front-end (adb-wireless self-connect + fuguquest --local).
# Same no-Gradle pipeline as the repo's other PoCs: aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner.
#
# Env (all optional):
#   BT        build-tools dir with aapt2/d8/zipalign/apksigner   [auto-downloaded 34.0.0]
#   PLATFORM  android.jar                                        [auto-downloaded platform-33]
#   NDK       NDK root, for the aarch64-linux-android rust build [skipped if the binary exists]
#   JDK       java home (javac/keytool/java must be on PATH)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CACHE="${CACHE:-$HOME/.android-cache}"
BT="${BT:-$CACHE/build-tools/34.0.0}"
PLATFORM="${PLATFORM:-$CACHE/platform-33/android.jar}"
OUT="$HERE/build"
APK="$OUT/fugu-root.apk"
KS="${KS:-$OUT/debug.keystore}"

fetch_tools() {
    mkdir -p "$CACHE"
    if [ ! -x "$BT/aapt2" ]; then
        echo "[tools] build-tools 34"
        curl -sSfL -o "$CACHE/bt.zip" https://dl.google.com/android/repository/build-tools_r34-linux.zip
        rm -rf "$CACHE/bt-unpack"; mkdir -p "$CACHE/bt-unpack"
        unzip -q "$CACHE/bt.zip" -d "$CACHE/bt-unpack"
        local aapt2; aapt2="$(find "$CACHE/bt-unpack" -name aapt2 -type f | head -1)"
        [ -n "$aapt2" ] || { echo "aapt2 not found in build-tools zip" >&2; exit 1; }
        rm -rf "$BT"; mkdir -p "$BT"; cp -r "$(dirname "$aapt2")"/. "$BT"/
        rm -rf "$CACHE/bt.zip" "$CACHE/bt-unpack"
    fi
    if [ ! -f "$PLATFORM" ]; then
        echo "[tools] platform-33"
        curl -sSfL -o "$CACHE/plat.zip" https://dl.google.com/android/repository/platform-33_r02.zip
        rm -rf "$CACHE/plat-unpack"; mkdir -p "$CACHE/plat-unpack"
        unzip -q "$CACHE/plat.zip" -d "$CACHE/plat-unpack"
        local jar; jar="$(find "$CACHE/plat-unpack" -name android.jar | head -1)"
        [ -n "$jar" ] || { echo "android.jar not found in platform zip" >&2; exit 1; }
        rm -rf "$(dirname "$PLATFORM")"; mkdir -p "$(dirname "$PLATFORM")"; cp "$jar" "$PLATFORM"
        rm -rf "$CACHE/plat.zip" "$CACHE/plat-unpack"
    fi
}

fugu_arm64() {
    local b="$ROOT/rust/target/aarch64-linux-android/release/fuguquest"
    [ -f "$b" ] && { echo "$b"; return; }
    if [ -n "${NDK:-}" ]; then
        echo "[bin] building fuguquest (aarch64-linux-android, NDK)..."
        export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
        (cd "$ROOT/rust" && cargo build --release --target aarch64-linux-android >/dev/null)
        [ -f "$b" ] && { echo "$b"; return; }
    fi
    local m="$ROOT/rust/target/aarch64-unknown-linux-musl/release/fuguquest"
    [ -f "$m" ] && { echo "$m"; return; }
    local p="$HERE/prebuilt/fuguquest-arm64-musl"
    [ -f "$p" ] && { echo "$p"; return; }
    echo "no arm64 fuguquest: set NDK=, prebuild one, or keep app/prebuilt/" >&2
    exit 1
}

fetch_tools
FUGU="$(fugu_arm64)"
echo "[bin] $FUGU"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/stage/assets/fugu"
cp "$FUGU" "$OUT/stage/assets/fugu/fuguquest"
cp -r "$ROOT/targets/." "$OUT/stage/assets/fugu/targets/"

echo "[1/5] aapt2 link"
"$BT/aapt2" link -I "$PLATFORM" --manifest "$HERE/AndroidManifest.xml" \
    -A "$OUT/stage/assets" --min-sdk-version 29 --target-sdk-version 33 -o "$OUT/base.apk"

echo "[2/5] javac"
find "$HERE/src" -name '*.java' > "$OUT/sources.txt"
# --release 8 (not -bootclasspath android.jar): android.jar class stubs lack the JDK's
# LambdaMetafactory, which javac needs to compile lambdas; d8 min-api 29 needs no desugaring.
javac --release 8 -encoding UTF-8 -classpath "$PLATFORM" \
    -d "$OUT/classes" -nowarn @"$OUT/sources.txt"

echo "[3/5] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --min-api 29 --lib "$PLATFORM" --output "$OUT" @"$OUT/classes.txt"

echo "[4/5] assemble"
cp "$OUT/base.apk" "$OUT/app.apk"
( cd "$OUT" && zip -q -u app.apk classes.dex )

echo "[5/5] zipalign + sign"
"$BT/zipalign" -f -p 4 "$OUT/app.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -noprompt -keystore "$KS" -storepass android -keypass android \
        -alias poc -keyalg RSA -keysize 2048 -validity 3650 \
        -dname "CN=fuguroot, OU=research, O=research, C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -2
echo "built: $APK ($(du -h "$APK" | cut -f1))"
