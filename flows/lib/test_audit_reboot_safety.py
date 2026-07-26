from __future__ import annotations

import unittest
from pathlib import Path

from audit_reboot_safety import audit_text


RUNNER = Path(__file__).resolve().parents[1] / "run-reboot-updater.sh"


class RebootSafetyAuditTest(unittest.TestCase):
    def test_current_wrapper_is_fail_closed(self) -> None:
        result = audit_text(RUNNER.read_text(encoding="utf-8"))
        self.assertTrue(result["valid"], result["errors"])

    def test_rejects_missing_exact_boot_transition(self) -> None:
        text = RUNNER.read_text(encoding="utf-8").replace(
            "((boot_count_after == boot_count_before + 1))",
            "((boot_count_after >= boot_count_before))",
        )
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("boot_count_after", " ".join(result["errors"]))

    def test_rejects_keyguard_dismissal(self) -> None:
        text = RUNNER.read_text(encoding="utf-8") + "\nadb shell wm dismiss-keyguard\n"
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("dismiss-keyguard", " ".join(result["errors"]))

    def test_rejects_missing_process_health_gate(self) -> None:
        text = RUNNER.read_text(encoding="utf-8").replace(
            "check_process_health.py", "unchecked_process_health.py"
        )
        result = audit_text(text)
        self.assertFalse(result["valid"])
        self.assertIn("check_process_health.py", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
