from __future__ import annotations

import unittest
from pathlib import Path

from audit_runner_safety import audit_text


RUNNER = Path(__file__).resolve().parents[1] / "run.sh"


class RunnerSafetyAuditTest(unittest.TestCase):
    def test_current_runner_closes_pre_maestro_evidence_gate(self) -> None:
        result = audit_text(RUNNER.read_text(encoding="utf-8"))
        self.assertTrue(result["valid"], result["errors"])

    def test_rejects_gate_that_does_not_guard_maestro_loop(self) -> None:
        text = RUNNER.read_text(encoding="utf-8").replace(
            "while ((pre_maestro_capture_ok && attempt <= max_attempts)); do",
            "while ((attempt <= max_attempts)); do",
        )
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("pre-Maestro", " ".join(result["errors"]))

    def test_rejects_missing_capture_failure_closures(self) -> None:
        text = RUNNER.read_text(encoding="utf-8").replace("pre_maestro_capture_ok=0", "true")
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("device lock", " ".join(result["errors"]))

    def test_rejects_any_keyguard_dismissal(self) -> None:
        text = RUNNER.read_text(encoding="utf-8") + "\nadb shell wm dismiss-keyguard\n"
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("ignores", " ".join(result["errors"]))

    def test_rejects_permissive_device_lock_check(self) -> None:
        text = RUNNER.read_text(encoding="utf-8").replace(
            '[[ "$line" =~ ^[[:space:]]*deviceLocked=0[[:space:]]*$ ]] || return 1',
            '[[ "$line" != *deviceLocked=1* ]] || return 1',
        )
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("deviceLocked=0", " ".join(result["errors"]))

    def test_rejects_nostr_abort_limited_to_onboarding(self) -> None:
        text = RUNNER.read_text(encoding="utf-8").replace(
            'if ((nostr_live_selected)) && [[ "$last_flow_result" != "PASS" ]]; then',
            'if ((nostr_live_selected)) && [[ "$flow_name" == "release-fresh-onboarding" && "$last_flow_result" != "PASS" ]]; then',
        )
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("last_flow_result", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
