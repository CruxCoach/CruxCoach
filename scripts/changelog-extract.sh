#!/usr/bin/env bash
# Extract the release-notes section for a given version from CHANGELOG.md.
# Matches Keep-a-Changelog format: "## [VERSION] - YYYY-MM-DD" (date optional).
# Prints the body between that heading and the next "## [" heading.
#
# Usage:
#   scripts/changelog-extract.sh 0.1.2            # release section
#   scripts/changelog-extract.sh Unreleased       # in-progress section
#   scripts/changelog-extract.sh 0.1.2 path.md    # alternate file
#
# Prints extracted body to stdout. Exit 0 on success (even if empty),
# exit 1 if the heading for the requested version was not found.

set -euo pipefail

VERSION="${1:?version required (e.g. 0.1.2 or Unreleased)}"
CHANGELOG="${2:-CHANGELOG.md}"

[ -f "$CHANGELOG" ] || {
    echo "changelog-extract: file not found: $CHANGELOG" >&2
    exit 2
}

awk -v ver="$VERSION" '
    BEGIN { found = 0; inside = 0 }
    /^## \[/ {
        if (inside) exit
        # Match "## [ver]" either alone or followed by whitespace/"-".
        prefix = "## [" ver "]"
        if (substr($0, 1, length(prefix)) == prefix) {
            found = 1
            inside = 1
            next
        }
        next
    }
    inside { print }
    END { exit found ? 0 : 1 }
' "$CHANGELOG"
