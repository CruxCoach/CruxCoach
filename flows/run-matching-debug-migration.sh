#!/usr/bin/env bash
# Schema/data migration proof using caller-supplied APKs with one shared,
# ephemeral build signer. This wrapper never builds, signs, or reads signing,
# credential, auth, secret, local.properties, or keystore material.

set -euo pipefail
umask 077

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
readonly repo_root
readonly package_name="com.cruxcoach.android"

usage() {
    cat <<'EOF'
Usage:
  flows/run-matching-debug-migration.sh --old-apk APK --new-apk APK
  flows/run-matching-debug-migration.sh --preflight-only --old-apk APK --new-apk APK

The old APK must be an unmodified public-tag assembleDebug fixture for exactly
one of v0.1.4 (vc5), v0.2.0 (vc6), or v0.2.1 (vc7). The new APK must be the
actual current assembleRelease candidate at 0.2.2 (vc8) and must not be
debuggable. Package, version, signature validity, public certificate digest,
and full APK SHA-256 are checked; both APKs must share the same public signer.

Required for device execution:
  MAESTRO_DEVICE_SERIAL   Explicit exact ADB serial; no default is inferred.

Optional tool overrides: ADB, AAPT, APKSIGNER.
Evidence uses MAESTRO_EVIDENCE_DIR/MAESTRO_RUN_ID as flows/run.sh does.
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

old_apk=""
new_apk=""
preflight_only=0
while (($# > 0)); do
    case "$1" in
        --old-apk)
            (($# >= 2)) || die "--old-apk needs a value"
            old_apk="$2"
            shift 2
            ;;
        --new-apk)
            (($# >= 2)) || die "--new-apk needs a value"
            new_apk="$2"
            shift 2
            ;;
        --preflight-only)
            preflight_only=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *) die "unknown argument: $1" ;;
    esac
done

[[ -f "$old_apk" ]] || die "old APK is missing"
[[ -f "$new_apk" ]] || die "new APK is missing"
old_apk="$(realpath "$old_apk")"
new_apk="$(realpath "$new_apk")"
[[ "$old_apk" != "$new_apk" ]] || die "old and new APK paths must differ"

aapt_bin="${AAPT:-$(command -v aapt || true)}"
apksigner_bin="${APKSIGNER:-$(command -v apksigner || true)}"
[[ -n "$aapt_bin" ]] || die "aapt not found"
[[ -n "$apksigner_bin" ]] || die "apksigner not found"
command -v python3 >/dev/null 2>&1 || die "python3 is required"

apk_badging() {
    "$aapt_bin" dump badging "$1"
}

apk_identity() {
    local apk="$1" badging identity
    badging="$(apk_badging "$apk")" || return 1
    identity="$(
        sed -n "s/^package: name='$package_name' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1\t\2/p" \
            <<< "$badging"
    )"
    [[ "$(wc -l <<< "$identity" | tr -d ' ')" == "1" && -n "$identity" ]] || return 1
    printf '%s\n' "$identity"
}

apk_is_debuggable() {
    apk_badging "$1" | grep -qx 'application-debuggable'
}

verify_signature() {
    "$apksigner_bin" verify --verbose "$1" >/dev/null
}

signer_digest() {
    local apk="$1" output digest count
    output="$("$apksigner_bin" verify --print-certs "$apk")" || return 1
    count="$(grep -c '^Signer #1 certificate SHA-256 digest:' <<< "$output" || true)"
    [[ "$count" == "1" ]] || return 1
    digest="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$output")"
    [[ "$digest" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    printf '%s' "${digest,,}"
}

old_identity="$(apk_identity "$old_apk")" || die "old APK package identity is invalid"
IFS=$'\t' read -r old_code old_name <<< "$old_identity"
case "$old_code:$old_name" in
    5:0.1.4) old_tag="v0.1.4"; verify_root="migration-old-verify" ;;
    6:0.2.0) old_tag="v0.2.0"; verify_root="migration-old-verify" ;;
    7:0.2.1) old_tag="v0.2.1"; verify_root="migration-021-verify" ;;
    *) die "old APK must identify v0.1.4/vc5, v0.2.0/vc6, or v0.2.1/vc7" ;;
