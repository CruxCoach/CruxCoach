#!/usr/bin/env bash
# Host wrapper for the reboot-sensitive updater regression. It proves a real,
# single boot-count transition and fail-closed crash/ANR evidence before
# delegating the two-manual-check UI portion to the structured Maestro runner.

set -euo pipefail
umask 077

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
readonly repo_root
readonly package_name="com.cruxcoach.android"
device_serial="${MAESTRO_DEVICE_SERIAL:-}"
[[ -n "$device_serial" ]] || { echo "ERROR: MAESTRO_DEVICE_SERIAL is required" >&2; exit 2; }
[[ "$device_serial" =~ ^[A-Za-z0-9._:-]+$ ]] || { echo "ERROR: invalid device serial" >&2; exit 2; }

adb_bin="${ADB:-$(command -v dadb || command -v adb || true)}"
[[ -n "$adb_bin" ]] || { echo "ERROR: adb/dadb not found" >&2; exit 2; }
command -v timeout >/dev/null 2>&1 || { echo "ERROR: GNU timeout is required" >&2; exit 2; }
adb_timeout_seconds="${MAESTRO_ADB_TIMEOUT_SECONDS:-30}"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || {
    echo "ERROR: MAESTRO_ADB_TIMEOUT_SECONDS must be a positive integer" >&2
    exit 2
}

adb_host() {
    timeout --foreground --kill-after=5s "${adb_timeout_seconds}s" \
        "$adb_bin" "$@"
}

adb_target() {
    timeout --foreground --kill-after=5s "${adb_timeout_seconds}s" \
        "$adb_bin" -s "$device_serial" "$@"
}

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

