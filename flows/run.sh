#!/usr/bin/env bash
# CruxCoach Maestro flow runner with logcat-based behavioural verification.
#
# Strategy:
#   1. Clear logcat on the device.
#   2. Run every flow under flows/ as a SINGLE Maestro session — keeps
#      the instrumentation driver alive across all flows. Per-flow
#      `maestro test` invocations were observed to fail ~70 % of the
#      time on this SSH-tunneled adb path with EOFException in
#      AndroidDriver.startInstrumentationSession; the single-session
#      pattern is reliable.
#   3. After the session, snapshot the device's PERF-tag logcat once.
#   4. For each flow that has a `flow.expects` file, grep the cumulative
#      logcat for every pattern listed.
#   5. Fail the run if Maestro reports any flow as failed OR if any
#      logcat expectation is missing.
#
# Cross-flow logcat contamination caveat: because all flows share one
# logcat buffer, a marker emitted by an earlier flow could satisfy a
# later flow's expectation. In practice the PERF NAV markers we
# assert on (e.g. "BoardBrowser → ClimbDetail") fire only on the
# specific UI path and not in setup/teardown, so contamination is
# rare. If it bites, scope the regex tighter.
#
# Usage:
#   flows/run.sh                     # run every flow
#   flows/run.sh smoke               # run only flows/smoke.yaml
#   flows/run.sh smoke browser-search
#
# Requires:
#   - dadb on $PATH (~/bin/dadb wrapper from the dev-server setup)
#   - maestro on $PATH (~/.maestro/bin/maestro)
#   - the SSH adb tunnel + Python bridge from the dev-server setup so
#     that adb on default port 5037 reaches the VM-host's tethered phone

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLOWS_DIR="$REPO_ROOT/flows"
LOG_DIR="${MAESTRO_LOG_DIR:-/tmp/cruxcoach-flows-$(date +%Y%m%dT%H%M%S)}"
mkdir -p "$LOG_DIR"

ADB="${ADB:-$(command -v dadb || command -v adb)}"
MAESTRO="${MAESTRO:-$(command -v dmaestro || command -v maestro || echo "$HOME/.maestro/bin/maestro")}"

if [[ ! -x "$ADB" && ! -L "$ADB" ]]; then echo "ERROR: adb not found ($ADB)" >&2; exit 2; fi
if [[ ! -x "$MAESTRO" && ! -L "$MAESTRO" ]]; then echo "ERROR: maestro not found ($MAESTRO)" >&2; exit 2; fi

if ! "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "ERROR: no adb device. Tunnel down? Try:" >&2
    echo "       systemctl --user status cruxcoach-adb-bridge.service" >&2
    exit 2
fi