esac
new_identity="$(apk_identity "$new_apk")" || die "new APK package identity is invalid"
[[ "$new_identity" == $'8\t0.2.2' ]] || die "new APK must identify 0.2.2/vc8"
apk_is_debuggable "$old_apk" || die "historical fixture must be debuggable"
if apk_is_debuggable "$new_apk"; then
    die "new APK is debuggable; it is not the required assembleRelease candidate"
fi
verify_signature "$old_apk" || die "old APK signature verification failed"
verify_signature "$new_apk" || die "new APK signature verification failed"
old_signer="$(signer_digest "$old_apk")" || die "cannot inspect old APK public signer certificate"
new_signer="$(signer_digest "$new_apk")" || die "cannot inspect new APK public signer certificate"
[[ "$old_signer" == "$new_signer" ]] || die "APK public signers differ; in-place migration is impossible"
old_hash="$(sha256sum "$old_apk" | awk '{print $1}')"
new_hash="$(sha256sum "$new_apk" | awk '{print $1}')"
[[ "$old_hash" =~ ^[0-9a-f]{64}$ && "$new_hash" =~ ^[0-9a-f]{64}$ ]] ||
    die "could not hash APK inputs"

python3 "$repo_root/flows/lib/audit_migration_predecessor.py" \
    --repo "$repo_root" \
    --flow "$repo_root/flows/migration-predecessor-setup.yaml" >/dev/null ||
    die "historical text/content-description selector audit failed"

echo "PASS: $old_tag assembleDebug and vc8 assembleRelease identities/signatures match"
echo "old_apk_sha256=$old_hash"
echo "new_apk_sha256=$new_hash"
if ((preflight_only)); then
    exit 0
fi

