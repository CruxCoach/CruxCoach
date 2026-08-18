from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from audit_flow_contracts import audit


class FlowContractAuditTest(unittest.TestCase):
    def fixture(self) -> tuple[Path, Path, Path, Path]:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        flows = root / "flows"
        flows.mkdir()
        common = "tags:\n  - release-gate\n  - mutates-state\n---\n"
        (flows / "release-fresh-onboarding.yaml").write_text(common, encoding="utf-8")
        live = "tags:\n  - irreversible-external\n  - live-external\n  - network\n  - mutates-state\n---\n"
        (flows / "nostr-dm-delivery.yaml").write_text(live, encoding="utf-8")
        (flows / "nostr-dm-force-stop.yaml").write_text(live, encoding="utf-8")
        release = root / "release.txt"
        release.write_text("release-fresh-onboarding\n", encoding="utf-8")
        nostr = root / "nostr.txt"
        nostr.write_text(
            "release-fresh-onboarding\nnostr-dm-delivery\nnostr-dm-force-stop\n",
            encoding="utf-8",
        )
        contracts = root / "contracts.tsv"
        contracts.write_text(
            "flow\tcontract\tnote\n"
            "release-fresh-onboarding\tdestructive-reset\tbaseline\n"
            "nostr-dm-delivery\trecipient-retained-one-shot\tone\n"
            "nostr-dm-force-stop\trecipient-retained-one-shot\ttwo\n",
            encoding="utf-8",
        )
        return flows, release, nostr, contracts

    def test_accepts_complete_contract(self) -> None:
        flows, release, nostr, contracts = self.fixture()
        result = audit(flows, release, nostr, contracts)
        self.assertTrue(result["valid"], result["errors"])

    def test_rejects_mutation_without_contract(self) -> None:
        flows, release, nostr, contracts = self.fixture()
        (flows / "leak.yaml").write_text("tags:\n  - mutates-state\n---\n", encoding="utf-8")
        result = audit(flows, release, nostr, contracts)
        self.assertFalse(result["valid"])
        self.assertIn("lacks a contract", " ".join(result["errors"]))

    def test_rejects_live_root_in_release_gate(self) -> None:
        flows, release, nostr, contracts = self.fixture()
        release.write_text("release-fresh-onboarding\nnostr-dm-delivery\n", encoding="utf-8")
        result = audit(flows, release, nostr, contracts)
        self.assertFalse(result["valid"])
        self.assertIn("irreversible", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
