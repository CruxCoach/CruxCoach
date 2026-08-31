#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 6 ]]; then
  echo "usage: $0 <package> <scenario> <light|dark> <en|de> <1.0|1.5> <output-dir>" >&2
  exit 64
fi

package_name=$1
scenario=$2
theme=$3
locale=$4
font_scale=$5
output_dir=$6
apk_source_commit=${CRUXCOACH_APK_SOURCE_COMMIT:-unknown}
apk_release_sha256=${CRUXCOACH_APK_RELEASE_SHA256:-unknown}

if [[ ! "$package_name" =~ ^[A-Za-z][A-Za-z0-9_.]*$ ]]; then
  echo "invalid Android package: $package_name" >&2
  exit 64
fi
case "$scenario" in
  log/new-send|log/new-attempt|log/edit-send|log/saving|log/success|log/error|browser/content|browser/empty|browser/error|session/active|session/resting|session/paused|session/active-no-climb|progress/history|progress/empty|progress/error|detail/disconnected|detail/connected) ;;
  *) echo "unsupported scenario: $scenario" >&2; exit 64 ;;
esac
case "$theme" in light|dark) ;; *) echo "theme must be light or dark" >&2; exit 64 ;; esac
case "$locale" in en|de) ;; *) echo "locale must be en or de" >&2; exit 64 ;; esac
case "$font_scale" in 1.0|1.5) ;; *) echo "font scale must be 1.0 or 1.5" >&2; exit 64 ;; esac
if [[ "$apk_source_commit" != unknown && ! "$apk_source_commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "CRUXCOACH_APK_SOURCE_COMMIT must be a full lowercase SHA" >&2
  exit 64
fi
if [[ "$apk_release_sha256" != unknown && ! "$apk_release_sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "CRUXCOACH_APK_RELEASE_SHA256 must be a lowercase SHA-256" >&2
  exit 64
fi

mkdir -p "$output_dir"
adb wait-for-device
adb shell am start -W \
  -f 0x10008000 \
  -n "$package_name/com.cruxcoach.android.ui.designlab.DesignLabActivity" \
  --es scenario "$scenario" \
  --es theme "$theme" \
  --es locale "$locale" \
  --ef font_scale "$font_scale" >/dev/null
sleep 1

adb exec-out screencap -p > "$output_dir/screenshot.png"
adb shell uiautomator dump /sdcard/cruxcoach-designlab.xml >/dev/null
adb exec-out cat /sdcard/cruxcoach-designlab.xml > "$output_dir/semantics.xml"
adb shell rm /sdcard/cruxcoach-designlab.xml

package_dump=$(adb shell dumpsys package "$package_name")
installed_version_code=$(sed -n -E 's/^[[:space:]]*versionCode=([0-9]+).*/\1/p' <<<"$package_dump" | head -n 1)
installed_version_name=$(sed -n -E 's/^[[:space:]]*versionName=(.*)/\1/p' <<<"$package_dump" | head -n 1 | tr -d '\r')
installed_update_time=$(sed -n -E 's/^[[:space:]]*lastUpdateTime=(.*)/\1/p' <<<"$package_dump" | head -n 1 | tr -d '\r')
if [[ -z "$installed_version_code" || -z "$installed_version_name" || -z "$installed_update_time" ]]; then
  echo "unable to read installed package identity for $package_name" >&2
  exit 1
fi

{
  printf 'package=%s\n' "$package_name"
  printf 'scenario=%s\n' "$scenario"
  printf 'theme=%s\n' "$theme"
  printf 'locale=%s\n' "$locale"
  printf 'font_scale=%s\n' "$font_scale"
  printf 'apk_source_commit=%s\n' "$apk_source_commit"
  printf 'apk_release_sha256=%s\n' "$apk_release_sha256"
  printf 'installed_version_code=%s\n' "$installed_version_code"
  printf 'installed_version_name=%s\n' "$installed_version_name"
  printf 'installed_update_time=%s\n' "$installed_update_time"
  printf 'workspace_commit=%s\n' "$(git rev-parse HEAD)"
  adb shell wm size
  adb shell wm density
  adb shell getprop ro.build.fingerprint
} > "$output_dir/environment.txt"

echo "Captured $scenario to $output_dir"