device_serial="${MAESTRO_DEVICE_SERIAL:-}"
[[ -n "$device_serial" ]] || die "MAESTRO_DEVICE_SERIAL is required"
[[ "$device_serial" =~ ^[A-Za-z0-9._:-]+$ ]] || die "invalid MAESTRO_DEVICE_SERIAL"
adb_bin="${ADB:-$(command -v dadb || command -v adb || true)}"
[[ -n "$adb_bin" ]] || die "adb/dadb not found"
command -v timeout >/dev/null 2>&1 || die "GNU timeout is required"
adb_timeout_seconds="${MAESTRO_ADB_TIMEOUT_SECONDS:-30}"
adb_transfer_timeout_seconds="${MAESTRO_ADB_TRANSFER_TIMEOUT_SECONDS:-180}"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die "invalid ADB timeout"
[[ "$adb_transfer_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die "invalid ADB transfer timeout"

adb_host() {
    timeout --foreground --kill-after=5s "${adb_timeout_seconds}s" "$adb_bin" "$@"
}

adb_target() {
    timeout --foreground --kill-after=5s "${adb_timeout_seconds}s" \
        "$adb_bin" -s "$device_serial" "$@"
}

adb_target_transfer() {
    timeout --foreground --kill-after=5s "${adb_transfer_timeout_seconds}s" \
        "$adb_bin" -s "$device_serial" "$@"
}

capture_exact_device_unlocked() {
    local output_file="$1" error_file="$2" line
    local -a lock_lines=()
    if ! adb_target shell dumpsys trust > "$output_file" 2> "$error_file"; then
        return 1
    fi
    mapfile -t lock_lines < <(grep -E 'deviceLocked=' "$output_file" || true)
    ((${#lock_lines[@]} > 0)) || return 1
    for line in "${lock_lines[@]}"; do
        [[ "$line" =~ ^[[:space:]]*deviceLocked=0[[:space:]]*$ ]] || return 1
    done
}

device_state="$({ adb_host devices 2>/dev/null || true; } | awk -v serial="$device_serial" '$1==serial {print $2; exit}')"
[[ "$device_state" == "device" ]] || die "target is not attached in device state"
adb_target shell echo exact-device-ok >/dev/null || die "target did not answer"

evidence_parent="${MAESTRO_EVIDENCE_DIR:-${TMPDIR:-/tmp}/cruxcoach-maestro}"
repo_real="$(realpath -m "$repo_root")"
evidence_real="$(realpath -m "$evidence_parent")"
case "$evidence_real/" in
    "$repo_real/"*) die "evidence directory must be outside the repository" ;;
esac
run_id="${MAESTRO_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-matching-${old_name}-to-0.2.2}"
[[ "$run_id" =~ ^[A-Za-z0-9._-]{1,80}$ ]] || die "unsafe MAESTRO_RUN_ID"
wrapper_dir="$evidence_real/$run_id"
[[ ! -e "$wrapper_dir" ]] || die "evidence directory already exists: $wrapper_dir"
mkdir -p "$wrapper_dir/host"
if ! capture_exact_device_unlocked \
    "$wrapper_dir/host/device-lock-before.txt" \
    "$wrapper_dir/host/device-lock-before.err"; then
    die "target must report exact deviceLocked=0 before migration device actions"
fi

package_path() {
    adb_target shell pm path "$package_name" 2>/dev/null |
        tr -d '\r' | sed -n 's/^package://p' | head -1
}

verify_installed_vc8() {
    local prefix="$1" package_dump installed_path installed_hash raw_apk
    package_dump="$prefix-package.txt"
    raw_apk="$prefix-base.apk"
    adb_target shell dumpsys package "$package_name" > "$package_dump" 2>&1 || return 1
    grep -qE 'versionCode=8([[:space:]]|$)' "$package_dump" || return 1
    grep -qE 'versionName=0\.2\.2([[:space:]]|$)' "$package_dump" || return 1
    installed_path="$(package_path)"
    [[ -n "$installed_path" ]] || return 1
    adb_target_transfer exec-out cat "$installed_path" > "$raw_apk" 2> "$prefix-pull.err" || {
        rm -f "$raw_apk"
        return 1
    }
    installed_hash="$(sha256sum "$raw_apk" | awk '{print $1}')"
    rm -f "$raw_apk"
    printf 'package=%s\nversionCode=8\nversionName=0.2.2\ninstalled_apk_sha256=%s\nexpected_apk_sha256=%s\n' \
        "$package_name" "$installed_hash" "$new_hash" > "$prefix-identity.properties"
    [[ "$installed_hash" == "$new_hash" ]]
}

cleanup_required=0
cleanup_done=0
cleanup_app_state() {
    ((cleanup_required)) || return 0
    ((cleanup_done == 0)) || return 0
    local log="$wrapper_dir/host/final-cleanup-restore.txt"
    local clear_output="$wrapper_dir/host/pre-restore-pm-clear.txt"
    local final_clear="$wrapper_dir/host/final-pm-clear.txt"
    local restore_log="$wrapper_dir/host/restore-vc8.txt"
    local failed=0 installed_path=""
    : > "$log"
    : > "$restore_log"

    installed_path="$(package_path || true)"
    if [[ -n "$installed_path" ]]; then
        adb_target shell am force-stop "$package_name" >> "$log" 2>&1 || true
        if adb_target shell pm clear "$package_name" > "$clear_output" 2>&1 &&
            grep -qx 'Success' "$clear_output"; then
            echo "pre_restore_data=cleared" >> "$log"
        else
            echo "pre_restore_data=clear_failed" >> "$log"
            failed=1
        fi
    else
        echo "pre_restore_package=already_absent" >> "$log"
    fi

    if ! verify_installed_vc8 "$wrapper_dir/host/pre-restore-installed"; then
        installed_path="$(package_path || true)"
        if [[ -n "$installed_path" ]]; then
            adb_target uninstall "$package_name" >> "$restore_log" 2>&1 || true
        else
            echo "old_or_partial_package=already_absent" >> "$restore_log"
        fi
        if adb_target_transfer install "$new_apk" >> "$restore_log" 2>&1 &&
            grep -q 'Success' "$restore_log"; then
            echo "restore_install=PASS" >> "$log"
        else
            echo "restore_install=FAIL" >> "$log"
            failed=1
        fi
    else
        echo "restore_install=not_needed_exact_vc8_present" >> "$log"
    fi

    if verify_installed_vc8 "$wrapper_dir/host/restored-vc8"; then
        echo "restored_identity=PASS" >> "$log"
    else
        echo "restored_identity=FAIL" >> "$log"
        failed=1
    fi

    adb_target shell am force-stop "$package_name" >> "$final_clear" 2>&1 || true
    if adb_target shell pm clear "$package_name" >> "$final_clear" 2>&1 &&
        grep -qx 'Success' "$final_clear"; then
        echo "final_pm_clear=PASS" >> "$log"
    else
        echo "final_pm_clear=FAIL" >> "$log"
        failed=1
    fi
    if verify_installed_vc8 "$wrapper_dir/host/final-installed-vc8"; then
        echo "final_installed_identity=PASS" >> "$log"
    else
        echo "final_installed_identity=FAIL" >> "$log"
        failed=1
    fi

    ((failed == 0)) || return 1
    cleanup_done=1
    echo "cleanup_restore=PASS" >> "$log"
}

on_exit() {
    local status=$?
    trap - EXIT
    cleanup_app_state || status=1
    exit "$status"
}
trap on_exit EXIT
trap 'exit 130' INT TERM

python3 "$repo_root/flows/lib/audit_migration_predecessor.py" \
    --repo "$repo_root" \
    --flow "$repo_root/flows/migration-predecessor-setup.yaml" \
    > "$wrapper_dir/host/predecessor-selector-audit.json"
{
    echo "old_tag=$old_tag"
    echo "old_version_code=$old_code"
    echo "old_version_name=$old_name"
    echo "old_apk_sha256=$old_hash"
    echo "new_version_code=8"
    echo "new_version_name=0.2.2"
    echo "new_apk_sha256=$new_hash"
    echo "shared_public_signer_sha256=$old_signer"
    echo "old_fixture=unmodified-public-tag-assembleDebug"
    echo "old_selector_mode=visible-text-and-content-description-only"
    echo "new_candidate=current-workspace-assembleRelease-nondebuggable"
    echo "classification=matching-signer-schema-and-data-migration-only"
    echo "published_artifact_upgrade_proved=false"
} > "$wrapper_dir/host/preflight.properties"

# From this line onward every exit path must restore a freshly installed vc8.
# Set the trap gate before the first destructive uninstall, including the case
# where the predecessor install itself fails.
cleanup_required=1
echo "cleanup_required=set-before-first-uninstall" > "$wrapper_dir/host/cleanup-gate.txt"
adb_target uninstall "$package_name" > "$wrapper_dir/host/uninstall-current.txt" 2>&1 || true
adb_target_transfer install "$old_apk" > "$wrapper_dir/host/install-old.txt" 2>&1
grep -q 'Success' "$wrapper_dir/host/install-old.txt" || die "old APK install did not succeed"

MAESTRO_EVIDENCE_DIR="$wrapper_dir" \
MAESTRO_RUN_ID="old-setup" \
MAESTRO_MIGRATION_MARKER="$run_id" \
MAESTRO_DEVICE_SERIAL="$device_serial" \
ADB="$adb_bin" \
    "$repo_root/flows/run.sh" migration-predecessor-setup

adb_target logcat -b all -c >/dev/null 2>&1 || true
adb_target_transfer install -r "$new_apk" > "$wrapper_dir/host/install-new-in-place.txt" 2>&1
grep -q 'Success' "$wrapper_dir/host/install-new-in-place.txt" ||
    die "matching-signer in-place install did not succeed"
verify_installed_vc8 "$wrapper_dir/host/after-in-place" ||
    die "installed package is not the exact vc8 release candidate after upgrade"

MAESTRO_EVIDENCE_DIR="$wrapper_dir" \
MAESTRO_RUN_ID="new-verify" \
MAESTRO_MIGRATION_MARKER="$run_id" \
MAESTRO_DEVICE_SERIAL="$device_serial" \
ADB="$adb_bin" \
    "$repo_root/flows/run.sh" "$verify_root"

cleanup_app_state
echo "PASS: matching-signer $old_name to 0.2.2 schema/data migration; published upgrade remains unproved"
echo "Evidence: $wrapper_dir"
