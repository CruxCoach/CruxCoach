#!/usr/bin/env bash
# Builds the reduced ONNX Runtime AAR for the spray-wall hold model (FEAT-078):
# only the operators in required_operators.config, CPU only (no NNAPI, XNNPACK
# or WebGPU), arm64-v8a, no telemetry, Release with LTO. The Java API is the
# same as the official onnxruntime-android AAR of the same version.
#
#   tools/onnxruntime/build-android.sh <work dir>
#
# Needs: git, cmake >= 3.28, ninja, Python 3 with flatbuffers, Java 17, the
# Android SDK in ANDROID_HOME. The NDK below is installed if it is missing.
# Writes <work dir>/out/onnxruntime-android-reduced-<version>.aar and SHA256SUMS.
set -euo pipefail

ORT_VERSION=1.31.1
ORT_COMMIT=6af1a932e2286d5c58166085d24c68783033edee
NDK_VERSION=27.2.12479018
ANDROID_API=24

work=${1:?work dir}
here=$(cd "$(dirname "$0")" && pwd)
config="$here/required_operators.config"
mkdir -p "$work/out"

if [ ! -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
  # yes ends on a broken pipe once sdkmanager is done; the check below is what counts.
  (yes || true) | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "ndk;$NDK_VERSION" > /dev/null
  [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ] || { echo "NDK $NDK_VERSION was not installed" >&2; exit 1; }
fi

src="$work/onnxruntime"
if [ ! -d "$src/.git" ]; then
  git clone --quiet --depth 1 --branch "v$ORT_VERSION" https://github.com/microsoft/onnxruntime.git "$src"
fi
actual=$(git -C "$src" rev-parse HEAD)
if [ "$actual" != "$ORT_COMMIT" ]; then
  echo "v$ORT_VERSION is $actual, expected $ORT_COMMIT" >&2
  exit 1
fi

# Third-party sources come from cmake/deps.txt, each pinned by its hash.
python3 "$src/tools/ci_build/build.py" \
  --build_dir "$work/build" --config Release --update --build --parallel \
  --android --android_sdk_path "$ANDROID_HOME" --android_ndk_path "$ANDROID_HOME/ndk/$NDK_VERSION" \
  --android_abi arm64-v8a --android_api "$ANDROID_API" \
  --build_java --build_shared_lib --skip_tests --skip_submodule_sync \
  --cmake_generator Ninja --enable_lto \
  --include_ops_by_config "$config" --disable_ml_ops --no_telemetry --compile_no_warning_as_error \
  --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF

aar="onnxruntime-android-reduced-$ORT_VERSION.aar"
cp "$work/build/Release/java/build/android/outputs/aar/onnxruntime-release.aar" "$work/out/$aar"
(cd "$work/out" && sha256sum "$aar" > SHA256SUMS && cat SHA256SUMS)
