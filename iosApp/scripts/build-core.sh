#!/usr/bin/env bash
# Builds Frameworks/CruxCoachCore.xcframework from :appcore. macOS + Xcode + JDK 17 required
# (Gradle also needs an Android SDK because :shared applies the Android library plugin).
# Usage: iosApp/scripts/build-core.sh [Debug|Release]
set -euo pipefail
CONFIG="${1:-Release}"; lower="$(echo "$CONFIG" | tr '[:upper:]' '[:lower:]')"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; OUT="$ROOT/iosApp/Frameworks"
cd "$ROOT"
./gradlew --console=plain ":appcore:link${CONFIG}FrameworkIosArm64" ":appcore:link${CONFIG}FrameworkIosSimulatorArm64"
rm -rf "$OUT/CruxCoachCore.xcframework"; mkdir -p "$OUT"
xcodebuild -create-xcframework \
  -framework "appcore/build/bin/iosArm64/${lower}Framework/CruxCoachCore.framework" \
  -framework "appcore/build/bin/iosSimulatorArm64/${lower}Framework/CruxCoachCore.framework" \
  -output "$OUT/CruxCoachCore.xcframework"
