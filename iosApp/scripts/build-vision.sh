#!/usr/bin/env bash
# Builds Frameworks/CruxVision.xcframework: Android's food photo engine
# (androidApp/src/main/cpp/vision/food_vision.cpp) over the pinned llama.cpp
# b11396 + libmtmd, with the C bridge in iosApp/Vision, as one static library
# for iOS devices (arm64) and one for the simulator (arm64). CPU only, as on
# Android. macOS + Xcode + CMake required; no Gradle, no network beyond the
# pinned source archive, which is checked against its SHA-256.
#
# Usage: iosApp/scripts/build-vision.sh
# CRUX_VISION_GENERATOR selects the CMake generator ("Unix Makefiles" by
# default; "Xcode" is what llama.cpp's own build-xcframework.sh uses).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/iosApp/Vision"
WORK="${CRUX_VISION_BUILD_DIR:-$ROOT/iosApp/build/vision}"
OUT="$ROOT/iosApp/Frameworks"
GENERATOR="${CRUX_VISION_GENERATOR:-Unix Makefiles}"
MIN_IOS="17.0"
TAG="b11396"
SHA256="fce45a07b2a6a0fb11f2b0fc8695ae5b463e95b47febdea3ae9f7db95f6c1f72"
JOBS="$(sysctl -n hw.logicalcpu)"

mkdir -p "$WORK"
ARCHIVE="$WORK/llama.cpp-$TAG.tar.gz"
if [ ! -f "$ARCHIVE" ] || ! echo "$SHA256  $ARCHIVE" | shasum -a 256 -c - >/dev/null 2>&1; then
  curl -fsSL --retry 3 -o "$ARCHIVE.part" "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$TAG.tar.gz"
  mv "$ARCHIVE.part" "$ARCHIVE"
fi
echo "$SHA256  $ARCHIVE" | shasum -a 256 -c -
LLAMA_SRC="$WORK/llama.cpp-$TAG"
if [ ! -f "$LLAMA_SRC/CMakeLists.txt" ]; then
  rm -rf "$LLAMA_SRC"
  tar -xzf "$ARCHIVE" -C "$WORK"
fi

# build <name> <sysroot>: configures and builds one platform, then merges every
# static library of that build (bridge, mtmd, llama, ggml*, vendor) into one.
build() {
  local name="$1" sysroot="$2" dir="$WORK/$1"
  echo "== $name ($sysroot, $GENERATOR)"
  rm -rf "$dir"
  cmake -S "$SRC" -B "$dir" -G "$GENERATOR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$sysroot" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$MIN_IOS" \
    -DCMAKE_XCODE_ATTRIBUTE_SUPPORTED_PLATFORMS="$sysroot" \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY="" \
    -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP="$LLAMA_SRC" \
    -DFETCHCONTENT_FULLY_DISCONNECTED=ON
  if [ "$GENERATOR" = "Xcode" ]; then
    cmake --build "$dir" --config Release -j "$JOBS" -- -quiet
  else
    cmake --build "$dir" --config Release -j "$JOBS"
  fi
  local libs=()
  while IFS= read -r lib; do libs+=("$lib"); done < <(find "$dir" -name '*.a' -not -path '*/CMakeFiles/*' | sort)
  printf '   %s\n' "${libs[@]}"
  xcrun libtool -static -o "$dir/libCruxVision.a" "${libs[@]}"
  # Fails the build when an expected entry point is missing from the merged library.
  nm -gU "$dir/libCruxVision.a" > "$dir/symbols.txt" 2>/dev/null || true
  for sym in _crux_vision_load _crux_vision_run _mtmd_init_from_file _llama_model_load_from_file _ggml_backend_cpu_init; do
    grep -q " T $sym\$" "$dir/symbols.txt" || { echo "missing $sym in $name" >&2; exit 1; }
  done
}

build device iphoneos
build simulator iphonesimulator

rm -rf "$OUT/CruxVision.xcframework"
mkdir -p "$OUT"
xcodebuild -create-xcframework \
  -library "$WORK/device/libCruxVision.a" \
  -library "$WORK/simulator/libCruxVision.a" \
  -output "$OUT/CruxVision.xcframework"
ls -la "$OUT/CruxVision.xcframework"/*/
