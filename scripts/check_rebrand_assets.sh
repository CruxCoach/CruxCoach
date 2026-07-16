#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: scripts/check_rebrand_assets.sh <upstream-commit-or-tag>" >&2
    exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir/.." rev-parse --show-toplevel)
cd "$repo_root"
upstream_ref=$1
git rev-parse --verify --quiet "$upstream_ref^{commit}" >/dev/null || {
    echo "not a local commit or tag: $upstream_ref" >&2
    exit 2
}

required_files=(
    logos/cruxcoach-logo-final.svg
    logos/play_store_icon_512.png
    androidApp/src/main/res/drawable/ic_launcher_monochrome.xml
    androidApp/src/main/res/drawable/ic_splash.xml
    androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
    androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
    androidApp/src/main/res/values/colors.xml
)
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    required_files+=(
        "androidApp/src/main/res/mipmap-$density/ic_launcher.png"
        "androidApp/src/main/res/mipmap-$density/ic_launcher_round.png"
        "androidApp/src/main/res/mipmap-$density/ic_launcher_foreground.png"
    )
done

failed=0
for path in "${required_files[@]}"; do
    if [[ ! -f "$path" ]]; then
        echo "missing required brand resource: $path" >&2
        failed=1
    fi
done

brand_specific=(
    logos/cruxcoach-logo-final.svg
    logos/play_store_icon_512.png
    androidApp/src/main/res/drawable/ic_launcher_monochrome.xml
)
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    brand_specific+=(
        "androidApp/src/main/res/mipmap-$density/ic_launcher.png"
        "androidApp/src/main/res/mipmap-$density/ic_launcher_round.png"
        "androidApp/src/main/res/mipmap-$density/ic_launcher_foreground.png"
    )
done
for path in "${brand_specific[@]}"; do
    if [[ -e "$path" ]] && git diff --quiet "$upstream_ref" -- "$path"; then
        echo "unchanged upstream brand asset: $path" >&2
        failed=1
    fi
done

for path in logos/preview/app-icon-preview.png \
            logos/preview/logo-48.png \
            logos/preview/logo-256.png \
            logos/preview/logo-512.png; do
    if [[ -e "$path" ]] && git diff --quiet "$upstream_ref" -- "$path"; then
        echo "unchanged retained documentation preview: $path" >&2
        failed=1
    fi
done

png_dimensions() {
    local path=$1
    local signature width_hex height_hex
    signature=$(od -An -tx1 -N8 "$path" | tr -d ' \n')
    [[ "$signature" == "89504e470d0a1a0a" ]] || return 1
    width_hex=$(od -An -tx1 -N4 -j16 "$path" | tr -d ' \n')
    height_hex=$(od -An -tx1 -N4 -j20 "$path" | tr -d ' \n')
    printf '%d %d\n' "$((16#$width_hex))" "$((16#$height_hex))"
}

while read -r path expected_width expected_height; do
    [[ -f "$path" ]] || continue
    if ! actual=$(png_dimensions "$path"); then
        echo "not a valid PNG: $path" >&2
        failed=1
    elif [[ "$actual" != "$expected_width $expected_height" ]]; then
        echo "wrong PNG dimensions: $path (got $actual; expected $expected_width $expected_height)" >&2
        failed=1
    fi
done <<'EOF'
logos/play_store_icon_512.png 512 512
androidApp/src/main/res/mipmap-mdpi/ic_launcher.png 48 48
androidApp/src/main/res/mipmap-mdpi/ic_launcher_round.png 48 48
androidApp/src/main/res/mipmap-mdpi/ic_launcher_foreground.png 108 108
androidApp/src/main/res/mipmap-hdpi/ic_launcher.png 72 72
androidApp/src/main/res/mipmap-hdpi/ic_launcher_round.png 72 72
androidApp/src/main/res/mipmap-hdpi/ic_launcher_foreground.png 162 162
androidApp/src/main/res/mipmap-xhdpi/ic_launcher.png 96 96
androidApp/src/main/res/mipmap-xhdpi/ic_launcher_round.png 96 96
androidApp/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png 216 216
androidApp/src/main/res/mipmap-xxhdpi/ic_launcher.png 144 144
androidApp/src/main/res/mipmap-xxhdpi/ic_launcher_round.png 144 144
androidApp/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png 324 324
androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png 192 192
androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png 192 192
androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png 432 432
EOF

if grep -Fq 'CruxCoach' \
    androidApp/src/main/res/values/strings.xml \
    androidApp/src/main/res/values-de/strings.xml; then
    echo "upstream display name remains in localized UI resources" >&2
    failed=1
fi
if grep -Eq '^name:[[:space:]]+CruxCoach[[:space:]]*$' zapstore.yaml; then
    echo "zapstore.yaml still uses the upstream listing name" >&2
    failed=1
fi
grep -Fq '@mipmap/ic_launcher_foreground' \
    androidApp/src/main/res/drawable/ic_splash.xml || {
    echo "splash no longer composes the reviewed launcher foreground" >&2
    failed=1
}
grep -Fq '@drawable/ic_launcher_monochrome' \
    androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml || {
    echo "adaptive icon does not reference the reviewed monochrome asset" >&2
    failed=1
}

if [[ $failed -ne 0 ]]; then
    exit 1
fi
echo "Rebrand assets, dimensions, wrappers, UI text, and store name are complete."
