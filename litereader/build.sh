#!/bin/bash
# Builds LiteReader.apk (minSdk 14, armeabi-v7a, debug-signed, v1 signature only so Android 4.0 accepts it).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$HOME/Documents/android-feasibility
NDK=$ROOT/ndk/android-ndk-r17c
BT=$HOME/Library/Android/sdk/build-tools/36.0.0
JAR=$HOME/Library/Android/sdk/platforms/android-37.0/android.jar
rm -rf out && mkdir -p out/classes out/dex out/apk/lib/armeabi-v7a out/res
arch -x86_64 /bin/bash -c "$NDK/ndk-build -j4 NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk NDK_APPLICATION_MK=jni/Application.mk" > out/ndk.log 2>&1 || { tail -30 out/ndk.log; exit 1; }
arch -x86_64 /bin/bash -c "$NDK/ndk-build -j4 NDK_PROJECT_PATH=$PWD/jni_mu_proj NDK_LIBS_OUT=$PWD/libs_mu NDK_OUT=$PWD/obj_mu APP_BUILD_SCRIPT=$PWD/jni_mu/Android.mk NDK_APPLICATION_MK=$PWD/jni_mu/Application.mk" > out/ndk_mu.log 2>&1 || { tail -30 out/ndk_mu.log; exit 1; }
cp libs/armeabi-v7a/liblrdec.so libs_mu/armeabi-v7a/liblrmu.so out/apk/lib/armeabi-v7a/
$BT/aapt2 compile --dir res -o out/res/res.zip
$BT/aapt2 link -o out/base.apk -I $JAR --manifest AndroidManifest.xml -R out/res/res.zip --min-sdk-version 14 --target-sdk-version 15 --version-code 1 --version-name 1.0
SRC=src; OUTAPK=LiteReader.apk
if [ "${SIM:-0}" = "1" ]; then rm -rf out/srcsim && cp -r src out/srcsim && sed -i '' 's/SIM = false/SIM = true/' out/srcsim/com/litereader/BuildFlags.java && SRC=out/srcsim; OUTAPK=LiteReader-sim.apk; fi
javac --release 8 -Xlint:-options -cp $JAR -d out/classes $(find $SRC -name '*.java')
$BT/d8 --min-api 14 --lib $JAR --output out/dex $(find out/classes -name '*.class')
cp out/dex/classes.dex out/apk/
cp out/base.apk out/unsigned.apk && (cd out/apk && zip -q -r ../unsigned.apk classes.dex lib)
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
KS=$ROOT/testapp/debug.keystore
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android --min-sdk-version 14 \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false --out $OUTAPK out/aligned.apk
ls -l $OUTAPK
