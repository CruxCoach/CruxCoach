#!/usr/bin/env bash
# CruxCoach Maestro flow runner with logcat-based behavioural verification.
#
# Strategy:
#   1. Run each selected flow separately, reusing the installed Maestro
#      driver but retrying the known cold-start EOFException once.
#   2. Clear logcat immediately before every attempt and snapshot it
#      immediately after that flow.
#   3. Verify a flow's `.expects` only against its own PERF log. An earlier
#      sibling can therefore never make a later flow pass.
#   4. Keep per-flow Maestro/logcat artifacts and report every failure.
#
# Usage:
#   flows/run.sh                     # run every flow
#   flows/run.sh smoke               # run only flows/smoke.yaml
#   flows/run.sh smoke browser-search
#
# Requires:
#   - adb on $PATH and an authorized Android device shown by `adb devices`
#   - maestro on $PATH (or set MAESTRO=/path/to/maestro)
# Custom device transports can set ADB=/path/to/an-adb-compatible wrapper.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLOWS_DIR="$REPO_ROOT/flows"
LOG_DIR="${MAESTRO_LOG_DIR:-/tmp/cruxcoach-flows-$(date +%Y%m%dT%H%M%S)}"
mkdir -p "$LOG_DIR"

ADB="${ADB:-$(command -v adb || true)}"
MAESTRO="${MAESTRO:-$(command -v maestro || echo "$HOME/.maestro/bin/maestro")}"

if [[ ! -x "$ADB" && ! -L "$ADB" ]]; then echo "ERROR: adb not found ($ADB)" >&2; exit 2; fi
if [[ ! -x "$MAESTRO" && ! -L "$MAESTRO" ]]; then echo "ERROR: maestro not found ($MAESTRO)" >&2; exit 2; fi

if ! "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "ERROR: no authorized adb device found." >&2
    echo "       Connect a device with USB debugging enabled and check: adb devices" >&2
    exit 2
fi

