#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/check_secrets.sh [--staged]

Scan tracked working-tree content (default) or the staged index for common
credential formats and secret-bearing file names. Match contents are never
printed; diagnostics contain only the rule and affected path.
EOF
}

mode=worktree
case "${1:-}" in
    "") ;;
    --staged) mode=staged ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "Secret scan must run inside a Git worktree." >&2
    exit 2
}
cd "$repo_root"

grep_args=(-I -n -E)
if [ "$mode" = staged ]; then
    grep_args+=(--cached)
fi

# Public templates intentionally show variable names and placeholders. They
# are still protected by the dangerous-file-name check if renamed to a live
# configuration filename.
pathspecs=(
    .
    ':(exclude).env.example'
    ':(exclude)local.properties.example'
)

failed=0

scan_rule() {
    local name="$1" regex="$2" matches paths
    matches=$(git grep "${grep_args[@]}" -e "$regex" -- "${pathspecs[@]}" || true)

    # Two parser tests deliberately use the documented non-secret `fake`
    # nsec placeholder. Discard only that complete value, not the whole file.
    matches=$(printf '%s\n' "$matches" \
        | grep -Ev 'nsec1(fake){14,15}([^023456789ac-hj-np-z]|$)' || true)
    [ -n "$matches" ] || return 0

    paths=$(printf '%s\n' "$matches" | cut -d: -f1 | sort -u)
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        printf 'secret-scan: %s matched in %s (content redacted)\n' "$name" "$path" >&2
    done <<< "$paths"
    failed=1
}

scan_rule private-key '-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----'
scan_rule nostr-secret-key 'nsec1[023456789ac-hj-np-z]{58}'
scan_rule github-token 'gh[pousr]_[A-Za-z0-9]{30,}'
scan_rule gitlab-token 'glpat-[A-Za-z0-9_-]{20,}'
scan_rule aws-access-key 'AKIA[0-9A-Z]{16}'
scan_rule google-api-key 'AIza[0-9A-Za-z_-]{35}'
scan_rule slack-token 'xox[baprs]-[A-Za-z0-9-]{20,}'
scan_rule release-secret-assignment \
    '(RELEASE_(STORE|KEY)_PASSWORD|SIGN_WITH|CODEBERG_TOKEN)[[:space:]]*=[[:space:]]*[^$<{[:space:]][^[:space:]]{7,}'

if [ "$mode" = staged ]; then
    tracked_files=$(git ls-files)
else
    tracked_files=$(git ls-files)
fi

dangerous_paths=$(printf '%s\n' "$tracked_files" \
    | grep -E '(^|/)(\.env($|\.)|local\.properties($|\.)|(key|keystore|signing|secrets)\.properties($|\.)|[^/]+\.(keystore|jks|p12|pfx|pem|p8|ppk))' \
    | grep -Ev '(^|/)(\.env|local\.properties)\.example$' || true)
if [ -n "$dangerous_paths" ]; then
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        printf 'secret-scan: secret-bearing filename is tracked: %s\n' "$path" >&2
    done <<< "$dangerous_paths"
    failed=1
fi

if [ "$failed" -ne 0 ]; then
    echo 'Secret scan failed; rotate any real exposed credential before removing it.' >&2
    exit 1
fi

echo "Secret scan passed (${mode})."
