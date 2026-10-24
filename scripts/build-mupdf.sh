#!/bin/bash
# Reproducible trimmed MuPDF 1.28.5 build for Android armeabi-v7a / API 14 (NEON), host: Apple Silicon macOS (Rosetta).
# Usage: scripts/build-mupdf.sh [workdir]   (default: ~/Documents/android-feasibility/mupdf-repro)
# Requires: ~/Documents/android-feasibility/ndk/android-ndk-r17c (see REPORT.md step 1), Rosetta 2, curl, make, tar.
set -euo pipefail
ROOT="${ROOT:-$HOME/Documents/android-feasibility}"
NDK="${NDK:-$ROOT/ndk/android-ndk-r17c}"
WORK="${1:-$ROOT/mupdf-repro}"
VER=1.28.5
SHA256=98a5c10cda20c3992cdf76ff6b2a1149c32bd79cc796d3f703230b1185b7e934
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"

[ -d "$NDK" ] || { echo "NDK missing: $NDK"; exit 1; }
arch -x86_64 /usr/bin/true || { echo "Rosetta 2 missing"; exit 1; }
T="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
G="$NDK/toolchains/arm-linux-androideabi-4.9/prebuilt/darwin-x86_64"
L="$NDK/sources/cxx-stl/llvm-libc++"
FLAGS="-gcc-toolchain $G -target armv7-none-linux-androideabi14 -march=armv7-a -mfloat-abi=softfp -mfpu=neon -mthumb -D__ANDROID_API__=14 --sysroot $NDK/sysroot -isystem $NDK/sysroot/usr/include/arm-linux-androideabi -Wno-unused-command-line-argument"

mkdir -p "$WORK/tc" "$WORK/out"
cd "$WORK"

# cross wrappers (host make otherwise picks the Mac compiler for C++ / ld)
cat > tc/cc <<EOF
#!/bin/bash
exec arch -x86_64 $T/clang $FLAGS "\$@"
EOF
cat > tc/cxx <<EOF
#!/bin/bash
exec arch -x86_64 $T/clang++ $FLAGS -nostdinc++ -isystem $L/include -isystem $NDK/sources/android/support/include -isystem $NDK/sources/cxx-stl/llvm-libc++abi/include "\$@"
EOF
cat > tc/ar <<EOF
#!/bin/bash
exec arch -x86_64 $G/bin/arm-linux-androideabi-ar "\$@"
EOF
cat > tc/ranlib <<EOF
#!/bin/bash
exec arch -x86_64 $G/bin/arm-linux-androideabi-ranlib "\$@"
EOF
cat > tc/strip <<EOF
#!/bin/bash
exec arch -x86_64 $G/bin/arm-linux-androideabi-strip "\$@"
EOF
chmod +x tc/*

# source
if [ ! -f mupdf-$VER-source.tar.gz ]; then
  curl -fL -o mupdf-$VER-source.tar.gz https://mupdf.com/downloads/archive/mupdf-$VER-source.tar.gz
fi
echo "$SHA256  mupdf-$VER-source.tar.gz" | shasum -a 256 -c -
rm -rf src && mkdir src && tar xzf mupdf-$VER-source.tar.gz -C src --strip-components=1

# trimmed library build
( cd src && make -j8 libs build=small OS=Linux HAVE_OBJCOPY=no \
    CC="$WORK/tc/cc" CXX="$WORK/tc/cxx" AR="$WORK/tc/ar" RANLIB="$WORK/tc/ranlib" \
    HAVE_X11=no HAVE_GLUT=no HAVE_CURL=no \
    XCFLAGS="-DTOFU -DTOFU_CJK -DTOFU_SIL -DTOFU_EMOJI -DTOFU_SYMBOL -DTOFU_NOTO -DFZ_ENABLE_JS=0 -DFZ_ENABLE_ICC=0 -D_FILE_OFFSET_BITS=32 -Os" ) > out/build.log 2>&1

# probe program
tc/cc -Os -std=gnu11 -Isrc/include -c "$SCRIPTS/mprobe.c" -o out/mprobe.o
tc/cxx -Os -fno-pie -no-pie -Wl,--fix-cortex-a8 --sysroot "$NDK/platforms/android-14/arch-arm" out/mprobe.o \
  src/build/small/libmupdf.a src/build/small/libmupdf-third.a \
  $L/libs/armeabi-v7a/libc++_static.a $L/libs/armeabi-v7a/libc++abi.a $L/libs/armeabi-v7a/libunwind.a $L/libs/armeabi-v7a/libandroid_support.a \
  -lm -llog -ldl -lc -Wl,--gc-sections -Wl,--no-undefined -o out/mprobe
tc/strip --strip-unneeded -o out/mprobe.stripped out/mprobe
ls -l src/build/small/*.a out/mprobe.stripped
echo "OK: $WORK/out/mprobe.stripped"
