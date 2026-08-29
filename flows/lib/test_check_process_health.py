from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from check_process_health import check_health


PACKAGE = "com.cruxcoach.android"
HEADER = "ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)\n"


def exit_entry(timestamp: str, pid: int, code: int, reason: str, process: str = PACKAGE) -> str:
    return (
        "  ApplicationExitInfo #0:\n"
        f"    timestamp={timestamp} pid={pid} realUid=12345\n"
        f"    process={process} reason={code} ({reason}) status=0\n"
    )


class ProcessHealthTest(unittest.TestCase):
    def evidence(self, before: str, after: str, logcat: str) -> tuple[Path, Path, Path]:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        paths = (root / "before.txt", root / "after.txt", root / "logcat.txt")
        for path, value in zip(paths, (before, after, logcat), strict=True):
            path.write_text(value, encoding="utf-8")
        return paths

    def test_accepts_complete_benign_scoped_evidence(self) -> None:
        before, after, logcat = self.evidence(HEADER, HEADER, "UpdateChecker: benign\n")
        result = check_health(PACKAGE, before, after, logcat)
        self.assertTrue(result["healthy"], result)

    def test_rejects_missing_or_empty_evidence(self) -> None:
        before, after, logcat = self.evidence("", HEADER, "benign\n")
        before.unlink()
        result = check_health(PACKAGE, before, after, logcat)
        self.assertFalse(result["healthy"])
        self.assertIn("missing", " ".join(result["evidence_errors"]))

    def test_rejects_unrecognizable_or_malformed_exit_info(self) -> None:
        malformed = HEADER + "ApplicationExitInfo #0:\n  process=broken\n"
        before, after, logcat = self.evidence("not a dump\n", malformed, "benign\n")
        result = check_health(PACKAGE, before, after, logcat)
        self.assertFalse(result["healthy"])
        errors = " ".join(result["evidence_errors"])
        self.assertIn("recognizable", errors)
        self.assertIn("malformed", errors)

    def test_rejects_new_crash_or_anr_exit(self) -> None:
        before_text = HEADER + exit_entry("2026-07-24 10:00:00.000", 100, 10, "USER_REQUESTED")
        after_text = before_text + exit_entry("2026-07-24 10:01:00.000", 101, 6, "ANR")
        before, after, logcat = self.evidence(before_text, after_text, "benign\n")
        result = check_health(PACKAGE, before, after, logcat)
        self.assertFalse(result["healthy"])
        self.assertEqual("ANR", result["bad_exit_entries"][0]["reason"])

    def test_requires_fatal_exception_and_target_process_in_same_block(self) -> None:
        other = "AndroidRuntime: FATAL EXCEPTION: main\nAndroidRuntime: Process: other.app, PID: 5\n"
        before, after, logcat = self.evidence(HEADER, HEADER, other)
        self.assertTrue(check_health(PACKAGE, before, after, logcat)["healthy"])

        target = (
            "AndroidRuntime: FATAL EXCEPTION: main\n"
            f"AndroidRuntime: Process: {PACKAGE}, PID: 6\n"
        )
        before, after, logcat = self.evidence(HEADER, HEADER, target)
        result = check_health(PACKAGE, before, after, logcat)
        self.assertFalse(result["healthy"])
        self.assertIn("package fatal exception", result["logcat_findings"])

    def test_rejects_target_anr_and_native_fatal_but_not_other_process(self) -> None:
        target = (
            f"ActivityManager: ANR in {PACKAGE}\n"
            "libc: Fatal signal 11 (SIGSEGV)\n"
            f"DEBUG: Cmdline: {PACKAGE}:worker\n"
        )
        before, after, logcat = self.evidence(HEADER, HEADER, target)
        result = check_health(PACKAGE, before, after, logcat)
        self.assertFalse(result["healthy"])
        self.assertIn("package ANR", result["logcat_findings"])
        self.assertIn("package native fatal signal", result["logcat_findings"])


if __name__ == "__main__":
    unittest.main()
