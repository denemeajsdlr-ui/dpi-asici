#!/usr/bin/env bash
# Native bileşenleri indirir ve derler (Linux / macOS / WSL / Git Bash).
set -euo pipefail
cd "$(dirname "$0")/.."

# 1) hev-socks5-tunnel (VPN trafiğini SOCKS5'e çeviren tünel motoru)
if [ ! -f app/src/main/jni/hev-socks5-tunnel/Android.mk ]; then
  rm -rf app/src/main/jni/hev-socks5-tunnel
  git clone --recursive --depth 1 https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel
fi

# 2) ByeDPI (DPI aşma motoru) - NDK ile çalıştırılabilir dosya olarak derlenir
rm -rf build/byedpi && mkdir -p build
git clone --depth 1 https://github.com/hufrea/byedpi build/byedpi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then NDK="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"; fi
HOST="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
SRC="$(ls build/byedpi/*.c | grep -v win_service || true)"

build() {
  mkdir -p "app/src/main/jniLibs/$1"
  "$BIN/${2}26-clang" -O2 -std=gnu99 -D_GNU_SOURCE -Wl,-z,max-page-size=16384 -I build/byedpi \
    -o "app/src/main/jniLibs/$1/libbyedpi.so" $SRC
}
build arm64-v8a   aarch64-linux-android
build armeabi-v7a armv7a-linux-androideabi
build x86_64      x86_64-linux-android
echo "Tamam. Şimdi projeyi Android Studio ile açıp derleyebilirsin."
