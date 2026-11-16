#!/usr/bin/env bash
# Assemble an arm64 APK with the official Android command-line build tools.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_PLATFORM_JAR:?Set the Android platform android.jar path}"
: "${ANDROID_BUILD_TOOLS:?Set the build-tools directory}"
if [ -z "${TAGLENS_PREBUILT_APK:-}" ]; then
    : "${ANDROID_NDK_HOME:?Set the Android NDK directory}"
fi
: "${APRILTAG_SRC:=$PWD/vendor/apriltag}"
: "${TAGLENS_KEYSTORE:?Set the signing keystore path}"
: "${TAGLENS_STOREPASS:?Set the signing keystore password}"
: "${TAGLENS_KEY_ALIAS:=taglens}"
: "${JAVA_HOME:?Set a JDK 17 directory}"
export PATH="$JAVA_HOME/bin:$PATH"
tools="$ANDROID_BUILD_TOOLS"
opencv=build/opencv
mkdir -p "$opencv"
if [ ! -s "$opencv/opencv.aar" ]; then
    curl -fLsS https://repo.maven.apache.org/maven2/org/opencv/opencv/4.12.0/opencv-4.12.0.aar -o "$opencv/opencv.aar.tmp"
    mv "$opencv/opencv.aar.tmp" "$opencv/opencv.aar"
fi
unzip -qo "$opencv/opencv.aar" 'classes.jar' 'jni/arm64-v8a/*' -d "$opencv"
mkdir -p build/classes build/dex build/package/lib/arm64-v8a
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link --manifest AndroidManifest.xml -I "$ANDROID_PLATFORM_JAR" \
    -A assets -o build/resources.apk build/resources.zip
find src -name '*.java' -print0 | xargs -0 "$JAVA_HOME/bin/javac" --release 8 \
    -classpath "$ANDROID_PLATFORM_JAR:$opencv/classes.jar" -encoding UTF-8 -d build/classes
"$JAVA_HOME/bin/jar" cf build/classes.jar -C build/classes .
"$tools/d8" --release --min-api 24 --lib "$ANDROID_PLATFORM_JAR" --output build/dex build/classes.jar "$opencv/classes.jar"
if [ -n "${TAGLENS_PREBUILT_APK:-}" ]; then
    unzip -qo "$TAGLENS_PREBUILT_APK" 'lib/arm64-v8a/*.so' -d build/package
else
    clang="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
    src="$APRILTAG_SRC"
    "$clang" -O3 -DNDEBUG -fPIC -flto=thin -fvisibility=hidden -shared -D_GNU_SOURCE \
        -I "$src" -I "$src/common" \
        native/taglens.c native/board_recovery.c "$src/apriltag.c" "$src/apriltag_quad_thresh.c" \
        "$src"/tag36h11.c "$src"/tag25h9.c "$src"/tag16h5.c "$src"/tagStandard41h12.c \
        "$src"/tagStandard52h13.c "$src"/tagCircle21h7.c "$src"/tagCircle49h12.c "$src"/tagCustom48h12.c \
        "$src"/common/*.c -Wl,-z,max-page-size=16384 -Wl,--gc-sections -lm -ljnigraphics \
        -o build/package/lib/arm64-v8a/libtaglens.so
    cp "$opencv"/jni/arm64-v8a/*.so build/package/lib/arm64-v8a/
fi
cp build/dex/classes*.dex build/package/
cp build/resources.apk build/unsigned.apk
(cd build/package && zip -q -r -0 ../unsigned.apk classes*.dex lib)
"$tools/zipalign" -P 16 -f 4 build/unsigned.apk build/aligned.apk
"$tools/apksigner" sign --ks "$TAGLENS_KEYSTORE" --ks-key-alias "$TAGLENS_KEY_ALIAS" \
    --ks-pass env:TAGLENS_STOREPASS --key-pass env:TAGLENS_STOREPASS \
    --out build/TagLens-Android-arm64.apk build/aligned.apk
"$tools/apksigner" verify --verbose build/TagLens-Android-arm64.apk
"$tools/zipalign" -c -P 16 4 build/TagLens-Android-arm64.apk
