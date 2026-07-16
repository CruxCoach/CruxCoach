#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: scripts/rebrand_ui.sh <new-display-name>" >&2
    exit 2
fi

new_name=$1
if [[ -z "$new_name" || ${#new_name} -gt 50 ||
      "$new_name" == [[:space:]]* || "$new_name" == *[[:space:]] ||
      "$new_name" == *$'\n'* || "$new_name" == *$'\r'* ||
      "$new_name" == *'<'* || "$new_name" == *'>'* ||
      "$new_name" == *'&'* || "$new_name" == *'"'* ||
      "$new_name" == *\\* ]]; then
    echo "display name must be 1-50 characters and contain no XML/Kotlin escaping characters" >&2
    exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir/.." rev-parse --show-toplevel)
cd "$repo_root"
command -v perl >/dev/null 2>&1 || {
    echo "perl is required for the literal UTF-8 replacement" >&2
    exit 2
}

files=(
    androidApp/src/main/res/values/strings.xml
    androidApp/src/main/res/values-de/strings.xml
)

before=0
for file in "${files[@]}"; do
    count=$({ grep -oF 'CruxCoach' "$file" || true; } | wc -l)
    before=$((before + count))
    NEW_NAME="$new_name" perl -0777 -pi -e 's/CruxCoach/$ENV{NEW_NAME}/g' "$file"
done

if grep -F 'CruxCoach' "${files[@]}"; then
    echo "upstream display name remains in localized UI resources" >&2
    exit 1
fi
for file in "${files[@]}"; do
    if ! grep -Fq "<string name=\"app_name\">$new_name</string>" "$file"; then
        echo "app_name did not become the requested display name in $file" >&2
        exit 1
    fi
done

echo "Replaced $before localized upstream-name occurrences with: $new_name"
echo "Set APP_DISPLAY_NAME to the same value in local.properties, configure the"
echo "complete fork identity, update zapstore.yaml and documentation, replace"
echo "brand assets, then run scripts/check_rebrand_assets.sh <upstream-ref>."
