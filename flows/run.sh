#!/usr/bin/env bash
# Deterministic Maestro runner for CruxCoach release validation.
#
# Each root flow is executed in its own Maestro invocation. This gives every
# flow an independently scoped JUnit report, Logcat buffer, process-exit audit,
# debug directory, final screenshot, and final hierarchy. Human-readable
# Maestro output is retained for diagnosis but is never used as pass evidence.

set -euo pipefail
umask 077

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly REPO_ROOT
readonly FLOWS_DIR="$REPO_ROOT/flows"
readonly LIB_DIR="$FLOWS_DIR/lib"
readonly SUITES_DIR="$FLOWS_DIR/suites"
readonly PACKAGE_NAME="com.cruxcoach.android"
export MAESTRO_CLI_NO_ANALYTICS=1

usage() {
    cat <<'EOF'
Usage:
  flows/run.sh [--suite NAME] [--repeat N]
  flows/run.sh [--repeat N] FLOW [FLOW ...]
  flows/run.sh --syntax-only

Required environment:
  MAESTRO_DEVICE_SERIAL   Exact ADB serial to target. No default is inferred.

Evidence environment:
  MAESTRO_EVIDENCE_DIR   Parent directory outside the repository.
                         Default: /tmp/cruxcoach-maestro
  MAESTRO_RUN_ID         Optional stable run directory name.
  MAESTRO_ADB_TIMEOUT_SECONDS
                         Bounded non-transfer ADB timeout (default 30).
  MAESTRO_ADB_TRANSFER_TIMEOUT_SECONDS
                         Bounded transfer/screencap timeout (default 180).
  MAESTRO_SYNTAX_TIMEOUT_SECONDS
                         Per-file check-syntax timeout (default 45).
  MAESTRO_TEST_TIMEOUT_SECONDS
                         Per-attempt Maestro test timeout (default 900).
  MAESTRO_HIERARCHY_TIMEOUT_SECONDS
                         Per-hierarchy timeout (default 45).
  MAESTRO_DRIVER_READY_TIMEOUT_SECONDS
                         Total readiness/backoff bound after reinstall (default 60).
  MAESTRO_STATE_RESTORE_TIMEOUT_SECONDS
                         Network-setting restore poll bound (default 45).
  MAESTRO_MIGRATION_MARKER
                         Public-safe run marker forwarded only to conditional
                         matching-debug migration roots.

Irreversible live Nostr suite:
  CRUXCOACH_ALLOW_NOSTR_LIVE=fresh-app-generated-only
                         Required only for --suite nostr-live. That suite is
                         hard-limited to one run and two prefixed DMs from a
                         freshly app-generated disposable identity.

Tool overrides:
  ADB                     adb-compatible executable (dadb is supported).
  MAESTRO                 Maestro executable (dmaestro is supported).

Suites are newline-delimited flow names in flows/suites/NAME.txt. The special
suite "all" discovers every top-level root flow. Comments and blank lines are
ignored. A normal assertion failure is never retried; only a narrow set of
known driver/ADB transport failures receives bounded, visible retries.
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

suite="release-gate"
repeat_count=1
syntax_only=0
declare -a requested_flows=()

while (($# > 0)); do
    case "$1" in
        --suite)
            (($# >= 2)) || die "--suite needs a value"
            suite="$2"
            shift 2
            ;;
        --suite=*)
            suite="${1#*=}"
            shift
            ;;
        --repeat)
            (($# >= 2)) || die "--repeat needs a value"
            repeat_count="$2"
            shift 2
            ;;
        --repeat=*)
            repeat_count="${1#*=}"
            shift
            ;;
        --syntax-only)
            syntax_only=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --*)
            die "unknown option: $1"
            ;;
        *)
            requested_flows+=("$1")
            shift
            ;;
    esac
done

[[ "$repeat_count" =~ ^[1-9][0-9]*$ ]] || die "--repeat must be a positive integer"

ADB_BIN="${ADB:-$(command -v dadb || command -v adb || true)}"
MAESTRO_BIN="${MAESTRO:-$(command -v dmaestro || command -v maestro || true)}"
[[ -n "$ADB_BIN" ]] || die "adb/dadb not found"
[[ -n "$MAESTRO_BIN" ]] || die "maestro/dmaestro not found"
command -v python3 >/dev/null 2>&1 || die "python3 is required for structured report checks"
command -v timeout >/dev/null 2>&1 || die "GNU timeout is required for bounded external commands"