# Pick flows.
declare -a FLOWS
declare -a FLOW_NAMES
if [[ $# -gt 0 ]]; then
    for name in "$@"; do
        FLOWS+=("$FLOWS_DIR/$name.yaml")
        FLOW_NAMES+=("$name")
    done
else
    while IFS= read -r -d '' f; do
        FLOWS+=("$f")
        FLOW_NAMES+=("$(basename "$f" .yaml)")
    done < <(find "$FLOWS_DIR" -maxdepth 1 -name '*.yaml' -not -name 'config.yaml' -print0 | sort -z)
fi

echo " ▶ running ${#FLOWS[@]} flow(s): ${FLOW_NAMES[*]}"
echo " ▶ log dir: $LOG_DIR"

# PERF markers are runtime-gated in release builds. Preserve the device's prior
# property and enable diagnostics only for this runner invocation.
previous_perf_level="$("$ADB" shell getprop log.tag.PERF 2>/dev/null | tr -d '\r')"
restore_perf_level() {
    "$ADB" shell setprop log.tag.PERF "$previous_perf_level" >/dev/null 2>&1 || true
}
trap restore_perf_level EXIT
if ! "$ADB" shell setprop log.tag.PERF DEBUG >/dev/null 2>&1; then
    echo "ERROR: could not enable runtime PERF diagnostics" >&2
    exit 2
fi

# Clear logcat upfront so the post-session snapshot is scoped.
"$ADB" logcat -c >/dev/null 2>&1 || true

# Single Maestro invocation. --reinstall-driver up-front avoids the
# 1-in-3 cold-start EOFException race.
maestro_log="$LOG_DIR/maestro.log"
maestro_status=0
# Maestro CLI takes ONE path arg (flow file or directory). For
# multi-flow filtered runs we iterate, with a retry-on-EOF for the
# 1-in-3 startInstrumentationSession race against tunneled adb.
run_one() {
    local target="$1" attempts=2 try=1
    while (( try <= attempts )); do
        local extra=""
        # --reinstall-driver only on the very first invocation across
        # the session. Maestro re-uses the driver if it's already
        # present and operational.
        if (( try == 1 && PRIMED == 0 )); then
            extra="--reinstall-driver"
            PRIMED=1
        fi
        if "$MAESTRO" test $extra "$target" 2>&1 | tee -a "$maestro_log"; then
            return 0
        fi
        if grep -q EOFException "$maestro_log"; then
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
if [[ ${#FLOWS[@]} -eq 1 ]]; then
    run_one "${FLOWS[0]}" || maestro_status=$?
elif [[ $# -gt 0 ]]; then
    # Iterate user-specified flows; one Maestro test per flow.
    for f in "${FLOWS[@]}"; do
        run_one "$f" || maestro_status=$?
    done
else
    # Full-suite mode — Maestro test against the directory keeps the
    # driver alive across all flows in a single session, which is far
    # more reliable than per-flow invocations.
    run_one "$FLOWS_DIR" || maestro_status=$?
fi

# Snapshot logcat (PERF only — that's where our nav markers live).
"$ADB" logcat -d -s PERF:D > "$LOG_DIR/logcat-perf.txt" 2>/dev/null || true

# An empty diagnostic stream must never let a navigation flow pass merely
# because Maestro's visual assertions happened to match another screen.
expects_markers=0
for fname in "${FLOW_NAMES[@]}"; do
    expects_path="$FLOWS_DIR/$fname.expects"
    if [[ -f "$expects_path" ]] && grep -qE '^[[:space:]]*[^#[:space:]]' "$expects_path"; then
        expects_markers=1
        break
    fi
done
if [[ $expects_markers -eq 1 && ! -s "$LOG_DIR/logcat-perf.txt" ]]; then
    echo "ERROR: PERF logcat is empty although selected flows require markers" >&2
    exit 1
fi

# Determine per-flow Maestro pass/fail by parsing the human-readable output.
# Maestro emits "[Passed] $name" or "[Failed] $name (...)" per flow when
# given a directory; for a single-flow run it doesn't, in which case we
# infer from the overall exit code.
declare -A FLOW_RESULT
if [[ ${#FLOWS[@]} -gt 1 ]]; then
    while IFS= read -r line; do
        if [[ $line =~ \[Passed\][[:space:]](.+)[[:space:]]\([0-9]+ ]]; then
            FLOW_RESULT["${BASH_REMATCH[1]}"]="passed"
        elif [[ $line =~ \[Failed\][[:space:]](.+)[[:space:]]\([0-9]+ ]]; then
            FLOW_RESULT["${BASH_REMATCH[1]}"]="failed"
        fi
    done < "$maestro_log"
else
    if [[ $maestro_status -eq 0 ]]; then
        FLOW_RESULT["${FLOW_NAMES[0]}"]="passed"
    else
        FLOW_RESULT["${FLOW_NAMES[0]}"]="failed"
    fi
fi

# Verify per-flow logcat expectations.
echo
echo "════════════════════════════════════════════════════════════════"
echo " logcat verification"
echo "════════════════════════════════════════════════════════════════"
PASSED=0
FAILED=0
declare -a FAIL_NAMES
for fname in "${FLOW_NAMES[@]}"; do
    expects_path="$FLOWS_DIR/$fname.expects"
    maestro_state="${FLOW_RESULT[$fname]:-unknown}"

    if [[ "$maestro_state" != "passed" ]]; then
        echo "  ✗ $fname  — Maestro $maestro_state"
        FAILED=$((FAILED + 1))
        FAIL_NAMES+=("$fname")
        continue
    fi

    if [[ ! -f "$expects_path" ]]; then
        echo "  ✓ $fname  — Maestro passed (no logcat expects)"
        PASSED=$((PASSED + 1))
        continue
    fi

    flow_failed=0
    while IFS= read -r pattern; do
        [[ -z "$pattern" || "$pattern" =~ ^[[:space:]]*# ]] && continue
        if grep -qE "$pattern" "$LOG_DIR/logcat-perf.txt"; then
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
