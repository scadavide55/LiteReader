# Building LiteReader

The build scripts target macOS (Apple Silicon works through Rosetta 2). They produce a `minSdk 14`, `armeabi-v7a` APK with a v1-only signature, which is what Android 4.0 accepts.

## Requirements

- Android NDK **r17c** (the last NDK that supports API 14). It is an x86_64 build, so Apple Silicon needs Rosetta 2.
- Android SDK build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`) and an `android.jar`.
- JDK 8 or newer (`javac --release 8`), plus `curl`, `make`, `tar`, `zip`.

## Paths

Both scripts read these environment variables. The defaults are shown.

| Variable | Default |
| --- | --- |
| `ROOT` | `$HOME/Documents/android-feasibility` |
| `NDK` | `$ROOT/ndk/android-ndk-r17c` |
| `BT` | `$HOME/Library/Android/sdk/build-tools/36.0.0` |
| `JAR` | `$HOME/Library/Android/sdk/platforms/android-37.0/android.jar` |
| `KS` | `$ROOT/testapp/debug.keystore` |

## Steps

1. Build the trimmed MuPDF 1.28.5 static libraries. The script downloads the official source tarball and checks its SHA-256.

   ```bash
   scripts/build-mupdf.sh /path/to/workdir
   ```

2. Copy the two libraries into the project.

   ```bash
   mkdir -p litereader/jni_mu/prebuilt
   cp /path/to/workdir/src/build/small/libmupdf.a /path/to/workdir/src/build/small/libmupdf-third.a litereader/jni_mu/prebuilt/
   ```

3. Create a signing key. Any key works; this one is the usual debug key.

   ```bash
   mkdir -p "$HOME/Documents/android-feasibility/testapp"
   keytool -genkeypair -keystore "$HOME/Documents/android-feasibility/testapp/debug.keystore" \
     -storepass android -keypass android -alias androiddebugkey \
     -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```

4. Build the APK. It is written to `litereader/LiteReader.apk`.

   ```bash
   cd litereader && ./build.sh
   ```

## Notes

- The MuPDF libraries are not in the repository. `jni_mu/include/` holds only the headers.
- Do not commit the keystore, the APK or the build output folders.
- The APK is licensed under AGPL-3.0 because it links MuPDF. See `LICENSE`.