capture_exact_device_unlocked() {
    local output_file="$1" error_file="$2" line
    local -a lock_lines=()
    if ! adb_target shell dumpsys trust > "$output_file" 2> "$error_file"; then
        return 2
    fi
    mapfile -t lock_lines < <(grep -E 'deviceLocked=' "$output_file" || true)
    ((${#lock_lines[@]} > 0)) || return 1
    for line in "${lock_lines[@]}"; do
        [[ "$line" =~ ^[[:space:]]*deviceLocked=0[[:space:]]*$ ]] || return 1
    done
}

boot_count_value=""
capture_boot_count() {
    local output_file="$1" error_file="$2"
    if ! adb_target shell settings get global boot_count \
        > "$output_file" 2> "$error_file"; then
        return 1
    fi
    boot_count_value="$(tr -d '[:space:]' < "$output_file")"
    [[ "$boot_count_value" =~ ^[0-9]+$ ]]
}

device_state="$({ adb_host devices 2>/dev/null || true; } | awk -v serial="$device_serial" '$1==serial {print $2; exit}')"
[[ "$device_state" == "device" ]] || { echo "ERROR: target $device_serial is not attached" >&2; exit 2; }

evidence_parent="${MAESTRO_EVIDENCE_DIR:-${TMPDIR:-/tmp}/cruxcoach-maestro}"
repo_real="$(realpath -m "$repo_root")"
evidence_real="$(realpath -m "$evidence_parent")"
case "$evidence_real/" in
    "$repo_real/"*) echo "ERROR: evidence directory must be outside the repository" >&2; exit 2 ;;
esac
run_id="${MAESTRO_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-reboot-updater}"
[[ "$run_id" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: unsafe MAESTRO_RUN_ID" >&2; exit 2; }
wrapper_dir="$evidence_real/$run_id"
[[ ! -e "$wrapper_dir" ]] || { echo "ERROR: evidence directory already exists: $wrapper_dir" >&2; exit 2; }
mkdir -p "$wrapper_dir/host"

if ! capture_exact_device_unlocked \
    "$wrapper_dir/host/device-lock-before.txt" \
    "$wrapper_dir/host/device-lock-before.err"; then
    fail "target must report exact deviceLocked=0 before the reboot root"
fi
if ! adb_target shell dumpsys package "$package_name" \
    > "$wrapper_dir/host/package-before.txt" \
    2> "$wrapper_dir/host/package-before.err" ||
    [[ ! -s "$wrapper_dir/host/package-before.txt" ]]; then
    fail "package identity capture before reboot failed or was empty"
fi
if ! capture_boot_count \
    "$wrapper_dir/host/boot-count-before.txt" \
    "$wrapper_dir/host/boot-count-before.err"; then
    fail "boot_count before reboot was unavailable or nonnumeric"
fi
boot_count_before="$boot_count_value"
if ! adb_target shell dumpsys activity exit-info "$package_name" \
    > "$wrapper_dir/host/exit-info-before.txt" \
    2> "$wrapper_dir/host/exit-info-before.err" ||
    [[ ! -s "$wrapper_dir/host/exit-info-before.txt" ]]; then
    fail "exit-info-before capture failed or was empty"
fi

if ! adb_target reboot \
    > "$wrapper_dir/host/reboot-command.txt" \
    2> "$wrapper_dir/host/reboot-command.err"; then
    fail "reboot command failed"
fi
attached=0
for _ in 1 2 3 4 5 6; do
    if adb_target wait-for-device >/dev/null 2>&1; then
        attached=1
        break
    fi
done
((attached == 1)) || fail "target did not return after reboot"

booted=0
: > "$wrapper_dir/host/boot-completed-poll.txt"
for attempt in $(seq 1 45); do
    if completed="$(adb_target shell getprop sys.boot_completed 2>/dev/null)"; then
        completed="$(printf '%s' "$completed" | tr -d '[:space:]')"
        printf 'attempt=%d command=PASS value=%s\n' "$attempt" "$completed" \
            >> "$wrapper_dir/host/boot-completed-poll.txt"
        if [[ "$completed" == "1" ]]; then
            booted=1
            break
        fi
    else
        printf 'attempt=%d command=FAIL\n' "$attempt" \
            >> "$wrapper_dir/host/boot-completed-poll.txt"
    fi
    sleep 2
done
((booted == 1)) || fail "Android did not finish booting"

if ! capture_boot_count \
    "$wrapper_dir/host/boot-count-after.txt" \
    "$wrapper_dir/host/boot-count-after.err"; then
    fail "boot_count after reboot was unavailable or nonnumeric"
fi
boot_count_after="$boot_count_value"
((boot_count_after == boot_count_before + 1)) ||
    fail "boot_count did not make exactly one transition ($boot_count_before -> $boot_count_after)"

# A real reboot requires the device owner's first secure unlock. Missing or
# failed trust-state queries never count as unlocked, and no bypass is used.
lock_status=0
if capture_exact_device_unlocked \
    "$wrapper_dir/host/device-lock-after.txt" \
    "$wrapper_dir/host/device-lock-after.err"; then
    lock_status=0
else
    lock_status=$?
fi
((lock_status != 2)) || fail "post-reboot trust-state capture failed"
if ((lock_status != 0)); then
    echo "ACTION REQUIRED: physically unlock exact device $device_serial (waiting up to 60s)"
    unlocked=0
    : > "$wrapper_dir/host/device-lock-poll.txt"
    for attempt in $(seq 1 30); do
        if capture_exact_device_unlocked \
            "$wrapper_dir/host/device-lock-after.txt" \
            "$wrapper_dir/host/device-lock-after.err"; then
            printf 'attempt=%d exact_deviceLocked=0\n' "$attempt" \
                >> "$wrapper_dir/host/device-lock-poll.txt"
            unlocked=1
            break
        else
            lock_status=$?
        fi
        ((lock_status != 2)) || fail "post-reboot trust-state polling command failed"
        printf 'attempt=%d exact_deviceLocked=0:not-yet\n' "$attempt" \
            >> "$wrapper_dir/host/device-lock-poll.txt"
        sleep 2
    done
    ((unlocked == 1)) || fail "secure post-reboot keyguard remains locked; no bypass attempted"
fi

if ! adb_target shell am force-stop "$package_name" \
    > "$wrapper_dir/host/force-stop-before-launch.txt" \
    2> "$wrapper_dir/host/force-stop-before-launch.err"; then
    fail "pre-launch force-stop failed"
fi
if ! adb_target logcat -b all -c \
    > "$wrapper_dir/host/logcat-clear.txt" \
    2> "$wrapper_dir/host/logcat-clear.err"; then
    fail "scoped logcat clear failed"
fi
if ! adb_target shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1 \
    > "$wrapper_dir/host/launch.txt" 2> "$wrapper_dir/host/launch.err"; then
    fail "post-reboot package launch failed"
fi

terminal_regex='event=(check_not_modified|check_no_update|check_error|update_available).*trigger=APP_FOREGROUND'
terminal_seen=0
for attempt in $(seq 1 45); do
    if ! adb_target logcat -b all -d -v epoch \
        'AndroidRuntime:V' 'ActivityManager:W' 'ActivityTaskManager:W' \
        'UpdateChecker:V' 'UpdateCheckWorker:V' 'UpdaterCoordinator:V' \
        'libc:V' 'DEBUG:V' '*:S' \
        > "$wrapper_dir/host/updater-post-reboot.log" \
        2> "$wrapper_dir/host/updater-post-reboot.err"; then
        fail "post-reboot scoped logcat dump failed on poll $attempt"
    fi
    if grep -qE -- "$terminal_regex" "$wrapper_dir/host/updater-post-reboot.log"; then
        terminal_seen=1
        break
    fi
    sleep 2
done
[[ -s "$wrapper_dir/host/updater-post-reboot.log" ]] ||
    fail "post-reboot scoped logcat evidence is empty"
if ! adb_target shell dumpsys activity exit-info "$package_name" \
    > "$wrapper_dir/host/exit-info-after.txt" \
    2> "$wrapper_dir/host/exit-info-after.err" ||
    [[ ! -s "$wrapper_dir/host/exit-info-after.txt" ]]; then
    fail "exit-info-after capture failed or was empty"
fi
if ! python3 "$repo_root/flows/lib/check_process_health.py" \
    --package "$package_name" \
    --before "$wrapper_dir/host/exit-info-before.txt" \
    --after "$wrapper_dir/host/exit-info-after.txt" \
    --logcat "$wrapper_dir/host/updater-post-reboot.log" \
    > "$wrapper_dir/host/process-health.json"; then
    fail "new package crash/ANR evidence or invalid health evidence detected"
fi

{
    echo "device_serial=$device_serial"
    echo "package=$package_name"
    echo "boot_count_before=$boot_count_before"
    echo "boot_count_after=$boot_count_after"
    echo "boot_count_transition=PASS_EXACTLY_ONE"
    echo "process_health=PASS"
    echo "terminal_seen=$terminal_seen"
    echo "finished_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$wrapper_dir/host/result.properties"

((terminal_seen == 1)) || fail "no post-reboot APP_FOREGROUND check completed"
if grep -qE -- 'event=check_throttled trigger=APP_FOREGROUND' "$wrapper_dir/host/updater-post-reboot.log"; then
    fail "first post-reboot foreground check was throttled"
fi

if ! adb_target shell am force-stop "$package_name" \
    > "$wrapper_dir/host/force-stop-before-maestro.txt" \
    2> "$wrapper_dir/host/force-stop-before-maestro.err"; then
    fail "force-stop before the delegated Maestro root failed"
fi
MAESTRO_EVIDENCE_DIR="$wrapper_dir" \
MAESTRO_RUN_ID="manual-check" \
MAESTRO_DEVICE_SERIAL="$device_serial" \
ADB="$adb_bin" \
    "$repo_root/flows/run.sh" updater-manual-check

echo "PASS: exact reboot transition, healthy automatic check, and two unthrottled manual checks"
echo "Evidence: $wrapper_dir"