# Pick flows.
declare -a FLOWS
declare -a FLOW_NAMES
if [[ $# -gt 0 ]]; then
    for name in "$@"; do
        flow_path="$FLOWS_DIR/$name.yaml"
        if [[ ! -f "$flow_path" ]]; then
            echo "ERROR: unknown flow '$name' ($flow_path)" >&2
            exit 2
        fi
        FLOWS+=("$flow_path")
        FLOW_NAMES+=("$name")
    done
else
    # Honour the explicit workspace order. Append any newly-added flow not
    # listed there so it is still executed (and make that omission visible in
    # the runner output rather than silently skipping it).
    declare -A SEEN_FLOWS=()
    while IFS= read -r name; do
        [[ -z "$name" ]] && continue
        flow_path="$FLOWS_DIR/$name.yaml"
        if [[ ! -f "$flow_path" ]]; then
            echo "ERROR: config.yaml names missing flow '$name'" >&2
            exit 2
        fi
        FLOWS+=("$flow_path")
        FLOW_NAMES+=("$name")
        SEEN_FLOWS["$name"]=1
    done < <(sed -n '/^[[:space:]]*flowsOrder:/,$s/^[[:space:]]*-[[:space:]]*\([^#[:space:]]\+\).*$/\1/p' "$FLOWS_DIR/config.yaml")

    while IFS= read -r -d '' f; do
        name="$(basename "$f" .yaml)"
        [[ -n "${SEEN_FLOWS[$name]:-}" ]] && continue
        FLOWS+=("$f")
        FLOW_NAMES+=("$name")
    done < <(find "$FLOWS_DIR" -maxdepth 1 -name '*.yaml' -not -name 'config.yaml' -print0 | sort -z)
fi

if [[ ${#FLOWS[@]} -eq 0 ]]; then
    echo "ERROR: no flows selected" >&2
    exit 2
fi

echo " ▶ running ${#FLOWS[@]} flow(s): ${FLOW_NAMES[*]}"
echo " ▶ log dir: $LOG_DIR"

# PERF markers are runtime-gated in release builds. Preserve the device's prior
# property and enable diagnostics only for this runner invocation.
previous_perf_level="$("$ADB" shell getprop log.tag.PERF 2>/dev/null | tr -d '\r')"
restore_perf_level() {
    # Invoked indirectly by the EXIT trap below.
    # shellcheck disable=SC2317
    "$ADB" shell setprop log.tag.PERF "$previous_perf_level" >/dev/null 2>&1 || true
}
trap restore_perf_level EXIT
if ! "$ADB" shell setprop log.tag.PERF DEBUG >/dev/null 2>&1; then
    echo "ERROR: could not enable runtime PERF diagnostics" >&2
    exit 2
fi

# Maestro CLI takes one flow path. `--reinstall-driver` on the first attempt
# avoids the common cold-start failure; a genuine EOFException gets one retry.
run_one() {
    local target="$1" name="$2" attempts=2 try=1
    local maestro_log="$LOG_DIR/$name.maestro.log"
    : > "$maestro_log"
    while (( try <= attempts )); do
        local attempt_log="$LOG_DIR/$name.maestro-attempt-$try.log"
        local -a maestro_args=(test)
        if (( try == 1 && PRIMED == 0 )); then
            maestro_args+=(--reinstall-driver)
            PRIMED=1
        fi
        maestro_args+=("$target")
        "$ADB" logcat -c >/dev/null 2>&1 || true
        if "$MAESTRO" "${maestro_args[@]}" 2>&1 | tee "$attempt_log"; then
            cat "$attempt_log" >> "$maestro_log"
            return 0
        fi
        cat "$attempt_log" >> "$maestro_log"
        if grep -q EOFException "$attempt_log" && (( try < attempts )); then
            echo " ⟲ EOFException → retry (attempt $((try + 1))/$attempts)"
            try=$((try + 1))
            sleep 3
            continue
        fi
        return 1
    done
    return 1
}

PRIMED=0
PASSED=0
FAILED=0
declare -a FAIL_NAMES
for i in "${!FLOWS[@]}"; do
    f="${FLOWS[$i]}"
    fname="${FLOW_NAMES[$i]}"
    expects_path="$FLOWS_DIR/$fname.expects"
    flow_log="$LOG_DIR/$fname.logcat-perf.txt"

    echo
    echo "════════════════════════════════════════════════════════════════"
    echo " flow: $fname"
    echo "════════════════════════════════════════════════════════════════"
    maestro_status=0
    run_one "$f" "$fname" || maestro_status=$?
    "$ADB" logcat -d -s PERF:D > "$flow_log" 2>/dev/null || true

    if [[ $maestro_status -ne 0 ]]; then
        echo "  ✗ $fname  — Maestro failed"
        FAILED=$((FAILED + 1))
        FAIL_NAMES+=("$fname")
        continue
    fi

    if [[ ! -f "$expects_path" ]]; then
        echo "  ✓ $fname  — Maestro passed (no logcat expects)"
        PASSED=$((PASSED + 1))
        continue
    fi

    if grep -qE '^[[:space:]]*[^#[:space:]]' "$expects_path" && [[ ! -s "$flow_log" ]]; then
        echo "  ✗ $fname  — PERF logcat empty"
        FAILED=$((FAILED + 1))
        FAIL_NAMES+=("$fname")
        continue
    fi

    flow_failed=0
    while IFS= read -r pattern; do
        [[ -z "$pattern" || "$pattern" =~ ^[[:space:]]*# ]] && continue
        if grep -qE "$pattern" "$flow_log"; then
            echo "  ✓ $fname  — logcat matched '$pattern'"
        else
            echo "  ✗ $fname  — logcat MISSING '$pattern'"
            flow_failed=1
        fi
    done < "$expects_path"

    if [[ $flow_failed -ne 0 ]]; then
        FAILED=$((FAILED + 1))
        FAIL_NAMES+=("$fname")
    else
        PASSED=$((PASSED + 1))
    fi
done

echo
echo "════════════════════════════════════════════════════════════════"
echo " SUMMARY: $PASSED passed, $FAILED failed"
echo " Logs: $LOG_DIR"
if [[ $FAILED -gt 0 ]]; then
    echo " Failed: ${FAIL_NAMES[*]}"
    exit 1
fi
exit 0
