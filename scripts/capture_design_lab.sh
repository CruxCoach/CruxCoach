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

{
  printf 'package=%s\n' "$package_name"
  printf 'scenario=%s\n' "$scenario"
  printf 'theme=%s\n' "$theme"
  printf 'locale=%s\n' "$locale"
  printf 'font_scale=%s\n' "$font_scale"
  printf 'source_commit=%s\n' "$(git rev-parse HEAD)"
  adb shell wm size
  adb shell wm density
  adb shell getprop ro.build.fingerprint
} > "$output_dir/environment.txt"

echo "Captured $scenario to $output_dir"
