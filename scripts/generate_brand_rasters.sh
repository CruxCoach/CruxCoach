#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir/.." rev-parse --show-toplevel)
cd "$repo_root"

master=${1:-logos/cruxcoach-logo-final.svg}
foreground=${2:-"$master"}

if ! command -v rsvg-convert >/dev/null 2>&1; then
    echo "rsvg-convert is required (install GNOME librsvg)." >&2
    exit 2
fi
for source in "$master" "$foreground"; do
    if [[ ! -f "$source" ]]; then
        echo "SVG source not found: $source" >&2
        exit 2
    fi
done

render() {
    local source=$1
    local size=$2
    local output=$3
    mkdir -p "$(dirname "$output")"
    rsvg-convert -w "$size" -h "$size" -o "$output" "$source"
}

while read -r density legacy_size foreground_size; do
    output_dir="androidApp/src/main/res/mipmap-$density"
    render "$master" "$legacy_size" "$output_dir/ic_launcher.png"
    cp "$output_dir/ic_launcher.png" "$output_dir/ic_launcher_round.png"
    render "$foreground" "$foreground_size" "$output_dir/ic_launcher_foreground.png"
done <<'EOF'
mdpi 48 108
hdpi 72 162
xhdpi 96 216
xxhdpi 144 324
xxxhdpi 192 432
EOF

render "$master" 512 logos/play_store_icon_512.png
render "$master" 48 logos/preview/logo-48.png
render "$master" 256 logos/preview/logo-256.png
render "$master" 512 logos/preview/logo-512.png

echo "Raster exports regenerated."
echo "Now replace drawable/ic_launcher_monochrome.xml, review the adaptive/splash"
echo "wrappers and background color, and recreate or remove app-icon-preview.png."
