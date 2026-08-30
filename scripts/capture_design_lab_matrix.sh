#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <package> <compact|expanded> <output-root>" >&2
  exit 64
fi

package_name=$1
width_class=$2
output_root=$3

if [[ ! "$package_name" =~ ^[A-Za-z][A-Za-z0-9_.]*$ ]]; then
  echo "invalid Android package: $package_name" >&2
  exit 64
fi
case "$width_class" in compact|expanded) ;; *) echo "width must be compact or expanded" >&2; exit 64 ;; esac

size=$(adb shell wm size | tail -n 1 | sed -E 's/.*: ([0-9]+)x[0-9]+/\1/')
density=$(adb shell wm density | tail -n 1 | sed -E 's/.*: ([0-9]+)/\1/')
if [[ ! "$size" =~ ^[0-9]+$ || ! "$density" =~ ^[0-9]+$ ]]; then
  echo "unable to read deterministic display width and density" >&2
  exit 1
fi
width_dp=$((size * 160 / density))
if [[ "$width_class" == compact && "$width_dp" -ge 600 ]]; then
  echo "connected renderer is ${width_dp}dp, not compact" >&2
  exit 1
fi
if [[ "$width_class" == expanded && "$width_dp" -lt 600 ]]; then
  echo "connected renderer is ${width_dp}dp, not expanded" >&2
  exit 1
fi

scenarios=(
  log/new-send log/new-attempt log/edit-send log/saving log/success log/error
  browser/content browser/empty browser/error
  session/active session/resting session/paused session/active-no-climb
  detail/disconnected detail/connected
  progress/history progress/empty progress/error
)

for scenario in "${scenarios[@]}"; do
  for theme in light dark; do
    for locale in en de; do
      for font_scale in 1.0 1.5; do
        destination="$output_root/$width_class/$scenario/$theme-$locale-$font_scale"
        scripts/capture_design_lab.sh \
          "$package_name" "$scenario" "$theme" "$locale" "$font_scale" "$destination"
      done
    done
  done
done
