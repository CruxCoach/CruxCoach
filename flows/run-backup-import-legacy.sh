#!/usr/bin/env bash
# Convenience entry point for the legacy fixture flow. Fixture validation,
# exact-name staging, checksum verification, and cleanup are owned by run.sh.

set -euo pipefail
umask 077

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly REPO_ROOT
readonly RUNNER="$REPO_ROOT/flows/run.sh"

usage() {
    cat <<'EOF'
Usage: flows/run-backup-import-legacy.sh [--repeat N]

Required environment:
  MAESTRO_DEVICE_SERIAL   Exact ADB serial to target.

The evidence variables and ADB/Maestro overrides accepted by flows/run.sh are
also honored.
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

repeat_count=1
while (($# > 0)); do
    case "$1" in
        --repeat)
            (($# >= 2)) || die "--repeat needs a value"
            repeat_count="$2"
            shift 2
            ;;
        --repeat=*)
            repeat_count="${1#*=}"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
    esac
done
[[ "$repeat_count" =~ ^[1-9][0-9]*$ ]] || die "--repeat must be a positive integer"

DEVICE_SERIAL="${MAESTRO_DEVICE_SERIAL:-}"
[[ -n "$DEVICE_SERIAL" ]] || die "MAESTRO_DEVICE_SERIAL is required"
[[ "$DEVICE_SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || die "invalid device serial"

MAESTRO_DEVICE_SERIAL="$DEVICE_SERIAL" "$RUNNER" --repeat "$repeat_count" backup-import-legacy
