from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from audit_coverage_ledger import audit


class CoverageLedgerAuditTest(unittest.TestCase):
    def fixture(self, mapping: str, classification: str = "C") -> tuple[Path, Path]:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        flows = root / "flows"
        (flows / "subflows").mkdir(parents=True)
        (flows / "real-root.yaml").write_text("appId: example\n---\n", encoding="utf-8")
        (flows / "subflows" / "real-helper.yaml").write_text(
            "appId: example\n---\n", encoding="utf-8"
        )
        (flows / "wrapper.sh").write_text("#!/bin/sh\n", encoding="utf-8")
        ledger = root / "ledger.md"
        ledger.write_text(
            "| ID | Assertion | Planned mapping | Class now | Evidence |\n"
            "|---|---|---|---|---|\n"
            f"| T-1 | assertion | {mapping} | {classification} | pending |\n",
            encoding="utf-8",
        )
        return ledger, flows

    def test_accepts_real_root_subflow_and_host_script(self) -> None:
        ledger, flows = self.fixture(
            "`real-root` + `real-helper` + `wrapper.sh`"
        )
        result = audit(ledger, flows)
        self.assertTrue(result["valid"], result["errors"])

    def test_rejects_phantom_flow(self) -> None:
        ledger, flows = self.fixture("`phantom-flow`")
        result = audit(ledger, flows)
        self.assertFalse(result["valid"])
        self.assertIn("phantom automation", " ".join(result["errors"]))

    def test_rejects_missing_host_script(self) -> None:
        ledger, flows = self.fixture("`missing-wrapper.sh`")
        result = audit(ledger, flows)
        self.assertFalse(result["valid"])
        self.assertIn("missing host script", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
