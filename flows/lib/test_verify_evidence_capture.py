from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_evidence_capture import REQUIRED, verify


class EvidenceCaptureTest(unittest.TestCase):
    def write(self, statuses: dict[str, str], extra: list[tuple[str, str]] | None = None) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "evidence-capture.tsv"
        lines = ["artifact\tstatus\tdetail"]
        lines.extend(f"{name}\t{statuses[name]}\ttest" for name in statuses)
        lines.extend(f"{name}\t{status}\ttest" for name, status in (extra or []))
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return path

    def test_accepts_exact_complete_pass_set(self) -> None:
        result = verify(self.write({name: "PASS" for name in REQUIRED}))
        self.assertTrue(result["valid"])
        self.assertEqual(len(REQUIRED), result["pass_count"])

    def test_rejects_failed_logcat_clear_even_if_everything_else_passes(self) -> None:
        statuses = {name: "PASS" for name in REQUIRED}
        statuses["logcat-clear"] = "FAIL"
        result = verify(self.write(statuses))
        self.assertFalse(result["valid"])
        self.assertIn("logcat-clear", " ".join(result["errors"]))

    def test_rejects_missing_empty_hierarchy_proof(self) -> None:
        statuses = {name: "PASS" for name in REQUIRED if name != "maestro-hierarchy-nonempty"}
        result = verify(self.write(statuses))
        self.assertFalse(result["valid"])
        self.assertIn("maestro-hierarchy-nonempty", " ".join(result["errors"]))

    def test_rejects_duplicate_or_unexpected_rows(self) -> None:
        statuses = {name: "PASS" for name in REQUIRED}
        result = verify(self.write(statuses, [("logcat-dump", "PASS"), ("other", "PASS")]))
        self.assertFalse(result["valid"])
        self.assertIn("duplicate", " ".join(result["errors"]))
        self.assertIn("unexpected", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