ADB_COMMAND_TIMEOUT_SECONDS="${MAESTRO_ADB_TIMEOUT_SECONDS:-30}"
ADB_TRANSFER_TIMEOUT_SECONDS="${MAESTRO_ADB_TRANSFER_TIMEOUT_SECONDS:-180}"
MAESTRO_SYNTAX_TIMEOUT_SECONDS="${MAESTRO_SYNTAX_TIMEOUT_SECONDS:-45}"
MAESTRO_TEST_TIMEOUT_SECONDS="${MAESTRO_TEST_TIMEOUT_SECONDS:-900}"
MAESTRO_HIERARCHY_TIMEOUT_SECONDS="${MAESTRO_HIERARCHY_TIMEOUT_SECONDS:-45}"
MAESTRO_DRIVER_READY_TIMEOUT_SECONDS="${MAESTRO_DRIVER_READY_TIMEOUT_SECONDS:-60}"
STATE_RESTORE_TIMEOUT_SECONDS="${MAESTRO_STATE_RESTORE_TIMEOUT_SECONDS:-45}"
[[ "$ADB_COMMAND_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] ||
    die "MAESTRO_ADB_TIMEOUT_SECONDS must be a positive integer"
[[ "$ADB_TRANSFER_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] ||
    die "MAESTRO_ADB_TRANSFER_TIMEOUT_SECONDS must be a positive integer"
for timeout_value in \
    "$MAESTRO_SYNTAX_TIMEOUT_SECONDS" \
    "$MAESTRO_TEST_TIMEOUT_SECONDS" \
    "$MAESTRO_HIERARCHY_TIMEOUT_SECONDS" \
    "$MAESTRO_DRIVER_READY_TIMEOUT_SECONDS" \
    "$STATE_RESTORE_TIMEOUT_SECONDS"; do
    [[ "$timeout_value" =~ ^[1-9][0-9]*$ ]] ||
        die "all Maestro/readiness/restore timeouts must be positive integers"
done

adb_host() {
    timeout --foreground --kill-after=5s "${ADB_COMMAND_TIMEOUT_SECONDS}s" \
        "$ADB_BIN" "$@"
}

adb_target() {
    timeout --foreground --kill-after=5s "${ADB_COMMAND_TIMEOUT_SECONDS}s" \
        "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"
}

adb_target_transfer() {
    timeout --foreground --kill-after=5s "${ADB_TRANSFER_TIMEOUT_SECONDS}s" \
        "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"
}

capture_exact_device_unlocked() {
    local output_file="$1" error_file="$2" value
    local -a lock_values=()
    if ! adb_target shell dumpsys trust > "$output_file" 2> "$error_file"; then
        return 1
    fi
    # dumpsys trust reports deviceLocked inline in the per-user line, e.g.
    #   User "Owner" (id=0, ...): trustState=UNTRUSTED, ..., deviceLocked=0, ...
    # so the value is extracted per occurrence rather than per line. Every
    # reported value must be exactly 0; a missing or unparsable field yields no
    # occurrence and fails closed.
    mapfile -t lock_values < <(grep -oE 'deviceLocked=[^,[:space:]]+' "$output_file" || true)
    ((${#lock_values[@]} > 0)) || return 1
    for value in "${lock_values[@]}"; do
        [[ "$value" =~ ^deviceLocked=0$ ]] || return 1
    done
}

bounded_maestro() {
    local seconds="$1"
    shift
    timeout --foreground --kill-after=10s "${seconds}s" "$MAESTRO_BIN" "$@"
}

syntax_log=""
check_all_syntax() {
    local failed=0 flow
    while IFS= read -r -d '' flow; do
        if ! bounded_maestro "$MAESTRO_SYNTAX_TIMEOUT_SECONDS" \
            check-syntax "$flow" >>"$syntax_log" 2>&1; then
            echo "  syntax FAIL: ${flow#"$REPO_ROOT/"}" >&2
            failed=1
        fi
    done < <(
        find "$FLOWS_DIR" -type f -name '*.yaml' ! -name 'config.yaml' -print0 |
            sort -z
    )
    ((failed == 0)) || return 1
}

validate_suite_manifests() {
    local suite_file raw name existing
    local -a seen=()
    while IFS= read -r -d '' suite_file; do
        seen=()
        while IFS= read -r raw || [[ -n "$raw" ]]; do
            raw="${raw%%#*}"
            name="$(trim "$raw")"
            [[ -z "$name" ]] && continue
            [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] ||
                die "invalid flow name in ${suite_file#$REPO_ROOT/}: $name"
            [[ -f "$FLOWS_DIR/$name.yaml" ]] ||
                die "missing root named by ${suite_file#$REPO_ROOT/}: $name"
            for existing in "${seen[@]}"; do
                [[ "$existing" != "$name" ]] ||
                    die "duplicate root in ${suite_file#$REPO_ROOT/}: $name"
            done
            seen+=("$name")
        done < "$suite_file"
        ((${#seen[@]} > 0)) || die "suite manifest is empty: ${suite_file#$REPO_ROOT/}"
    done < <(find "$SUITES_DIR" -maxdepth 1 -type f -name '*.txt' -print0 | sort -z)
}

validate_suite_manifests

validate_identity_proofs() {
    local proof flow minimum root count token
    while IFS= read -r -d '' proof; do
        flow="${proof%.entity-proof}.yaml"
        [[ -f "$flow" ]] || die "entity-proof has no matching root: ${proof#$REPO_ROOT/}"
        minimum="$(tr -d '[:space:]' < "$proof")"
        [[ "$minimum" =~ ^[1-9][0-9]*$ ]] ||
            die "entity-proof must contain one positive integer: ${proof#$REPO_ROOT/}"
    done < <(find "$FLOWS_DIR" -maxdepth 1 -type f -name '*.entity-proof' -print0 | sort -z)
    while IFS= read -r -d '' proof; do
        flow="${proof%.deep-link-proof}.yaml"
        [[ -f "$flow" ]] || die "deep-link-proof has no matching root: ${proof#$REPO_ROOT/}"
        minimum="$(tr -d '[:space:]' < "$proof")"
        [[ "$minimum" =~ ^[1-9][0-9]*$ ]] ||
            die "deep-link-proof must contain one positive integer: ${proof#$REPO_ROOT/}"
    done < <(find "$FLOWS_DIR" -maxdepth 1 -type f -name '*.deep-link-proof' -print0 | sort -z)
    while IFS= read -r -d '' proof; do
        flow="${proof%.sort-proof}.yaml"
        [[ -f "$flow" ]] || die "sort-proof has no matching root: ${proof#$REPO_ROOT/}"
        token="$(tr -d '[:space:]' < "$proof")"
        [[ "$token" == "DESC_ASC_DESC" ]] ||
            die "sort-proof must contain DESC_ASC_DESC: ${proof#$REPO_ROOT/}"
    done < <(find "$FLOWS_DIR" -maxdepth 1 -type f -name '*.sort-proof' -print0 | sort -z)
    while IFS= read -r -d '' root; do
        count=0
        [[ -f "${root%.yaml}.entity-proof" ]] && count=$((count + 1))
        [[ -f "${root%.yaml}.deep-link-proof" ]] && count=$((count + 1))
        [[ -f "${root%.yaml}.sort-proof" ]] && count=$((count + 1))
        ((count <= 1)) || die "root has multiple identity-proof contracts: ${root#$REPO_ROOT/}"
    done < <(find "$FLOWS_DIR" -maxdepth 1 -type f -name '*.yaml' ! -name 'config.yaml' -print0 | sort -z)
}

validate_identity_proofs
contract_audit_args=(
    --flows-dir "$FLOWS_DIR"
    --release-suite "$SUITES_DIR/release-gate.txt"
    --nostr-suite "$SUITES_DIR/nostr-live.txt"
    --contracts "$FLOWS_DIR/state-contracts.tsv"
)
python3 "$LIB_DIR/audit_flow_contracts.py" "${contract_audit_args[@]}" >/dev/null ||
    die "flow state/tag contract audit failed"
python3 "$LIB_DIR/audit_migration_predecessor.py" \
    --repo "$REPO_ROOT" \
    --flow "$FLOWS_DIR/migration-predecessor-setup.yaml" >/dev/null ||
    die "historical migration selector audit failed"
python3 "$LIB_DIR/audit_runner_safety.py" --runner "$FLOWS_DIR/run.sh" >/dev/null ||
    die "runner fail-closed safety audit failed"
python3 "$LIB_DIR/audit_reboot_safety.py" \
    --runner "$FLOWS_DIR/run-reboot-updater.sh" >/dev/null ||
    die "reboot runner fail-closed safety audit failed"

if ((syntax_only)); then
    syntax_log="${TMPDIR:-/tmp}/cruxcoach-maestro-syntax-$$.log"
    trap 'rm -f "$syntax_log"' EXIT
    check_all_syntax || die "one or more Maestro files failed syntax validation"
    echo "PASS: bounded Maestro syntax plus state/tag/identity/migration-selector contracts are valid"
    exit 0
fi

declare -a flow_names=()
if ((${#requested_flows[@]} > 0)); then
    flow_names=("${requested_flows[@]}")
elif [[ "$suite" == "all" ]]; then
    while IFS= read -r -d '' flow; do
        flow_names+=("$(basename "$flow" .yaml)")
    done < <(
        find "$FLOWS_DIR" -maxdepth 1 -type f -name '*.yaml' ! -name 'config.yaml' -print0 |
            sort -z
    )
else
    suite_file="$SUITES_DIR/$suite.txt"
    [[ -f "$suite_file" ]] || die "suite not found: $suite_file"
    while IFS= read -r raw || [[ -n "$raw" ]]; do
        raw="${raw%%#*}"
        name="$(trim "$raw")"
        [[ -z "$name" ]] || flow_names+=("$name")
    done < "$suite_file"
fi

((${#flow_names[@]} > 0)) || die "no root flows selected"

is_nostr_live_flow() {
    case "$1" in
        nostr-dm-delivery|nostr-dm-force-stop) return 0 ;;
        *) return 1 ;;
    esac
}

is_non_replayable_transition() {
    case "$1" in
        migration-*-verify|upgrade-021-to-022|upgrade-old-to-022) return 0 ;;
        *) return 1 ;;
    esac
}

# NIP-17 DMs are intentional, recipient-visible external effects and cannot be
# recalled by the sender. Fail closed unless the operator selected the exact
# dedicated suite, acknowledged the fresh-app-generated identity boundary, and
# requested one run. Direct roots, --suite all, repeats, and reordered manifests
# are rejected before the device is contacted or Maestro can send anything.
nostr_live_selected=0
for name in "${flow_names[@]}"; do
    if is_nostr_live_flow "$name"; then
        nostr_live_selected=1
    fi
done
if ((nostr_live_selected)); then
    [[ "$suite" == "nostr-live" && ${#requested_flows[@]} -eq 0 ]] ||
        die "Nostr DM roots are executable only via --suite nostr-live"
    ((repeat_count == 1)) || die "the Nostr live suite cannot be repeated"
    [[ "${CRUXCOACH_ALLOW_NOSTR_LIVE:-}" == "fresh-app-generated-only" ]] ||
        die "set CRUXCOACH_ALLOW_NOSTR_LIVE=fresh-app-generated-only to authorize the two live DMs"
    ((${#flow_names[@]} == 3)) || die "nostr-live manifest must contain exactly three roots"
    [[ "${flow_names[0]}" == "release-fresh-onboarding" &&
       "${flow_names[1]}" == "nostr-dm-delivery" &&
       "${flow_names[2]}" == "nostr-dm-force-stop" ]] ||
        die "nostr-live manifest order or contents changed; refusing external writes"
fi

migration_marker="${MAESTRO_MIGRATION_MARKER:-}"
if [[ -n "$migration_marker" ]]; then
    [[ "$migration_marker" =~ ^[A-Za-z0-9._-]{1,80}$ ]] ||
        die "MAESTRO_MIGRATION_MARKER contains unsafe characters"
fi
for name in "${flow_names[@]}"; do
    if [[ "$name" == migration-* && -z "$migration_marker" ]]; then
        die "MAESTRO_MIGRATION_MARKER is required for matching-debug migration roots"
    fi
done

DEVICE_SERIAL="${MAESTRO_DEVICE_SERIAL:-}"
[[ -n "$DEVICE_SERIAL" ]] || die "MAESTRO_DEVICE_SERIAL is required"
[[ "$DEVICE_SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || die "invalid device serial"

device_state="$({ adb_host devices 2>/dev/null || true; } | awk -v serial="$DEVICE_SERIAL" '$1==serial {print $2; exit}')"
[[ "$device_state" == "device" ]] || die "target $DEVICE_SERIAL is not attached in device state (state=${device_state:-missing})"
adb_target shell echo exact-device-ok >/dev/null 2>&1 ||
    die "target $DEVICE_SERIAL did not answer an explicit shell command"

evidence_parent="${MAESTRO_EVIDENCE_DIR:-${TMPDIR:-/tmp}/cruxcoach-maestro}"
repo_real="$(realpath -m "$REPO_ROOT")"
evidence_real="$(realpath -m "$evidence_parent")"
case "$evidence_real/" in
    "$repo_real/"*) die "evidence directory must be outside the repository" ;;
esac

run_id="${MAESTRO_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$(git -C "$REPO_ROOT" rev-parse --short HEAD)}"
[[ "$run_id" =~ ^[A-Za-z0-9._-]+$ ]] || die "MAESTRO_RUN_ID contains unsafe characters"
run_dir="$evidence_real/$run_id"
[[ ! -e "$run_dir" ]] || die "evidence directory already exists: $run_dir"

mkdir -p "$run_dir"
if ! capture_exact_device_unlocked \
    "$run_dir/device-lock-initial.txt" "$run_dir/device-lock-initial.err"; then
    die "target $DEVICE_SERIAL must report exact deviceLocked=0; physically unlock it before UI execution"
fi
syntax_log="$run_dir/syntax.log"
python3 "$LIB_DIR/audit_flow_contracts.py" "${contract_audit_args[@]}" \
    > "$run_dir/flow-contract-audit.json"
python3 "$LIB_DIR/audit_migration_predecessor.py" \
    --repo "$REPO_ROOT" \
    --flow "$FLOWS_DIR/migration-predecessor-setup.yaml" \
    > "$run_dir/migration-predecessor-selector-audit.json"
python3 "$LIB_DIR/audit_runner_safety.py" --runner "$FLOWS_DIR/run.sh" \
    > "$run_dir/runner-safety-audit.json"
python3 "$LIB_DIR/audit_reboot_safety.py" \
    --runner "$FLOWS_DIR/run-reboot-updater.sh" \
    > "$run_dir/reboot-runner-safety-audit.json"

for name in "${flow_names[@]}"; do
    [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid flow name: $name"
    [[ -f "$FLOWS_DIR/$name.yaml" ]] || die "flow not found: flows/$name.yaml"
    if ! bounded_maestro "$MAESTRO_SYNTAX_TIMEOUT_SECONDS" \
        check-syntax "$FLOWS_DIR/$name.yaml" >> "$syntax_log" 2>&1; then
        die "syntax validation failed for flows/$name.yaml; see $syntax_log"
    fi
done

# Capture the small set of device settings/permissions these release flows can
# modify. The EXIT handler restores them even after an interrupted assertion.
# All values are non-secret OS state and remain in the external evidence tree.
readonly -a TRACKED_PERMISSIONS=(
    android.permission.POST_NOTIFICATIONS
    android.permission.BLUETOOTH_SCAN
    android.permission.BLUETOOTH_CONNECT
    android.permission.BLUETOOTH_ADVERTISE
    android.permission.ACCESS_FINE_LOCATION
    android.permission.ACCESS_COARSE_LOCATION
)

permission_grant_state() {
    local permission="$1"
    adb_target shell dumpsys package "$PACKAGE_NAME" 2>/dev/null |
        awk -v target="$permission:" '
            $1 == target {
                for (i = 2; i <= NF; i++) {
                    if ($i ~ /^granted=(true|false),?$/) {
                        sub(/^granted=/, "", $i)
                        sub(/,$/, "", $i)
                        print $i
                        exit
                    }
                }
            }
        '
}

setting_value() {
    local namespace="$1" key="$2"
    adb_target shell settings get "$namespace" "$key" 2>/dev/null |
        tr -d '\r' | head -1
}

baseline_accelerometer_rotation="$(setting_value system accelerometer_rotation)"
baseline_user_rotation="$(setting_value system user_rotation)"
baseline_wifi="$(setting_value global wifi_on)"
baseline_mobile_data="$(setting_value global mobile_data)"
baseline_locale="$(adb_target shell getprop persist.sys.locale 2>/dev/null | tr -d '\r')"
declare -A baseline_permission_state=()
for permission in "${TRACKED_PERMISSIONS[@]}"; do
    baseline_permission_state["$permission"]="$(permission_grant_state "$permission")"
done

capture_device_state() {
    local destination="$1" permission
    {
        echo "accelerometer_rotation=$(setting_value system accelerometer_rotation)"
        echo "user_rotation=$(setting_value system user_rotation)"
        echo "wifi_on=$(setting_value global wifi_on)"
        echo "mobile_data=$(setting_value global mobile_data)"
        echo "locale=$(adb_target shell getprop persist.sys.locale 2>/dev/null | tr -d '\r')"
        for permission in "${TRACKED_PERMISSIONS[@]}"; do
            echo "permission.$permission=$(permission_grant_state "$permission")"
        done
    } > "$destination"
}
capture_device_state "$run_dir/device-state-before.txt"

wait_for_setting() {
    local namespace="$1" key="$2" expected="$3" log="$4"
    local started=$SECONDS delay_seconds=1 current elapsed remaining
    while :; do
        current="$(setting_value "$namespace" "$key")"
        elapsed=$((SECONDS - started))
        printf 'poll namespace=%s key=%s expected=%s actual=%s elapsed_seconds=%d\n' \
            "$namespace" "$key" "$expected" "$current" "$elapsed" >> "$log"
        [[ "$current" == "$expected" ]] && return 0
        ((elapsed < STATE_RESTORE_TIMEOUT_SECONDS)) || return 1
        remaining=$((STATE_RESTORE_TIMEOUT_SECONDS - elapsed))
        ((delay_seconds <= remaining)) || delay_seconds=$remaining
        ((delay_seconds > 0)) || return 1
        sleep "$delay_seconds"
        ((delay_seconds < 4)) && delay_seconds=$((delay_seconds * 2))
        ((delay_seconds > 4)) && delay_seconds=4
    done
}

nostr_live_identity_created_or_possible=0
nostr_live_identity_cleanup_done=0
if ((nostr_live_selected)); then
    {
        printf 'timestamp_utc\tflow\tidentity_alias\teffect\tstatus\n'
        printf '%s\tnostr-dm-delivery\tapp-generated-disposable\tone prefixed encrypted DM; sender recall unavailable\tPLANNED\n' \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf '%s\tnostr-dm-force-stop\tapp-generated-disposable\tone prefixed encrypted DM; sender recall unavailable\tPLANNED\n' \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } > "$run_dir/external-effects.tsv"
fi

# Root flows that consume or create a shared-storage test file register the
# exact path here. There are no globs or broad Downloads cleanups: each file is
# pre-cleaned or checksum-staged once, removed by its post-hook when possible,
# and removed again by this EXIT safety net after an interrupted run.
declare -a managed_remote_files=()
register_managed_remote_file() {
    local candidate="$1" existing
    for existing in "${managed_remote_files[@]}"; do
        [[ "$existing" == "$candidate" ]] && return 0
    done
    managed_remote_files+=("$candidate")
}
cleanup_managed_remote_files() {
    local remote cleanup_log="$run_dir/managed-file-cleanup.txt" failed=0
    ((${#managed_remote_files[@]} > 0)) || return 0
    : > "$cleanup_log"
    for remote in "${managed_remote_files[@]}"; do
        adb_target shell rm -f "$remote" \
            >> "$cleanup_log" 2>&1 || true
        if adb_target shell test ! -e "$remote"; then
            echo "verified_absent=$remote" >> "$cleanup_log"
        else
            echo "WARNING: managed test file still exists: $remote" >> "$cleanup_log"
            failed=1
        fi
    done
    ((failed == 0))
}

restore_device_state() {
    local failed=0 current permission desired state_log="$run_dir/device-state-restore.txt"
    : > "$state_log"
    capture_device_state "$run_dir/device-state-pre-restore.txt"

    if [[ "$baseline_accelerometer_rotation" =~ ^[01]$ ]]; then
        adb_target shell settings put system accelerometer_rotation \
            "$baseline_accelerometer_rotation" >> "$state_log" 2>&1 || failed=1
    fi
    if [[ "$baseline_user_rotation" =~ ^[0-3]$ ]]; then
        adb_target shell settings put system user_rotation \
            "$baseline_user_rotation" >> "$state_log" 2>&1 || failed=1
    fi

    current="$(setting_value global wifi_on)"
    if [[ "$baseline_wifi" =~ ^[01]$ && "$current" != "$baseline_wifi" ]]; then
        if [[ "$baseline_wifi" == "1" ]]; then
            adb_target shell svc wifi enable >> "$state_log" 2>&1 || failed=1
        else
            adb_target shell svc wifi disable >> "$state_log" 2>&1 || failed=1
        fi
    fi
    current="$(setting_value global mobile_data)"
    if [[ "$baseline_mobile_data" =~ ^[01]$ && "$current" != "$baseline_mobile_data" ]]; then
        if [[ "$baseline_mobile_data" == "1" ]]; then
            adb_target shell svc data enable >> "$state_log" 2>&1 || failed=1
        else
            adb_target shell svc data disable >> "$state_log" 2>&1 || failed=1
        fi
    fi

    # Network service toggles are asynchronous. Reach the baseline under a
    # bounded backoff before final capture and byte-for-byte state comparison.
    if [[ "$baseline_wifi" =~ ^[01]$ ]]; then
        wait_for_setting global wifi_on "$baseline_wifi" "$state_log" || failed=1
    fi
    if [[ "$baseline_mobile_data" =~ ^[01]$ ]]; then
        wait_for_setting global mobile_data "$baseline_mobile_data" "$state_log" || failed=1
    fi

    for permission in "${TRACKED_PERMISSIONS[@]}"; do
        desired="${baseline_permission_state[$permission]}"
        [[ "$desired" == "true" || "$desired" == "false" ]] || continue
        current="$(permission_grant_state "$permission")"
        [[ "$current" == "$desired" ]] && continue
        if [[ "$desired" == "true" ]]; then
            adb_target shell pm grant "$PACKAGE_NAME" "$permission" \
                >> "$state_log" 2>&1 || failed=1
        else
            adb_target shell pm revoke "$PACKAGE_NAME" "$permission" \
                >> "$state_log" 2>&1 || failed=1
        fi
    done

    capture_device_state "$run_dir/device-state-after.txt"
    {
        echo "expected.accelerometer_rotation=$baseline_accelerometer_rotation"
        echo "actual.accelerometer_rotation=$(setting_value system accelerometer_rotation)"
        echo "expected.user_rotation=$baseline_user_rotation"
        echo "actual.user_rotation=$(setting_value system user_rotation)"
        echo "expected.wifi_on=$baseline_wifi"
        echo "actual.wifi_on=$(setting_value global wifi_on)"
        echo "expected.mobile_data=$baseline_mobile_data"
        echo "actual.mobile_data=$(setting_value global mobile_data)"
        echo "expected.locale=$baseline_locale"
        echo "actual.locale=$(adb_target shell getprop persist.sys.locale 2>/dev/null | tr -d '\r')"
        for permission in "${TRACKED_PERMISSIONS[@]}"; do
            echo "expected.permission.$permission=${baseline_permission_state[$permission]}"
            echo "actual.permission.$permission=$(permission_grant_state "$permission")"
        done
    } > "$run_dir/device-state-verification.txt"

    cmp -s "$run_dir/device-state-before.txt" "$run_dir/device-state-after.txt" || failed=1
    if ((failed)); then
        echo "restore=FAIL" >> "$state_log"
        return 1
    fi
    echo "restore=PASS" >> "$state_log"
}

cleanup_nostr_live_identity() {
    local cleanup_log="$run_dir/nostr-live-identity-cleanup.txt"
    ((nostr_live_selected)) || return 0
    ((nostr_live_identity_created_or_possible)) || return 0
    ((nostr_live_identity_cleanup_done == 0)) || return 0
    : > "$cleanup_log"
    adb_target shell am force-stop "$PACKAGE_NAME" \
        >> "$cleanup_log" 2>&1 || true
    if adb_target shell pm clear "$PACKAGE_NAME" \
        >> "$cleanup_log" 2>&1 && grep -q 'Success' "$cleanup_log"; then
        echo "verified=exact-package-app-data-cleared-without-reading-identity" >> "$cleanup_log"
        printf '%s\tCLEANUP\tapp-generated-disposable\tlocal identity/app data cleared\tPASS\n' \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$run_dir/external-effects.tsv"
        nostr_live_identity_cleanup_done=1
        return 0
    fi
    echo "cleanup=FAIL" >> "$cleanup_log"
    printf '%s\tCLEANUP\tapp-generated-disposable\tlocal identity/app data clear failed\tFAIL\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$run_dir/external-effects.tsv"
    return 1
}

cleanup_done=0
perform_cleanup() {
    local failed=0
    ((cleanup_done == 0)) || return 0
    cleanup_managed_remote_files || failed=1
    cleanup_nostr_live_identity || failed=1
    restore_device_state || failed=1
    if ((failed)); then
        return 1
    fi
    cleanup_done=1
}
on_exit() {
    local original_status=$?
    trap - EXIT
    if ! perform_cleanup; then
        original_status=1
    fi
    exit "$original_status"
}
trap on_exit EXIT
trap 'exit 130' INT TERM

legacy_fixture_staged=0
missing_catalog_fixture_staged=0
roundtrip_remote="/sdcard/Download/cruxcoach-e2e-roundtrip.json"
for name in "${flow_names[@]}"; do
    if [[ "$name" == "backup-import-legacy" ]] && ((legacy_fixture_staged == 0)); then
        legacy_fixture="$FLOWS_DIR/fixtures/cruxcoach-legacy-014.json"
        legacy_remote="/sdcard/Download/cruxcoach-e2e-legacy-014.json"
        register_managed_remote_file "$legacy_remote"
        python3 "$LIB_DIR/validate_backup_fixture.py" "$legacy_fixture" \
            > "$run_dir/fixture-validation.txt"
        legacy_fixture_staged=1
        adb_target_transfer push "$legacy_fixture" "$legacy_remote" \
            > "$run_dir/fixture-stage.txt" 2>&1 || die "could not stage legacy backup fixture"
        local_hash="$(sha256sum "$legacy_fixture" | awk '{print $1}')"
        remote_hash="$(
            adb_target shell sha256sum "$legacy_remote" |
                tr -d '\r' | awk '{print $1}'
        )"
        [[ "$remote_hash" == "$local_hash" ]] || die "staged legacy fixture checksum mismatch"
        printf 'sha256=%s\nremote=%s\n' "$local_hash" "$legacy_remote" \
            >> "$run_dir/fixture-stage.txt"
    fi
    if [[ "$name" == "lists-missing-catalogue" ]] &&
        ((missing_catalog_fixture_staged == 0)); then
        missing_catalog_fixture="$FLOWS_DIR/fixtures/cruxcoach-missing-catalogue.json"
        missing_catalog_remote="/sdcard/Download/cruxcoach-e2e-missing-catalogue.json"
        register_managed_remote_file "$missing_catalog_remote"
        python3 "$LIB_DIR/validate_missing_catalog_fixture.py" "$missing_catalog_fixture" \
            > "$run_dir/missing-catalog-fixture-validation.json"
        missing_catalog_fixture_staged=1
        adb_target_transfer push "$missing_catalog_fixture" \
            "$missing_catalog_remote" \
            > "$run_dir/missing-catalog-fixture-stage.txt" 2>&1 ||
            die "could not stage missing-catalogue backup fixture"
        local_hash="$(sha256sum "$missing_catalog_fixture" | awk '{print $1}')"
        remote_hash="$(
            adb_target shell sha256sum "$missing_catalog_remote" |
                tr -d '\r' | awk '{print $1}'
        )"
        [[ "$remote_hash" == "$local_hash" ]] ||
            die "staged missing-catalogue fixture checksum mismatch"
        printf 'sha256=%s\nremote=%s\n' "$local_hash" "$missing_catalog_remote" \
            >> "$run_dir/missing-catalog-fixture-stage.txt"
    fi
    if [[ "$name" == "backup-json-roundtrip" ]]; then
        register_managed_remote_file "$roundtrip_remote"
        adb_target shell rm -f "$roundtrip_remote" \
            > "$run_dir/roundtrip-file-setup.txt" 2>&1 ||
            die "could not pre-clean exact round-trip backup path"
        if ! adb_target shell test ! -e "$roundtrip_remote"; then
            die "round-trip backup path still exists after exact pre-clean"
        fi
        echo "verified_absent=$roundtrip_remote" >> "$run_dir/roundtrip-file-setup.txt"
    fi
done

{
    echo "run_id=$run_id"
    echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_head=$(git -C "$REPO_ROOT" rev-parse HEAD)"
    echo "git_branch=$(git -C "$REPO_ROOT" branch --show-current)"
    echo "device_serial=$DEVICE_SERIAL"
    echo "package=$PACKAGE_NAME"
    echo "suite=$suite"
    echo "repeat_count=$repeat_count"
    echo "flows=${flow_names[*]}"
    echo "maestro_version=$(bounded_maestro 30 --version 2>/dev/null | head -1)"
    if [[ -n "$migration_marker" ]]; then
        echo "migration_marker=$migration_marker"
    fi
} > "$run_dir/run.properties"
if ((nostr_live_selected)); then
    {
        echo "nostr_live_identity=fresh-app-generated-disposable-only"
        echo "nostr_live_dm_count=2"
        echo "nostr_live_repeat_allowed=false"
    } >> "$run_dir/run.properties"
fi
git -C "$REPO_ROOT" status --short --branch > "$run_dir/git-status.txt"
{
    echo "model=$(adb_target shell getprop ro.product.model | tr -d '\r')"
    echo "android_release=$(adb_target shell getprop ro.build.version.release | tr -d '\r')"
    echo "api=$(adb_target shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "locale=$(adb_target shell getprop persist.sys.locale | tr -d '\r')"
    adb_target shell dumpsys package "$PACKAGE_NAME" 2>/dev/null |
        sed -n '/versionCode=/p;/versionName=/p' | head -4 | tr -d '\r'
} > "$run_dir/device.properties"

printf 'repeat\tsequence\tflow\tresult\tmaestro_exit\tinfrastructure_retries\tevidence\n' > "$run_dir/results.tsv"

is_infrastructure_failure() {
    local output_file="$1"
    grep -qE \
        'EOFException|StatusRuntimeException: UNAVAILABLE|Command failed \(tcp:[0-9]+\): closed|startInstrumentationSession|Unable to start instrumentation|Failed to install apk /tmp/maestro-app|installMaestroDriverApp|Connection reset|Broken pipe|device .*offline|device .*not found' \
        "$output_file"
}

wait_for_maestro_driver() {
    local log="$1" started=$SECONDS delay_seconds=1 elapsed remaining call_timeout status
    : > "$log"
    while :; do
        elapsed=$((SECONDS - started))
        ((elapsed < MAESTRO_DRIVER_READY_TIMEOUT_SECONDS)) || return 1
        remaining=$((MAESTRO_DRIVER_READY_TIMEOUT_SECONDS - elapsed))
        call_timeout=$MAESTRO_HIERARCHY_TIMEOUT_SECONDS
        ((call_timeout <= remaining)) || call_timeout=$remaining
        printf 'readiness_attempt elapsed_seconds=%d call_timeout_seconds=%d\n' \
            "$elapsed" "$call_timeout" >> "$log"
        set +e
        ANDROID_SERIAL="$DEVICE_SERIAL" bounded_maestro "$call_timeout" \
            hierarchy --no-ansi --no-reinstall-driver >> "$log" 2>&1
        status=$?
        set -e
        if ((status == 0)); then
            echo "driver_readiness=PASS" >> "$log"
            return 0
        fi
        elapsed=$((SECONDS - started))
        ((elapsed < MAESTRO_DRIVER_READY_TIMEOUT_SECONDS)) || return 1
        remaining=$((MAESTRO_DRIVER_READY_TIMEOUT_SECONDS - elapsed))
        ((delay_seconds <= remaining)) || delay_seconds=$remaining
        ((delay_seconds > 0)) || return 1
        sleep "$delay_seconds"
        if ((delay_seconds < 8)); then delay_seconds=$((delay_seconds * 2)); fi
        if ((delay_seconds > 8)); then delay_seconds=8; fi
    done
}

verify_expectations() {
    local flow_name="$1" logcat_file="$2" result_file="$3"
    local expects="$FLOWS_DIR/$flow_name.expects" failed=0 pattern regex count minimum
    : > "$result_file"
    [[ -f "$expects" ]] || return 0
    while IFS= read -r pattern || [[ -n "$pattern" ]]; do
        [[ -z "$pattern" || "$pattern" =~ ^[[:space:]]*# ]] && continue
        if [[ "$pattern" == '!'* ]]; then
            regex="${pattern:1}"
            if grep -qE -- "$regex" "$logcat_file"; then
                printf 'FAIL\tabsent: %s\n' "$regex" >> "$result_file"
                failed=1
            else
                printf 'PASS\tabsent: %s\n' "$regex" >> "$result_file"
            fi
        elif [[ "$pattern" =~ ^([1-9][0-9]*)\+::(.*)$ ]]; then
            minimum="${BASH_REMATCH[1]}"
            regex="${BASH_REMATCH[2]}"
            count="$(grep -cE -- "$regex" "$logcat_file" || true)"
            if ((count >= minimum)); then
                printf 'PASS\tcount=%d minimum=%d: %s\n' "$count" "$minimum" "$regex" >> "$result_file"
            else
                printf 'FAIL\tcount=%d minimum=%d: %s\n' "$count" "$minimum" "$regex" >> "$result_file"
                failed=1
            fi
        elif [[ "$pattern" =~ ^([0-9]+)::(.*)$ ]]; then
            local exact="${BASH_REMATCH[1]}"
            regex="${BASH_REMATCH[2]}"
            count="$(grep -cE -- "$regex" "$logcat_file" || true)"
            if ((count == exact)); then
                printf 'PASS\tcount=%d exact=%d: %s\n' "$count" "$exact" "$regex" >> "$result_file"
            else
                printf 'FAIL\tcount=%d exact=%d: %s\n' "$count" "$exact" "$regex" >> "$result_file"
                failed=1
            fi
        elif grep -qE -- "$pattern" "$logcat_file"; then
            printf 'PASS\t%s\n' "$pattern" >> "$result_file"
        else
            printf 'FAIL\t%s\n' "$pattern" >> "$result_file"
            failed=1
        fi
    done < "$expects"
    ((failed == 0))
}

record_evidence_capture() {
    local ledger="$1" artifact="$2" status="$3" detail="$4"
    printf '%s\t%s\t%s\n' "$artifact" "$status" "$detail" >> "$ledger"
}

overall_failed=0
sequence=0
abort_nostr_suite=0
last_flow_result=""

run_flow() {
    local repeat_no="$1" flow_name="$2"
    local flow_file="$FLOWS_DIR/$flow_name.yaml"
    local flow_dir
    flow_dir="$run_dir/repeat-$(printf '%02d' "$repeat_no")/$(printf '%03d' "$sequence")-$flow_name"
    local attempt=1 max_attempts=3 infrastructure_retries=0
    local maestro_exit=1 final_attempt_dir="" junit_ok=1 junit_html_ok=1
    local crash_ok=1 expects_ok=1 expects_required=0 evidence_ok=1
    local pre_maestro_capture_ok=1 maestro_started=0
    local identity_ok=1 identity_required=0
    local post_hook_ok=1 post_hook_required=0
    if is_nostr_live_flow "$flow_name"; then
        # A transport retry after the tap could duplicate an irreversible DM.
        # Preserve the first attempt's evidence and fail without replaying it.
        max_attempts=1
    elif is_non_replayable_transition "$flow_name"; then
        # Explicit dismissal advances persistent one-time migration state.
        # Replaying after a transport failure would test a different state.
        max_attempts=1
    fi
    mkdir -p "$flow_dir"
    local capture_ledger="$flow_dir/evidence-capture.tsv"
    printf 'artifact\tstatus\tdetail\n' > "$capture_ledger"

    if capture_exact_device_unlocked \
        "$flow_dir/device-lock-before.txt" "$flow_dir/device-lock-before.err"; then
        record_evidence_capture "$capture_ledger" device-unlocked-before PASS exact-deviceLocked=0
    else
        record_evidence_capture "$capture_ledger" device-unlocked-before FAIL missing-conflicting-or-nonzero-deviceLocked
        evidence_ok=0
        pre_maestro_capture_ok=0
    fi
    if adb_target shell dumpsys activity exit-info "$PACKAGE_NAME" \
        > "$flow_dir/exit-info-before.txt" 2> "$flow_dir/exit-info-before.err"; then
        record_evidence_capture "$capture_ledger" exit-info-before PASS command-succeeded
    else
        record_evidence_capture "$capture_ledger" exit-info-before FAIL command-failed
        evidence_ok=0
        pre_maestro_capture_ok=0
    fi
    if [[ -s "$flow_dir/exit-info-before.txt" ]]; then
        record_evidence_capture "$capture_ledger" exit-info-before-nonempty PASS nonempty
    else
        record_evidence_capture "$capture_ledger" exit-info-before-nonempty FAIL empty-or-missing
        evidence_ok=0
        pre_maestro_capture_ok=0
    fi
    if adb_target logcat -b all -c \
        > "$flow_dir/logcat-clear.txt" 2> "$flow_dir/logcat-clear.err"; then
        record_evidence_capture "$capture_ledger" logcat-clear PASS command-succeeded
    else
        record_evidence_capture "$capture_ledger" logcat-clear FAIL command-failed-stale-log-risk
        evidence_ok=0
        pre_maestro_capture_ok=0
    fi

    if ((pre_maestro_capture_ok == 0)); then
        final_attempt_dir="$flow_dir/attempt-0-not-started"
        mkdir -p "$final_attempt_dir"
        maestro_exit=125
        printf 'Maestro not started: mandatory pre-root evidence capture failed.\n' \
            > "$final_attempt_dir/maestro-not-started.txt"
        if is_nostr_live_flow "$flow_name"; then
            abort_nostr_suite=1
        fi
    fi

    while ((pre_maestro_capture_ok && attempt <= max_attempts)); do
        local attempt_dir="$flow_dir/attempt-$attempt"
        local report="$attempt_dir/junit.xml"
        local console="$attempt_dir/maestro-console.log"
        mkdir -p "$attempt_dir/debug" "$attempt_dir/test-output"

        declare -a args=(
            test
            --no-ansi
            --udid "$DEVICE_SERIAL"
            --config "$FLOWS_DIR/config.yaml"
            --format JUNIT
            --output "$report"
            --debug-output "$attempt_dir/debug"
            --flatten-debug-output
            --test-output-dir "$attempt_dir/test-output"
            --test-suite-name "CruxCoach-$flow_name"
            --env "E2E_RUN_ID=${run_id}-r${repeat_no}-s${sequence}"
        )
        if [[ -n "$migration_marker" && "$flow_name" == migration-* ]]; then
            args+=(--env "E2E_MIGRATION_MARKER=$migration_marker")
        fi
        # Attempt 2 performs the one permitted driver reinstall. Attempt 3
        # reuses that freshly installed driver: reinstalling again can race
        # Android's instrumentation teardown and reproduce the same transport
        # failure the retry is meant to recover from.
        if ((attempt == 2)); then
            args+=(--reinstall-driver)
        else
            args+=(--no-reinstall-driver)
        fi
        args+=("$flow_file")

        set +e
        maestro_started=1
        if is_nostr_live_flow "$flow_name"; then
            nostr_live_identity_created_or_possible=1
        fi
        ANDROID_SERIAL="$DEVICE_SERIAL" bounded_maestro "$MAESTRO_TEST_TIMEOUT_SECONDS" \
            "${args[@]}" > "$console" 2>&1
        maestro_exit=$?
        set -e
        final_attempt_dir="$attempt_dir"

        if ((maestro_exit != 0)) &&
            { is_infrastructure_failure "$console" ||
                { [[ -f "$report" ]] && is_infrastructure_failure "$report"; }; } &&
            ((attempt < max_attempts)); then
            infrastructure_retries=$((infrastructure_retries + 1))
            printf 'infrastructure retry %d after known driver/ADB transport failure\n' \
                "$infrastructure_retries" >> "$flow_dir/retries.log"
            if ((attempt == 2)); then
                if ! wait_for_maestro_driver "$flow_dir/driver-readiness-after-reinstall.log"; then
                    echo "driver readiness did not recover within the bounded backoff" \
                        >> "$flow_dir/retries.log"
                    break
                fi
            fi
            attempt=$((attempt + 1))
            continue
        fi
        break
    done

    if adb_target logcat -b all -d -v epoch \
        'PERF:V' 'AndroidRuntime:V' 'ActivityManager:W' 'ActivityTaskManager:W' \
        'DataExchangeVM:V' \
        'MessageDeliveryCoord:V' 'OfflineQueueManager:V' 'NostrMessageSender:V' \
        'UpdateChecker:V' 'UpdateCheckWorker:V' 'UpdaterCoordinator:V' \
        'SQLiteLog:V' 'SQLDelight:V' 'Database:V' \
        'libc:V' 'DEBUG:V' '*:S' \
        > "$flow_dir/logcat-safe.txt" 2> "$flow_dir/logcat-dump.err"; then
        record_evidence_capture "$capture_ledger" logcat-dump PASS command-succeeded
    else
        record_evidence_capture "$capture_ledger" logcat-dump FAIL command-failed
        evidence_ok=0
    fi
    if [[ -s "$flow_dir/logcat-safe.txt" ]]; then
        record_evidence_capture "$capture_ledger" logcat-dump-nonempty PASS nonempty
    else
        record_evidence_capture "$capture_ledger" logcat-dump-nonempty FAIL empty-or-missing
        evidence_ok=0
    fi
    if adb_target shell dumpsys activity exit-info "$PACKAGE_NAME" \
        > "$flow_dir/exit-info-after.txt" 2> "$flow_dir/exit-info-after.err"; then
        record_evidence_capture "$capture_ledger" exit-info-after PASS command-succeeded
    else
        record_evidence_capture "$capture_ledger" exit-info-after FAIL command-failed
        evidence_ok=0
    fi
    if [[ -s "$flow_dir/exit-info-after.txt" ]]; then
        record_evidence_capture "$capture_ledger" exit-info-after-nonempty PASS nonempty
    else
        record_evidence_capture "$capture_ledger" exit-info-after-nonempty FAIL empty-or-missing
        evidence_ok=0
    fi

    if ((pre_maestro_capture_ok)); then
        if ANDROID_SERIAL="$DEVICE_SERIAL" bounded_maestro "$MAESTRO_HIERARCHY_TIMEOUT_SECONDS" \
            hierarchy --no-ansi --no-reinstall-driver \
            > "$flow_dir/final-maestro-hierarchy.txt" 2> "$flow_dir/final-maestro-hierarchy.err"; then
            record_evidence_capture "$capture_ledger" maestro-hierarchy-command PASS command-succeeded
        else
            record_evidence_capture "$capture_ledger" maestro-hierarchy-command FAIL command-failed-or-timed-out
            evidence_ok=0
        fi
    else
        : > "$flow_dir/final-maestro-hierarchy.txt"
        printf 'not attempted: mandatory pre-root gate failed\n' \
            > "$flow_dir/final-maestro-hierarchy.err"
        record_evidence_capture "$capture_ledger" maestro-hierarchy-command FAIL not-attempted-pre-root-gate
        evidence_ok=0
    fi
    if [[ -s "$flow_dir/final-maestro-hierarchy.txt" ]]; then
        record_evidence_capture "$capture_ledger" maestro-hierarchy-nonempty PASS nonempty
    else
        record_evidence_capture "$capture_ledger" maestro-hierarchy-nonempty FAIL empty
        evidence_ok=0
    fi
    # Do not launch Android's separate `uiautomator dump` client here. It
    # competes with Maestro's instrumentation for UiAutomation ownership and
    # can invalidate the just-primed driver before the next root. Maestro's
    # hierarchy is the single accessibility-tree artifact; screencap is a
    # separate bounded framebuffer read and does not acquire UiAutomation.
    if adb_target_transfer exec-out screencap -p \
        > "$flow_dir/final-screen.png" 2> "$flow_dir/final-screen.err"; then
        record_evidence_capture "$capture_ledger" screencap-command PASS command-succeeded
    else
        record_evidence_capture "$capture_ledger" screencap-command FAIL command-failed-or-timed-out
        evidence_ok=0
    fi
    if python3 "$LIB_DIR/verify_png_orientation.py" "$flow_dir/final-screen.png" \
        > "$flow_dir/screencap-validation.json"; then
        record_evidence_capture "$capture_ledger" screencap-png-validation PASS complete-valid-png
    else
        record_evidence_capture "$capture_ledger" screencap-png-validation FAIL invalid-empty-or-truncated-png
        evidence_ok=0
    fi
    if ! python3 "$LIB_DIR/verify_evidence_capture.py" \
        --ledger "$capture_ledger" > "$flow_dir/evidence-capture.json"; then
        evidence_ok=0
    fi

    if [[ "$flow_name" == "portrait-lock" ]]; then
        post_hook_required=1
        if ! python3 "$LIB_DIR/verify_png_orientation.py" \
            --expect portrait "$flow_dir/final-screen.png" \
            > "$flow_dir/portrait-orientation.json"; then
            post_hook_ok=0
        fi
    fi

    # A successful UI round-trip is not enough: independently inspect the
    # bytes written through Android's document provider. The raw payload may
    # contain a public account identifier, so retain only its digest and a
    # secret-free structural audit, then remove both exact raw copies.
    if [[ "$flow_name" == "backup-json-roundtrip" ]]; then
        post_hook_required=1
        local raw_export="$flow_dir/exported-backup.raw.json"
        local post_hook_log="$flow_dir/exported-backup-post-hook.txt"
        : > "$post_hook_log"
        if adb_target_transfer exec-out cat "$roundtrip_remote" \
            > "$raw_export" 2>> "$post_hook_log" && [[ -s "$raw_export" ]]; then
            local export_hash
            export_hash="$(sha256sum "$raw_export" | awk '{print $1}')"
            printf 'sha256=%s\n' "$export_hash" > "$flow_dir/exported-backup.sha256"
            if python3 "$LIB_DIR/audit_exported_backup.py" "$raw_export" \
                > "$flow_dir/exported-backup-audit.json" 2>> "$post_hook_log"; then
                echo "audit=PASS" >> "$post_hook_log"
            else
                echo "audit=FAIL" >> "$post_hook_log"
                post_hook_ok=0
            fi
        else
            echo "pull=FAIL_OR_EMPTY remote=$roundtrip_remote" >> "$post_hook_log"
            post_hook_ok=0
        fi
        rm -f "$raw_export"
        if adb_target shell rm -f "$roundtrip_remote" \
            >> "$post_hook_log" 2>&1 &&
            adb_target shell test ! -e "$roundtrip_remote"; then
            echo "verified_absent=$roundtrip_remote" >> "$post_hook_log"
        else
            echo "cleanup=FAIL remote=$roundtrip_remote" >> "$post_hook_log"
            post_hook_ok=0
        fi
    fi

    if ! python3 "$LIB_DIR/verify_junit.py" "$final_attempt_dir/junit.xml" \
        > "$flow_dir/junit-summary.json"; then
        junit_ok=0
    fi
    if ! python3 "$LIB_DIR/render_junit_html.py" \
        "$final_attempt_dir/junit.xml" "$flow_dir/junit-only.html" \
        > "$flow_dir/junit-html-render.log" 2>&1; then
        junit_html_ok=0
    fi
    if ! python3 "$LIB_DIR/check_process_health.py" \
        --package "$PACKAGE_NAME" \
        --before "$flow_dir/exit-info-before.txt" \
        --after "$flow_dir/exit-info-after.txt" \
        --logcat "$flow_dir/logcat-safe.txt" \
        > "$flow_dir/process-health.json"; then
        crash_ok=0
    fi
    [[ -f "$FLOWS_DIR/$flow_name.expects" ]] && expects_required=1
    if ! verify_expectations "$flow_name" "$flow_dir/logcat-safe.txt" "$flow_dir/expectations.tsv"; then
        expects_ok=0
    fi
    local entity_contract="$FLOWS_DIR/$flow_name.entity-proof"
    local deep_link_contract="$FLOWS_DIR/$flow_name.deep-link-proof"
    local sort_contract="$FLOWS_DIR/$flow_name.sort-proof"
    if [[ -f "$entity_contract" ]]; then
        identity_required=1
        local minimum_chains
        minimum_chains="$(tr -d '[:space:]' < "$entity_contract")"
        if ! python3 "$LIB_DIR/verify_entity_trace.py" \
            --logcat "$flow_dir/logcat-safe.txt" \
            --minimum-chains "$minimum_chains" \
            > "$flow_dir/identity-proof.json"; then
            identity_ok=0
        fi
    elif [[ -f "$deep_link_contract" ]]; then
        identity_required=1
        local expected_roundtrips
        expected_roundtrips="$(tr -d '[:space:]' < "$deep_link_contract")"
        if ! python3 "$LIB_DIR/verify_deep_link_trace.py" \
            --logcat "$flow_dir/logcat-safe.txt" \
            --expected-roundtrips "$expected_roundtrips" \
            > "$flow_dir/identity-proof.json"; then
            identity_ok=0
        fi
    elif [[ -f "$sort_contract" ]]; then
        identity_required=1
        if ! python3 "$LIB_DIR/verify_newest_sort_trace.py" \
            --logcat "$flow_dir/logcat-safe.txt" \
            > "$flow_dir/identity-proof.json"; then
            identity_ok=0
        fi
    fi

    local result="PASS"
    if ((maestro_exit != 0 || evidence_ok == 0 || junit_ok == 0 || junit_html_ok == 0 || crash_ok == 0 || expects_ok == 0 || identity_ok == 0 || post_hook_ok == 0)); then
        result="FAIL"
        overall_failed=1
    fi
    local maestro_status="PASS" evidence_status="PASS" junit_status="PASS" junit_html_status="PASS"
    local crash_status="PASS" expects_status="SKIP" identity_status="SKIP" post_hook_status="SKIP"
    ((maestro_exit == 0)) || maestro_status="FAIL"
    ((evidence_ok)) || evidence_status="FAIL"
    ((junit_ok)) || junit_status="FAIL"
    ((junit_html_ok)) || junit_html_status="FAIL"
    ((crash_ok)) || crash_status="FAIL"
    if ((expects_required)); then
        expects_status="PASS"
        ((expects_ok)) || expects_status="FAIL"
    fi
    if ((identity_required)); then
        identity_status="PASS"
        ((identity_ok)) || identity_status="FAIL"
    fi
    if ((post_hook_required)); then
        post_hook_status="PASS"
        ((post_hook_ok)) || post_hook_status="FAIL"
    fi
    if ! python3 "$LIB_DIR/render_root_html.py" \
        --output "$flow_dir/report.html" \
        --flow "$flow_name" \
        --result "$result" \
        --maestro-exit "$maestro_exit" \
        --infrastructure-retries "$infrastructure_retries" \
        --maestro "$maestro_status" \
        --evidence-capture "$evidence_status" \
        --junit "$junit_status" \
        --junit-html "$junit_html_status" \
        --process-health "$crash_status" \
        --expectations "$expects_status" \
        --identity-proof "$identity_status" \
        --post-hook "$post_hook_status" \
        > "$flow_dir/root-html-render.log" 2>&1; then
        result="FAIL"
        overall_failed=1
    fi
    if is_nostr_live_flow "$flow_name"; then
        local live_proof effect_suffix live_status
        if ((maestro_started == 0)); then
            live_proof="mandatory evidence precondition failed before Maestro"
            effect_suffix="DM not attempted; no remote effect"
            live_status="NOT_SENT"
        else
            live_proof="no relay-accept marker"
            effect_suffix="remote DM may be retained by recipient protocol and is not sender-recallable"
            live_status="$result"
            if [[ "$flow_name" == "nostr-dm-delivery" ]] &&
                grep -qE 'MessageDeliveryCoord.*Message .* delivered to relay' "$flow_dir/logcat-safe.txt"; then
                live_proof="relay accepted app-scoped delivery"
            elif [[ "$flow_name" == "nostr-dm-force-stop" ]] &&
                grep -qE 'OfflineQueueManager.*Queue drain: sent' "$flow_dir/logcat-safe.txt"; then
                live_proof="relay accepted restarted queue drain"
            fi
        fi
        printf '%s\t%s\tapp-generated-disposable\t%s; %s\t%s\n' \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$flow_name" "$live_proof" "$effect_suffix" "$live_status" \
            >> "$run_dir/external-effects.tsv"
    fi
    printf '%d\t%d\t%s\t%s\t%d\t%d\t%s\n' \
        "$repeat_no" "$sequence" "$flow_name" "$result" "$maestro_exit" \
        "$infrastructure_retries" "$flow_dir" >> "$run_dir/results.tsv"
    last_flow_result="$result"
    echo "$result: $flow_name (repeat $repeat_no; evidence $flow_dir)"
}

echo "Running ${#flow_names[@]} flow(s) x $repeat_count on exact device $DEVICE_SERIAL"
echo "Evidence: $run_dir"

for ((repeat_no = 1; repeat_no <= repeat_count; repeat_no++)); do
    for flow_name in "${flow_names[@]}"; do
        sequence=$((sequence + 1))
        run_flow "$repeat_no" "$flow_name"
        if ((nostr_live_selected)) && [[ "$last_flow_result" != "PASS" ]]; then
            abort_nostr_suite=1
        fi
        if ((nostr_live_selected && abort_nostr_suite)); then
            if ((sequence < ${#flow_names[@]})); then
                printf '%s\tSUITE_ABORT\tapp-generated-disposable\tremaining live roots not started after preceding non-PASS result\tNOT_SENT\n' \
                    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$run_dir/external-effects.tsv"
            fi
            break
        fi
    done
    ((nostr_live_selected && abort_nostr_suite)) && break
done

cleanup_result="PASS"
if ! perform_cleanup; then
    cleanup_result="FAIL"
    overall_failed=1
fi

if ! python3 "$LIB_DIR/render_run_html.py" \
    --results "$run_dir/results.tsv" \
    --run-dir "$run_dir" \
    --cleanup-result "$cleanup_result" \
    --output "$run_dir/run-report.html"; then
    overall_failed=1
fi

{
    echo "finished_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "cleanup_restore=$cleanup_result"
    echo "overall=$([[ $overall_failed -eq 0 ]] && echo PASS || echo FAIL)"
} >> "$run_dir/run.properties"

if ((overall_failed)); then
    echo "FAIL: one or more flows failed. Structured results: $run_dir/results.tsv" >&2
    exit 1
fi

echo "PASS: all selected flows and cleanup/restore passed with full root/run HTML verdicts and structured evidence"
echo "Structured results: $run_dir/results.tsv"
echo "HTML index: $run_dir/run-report.html"
