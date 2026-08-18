from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import validate_missing_catalog_fixture as validator


class MissingCatalogFixtureValidationTest(unittest.TestCase):
    def write(self, payload: dict) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "fixture.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def payload(self) -> dict:
        return {
            "version": 3,
            "app": "CruxCoach",
            "exportedAt": validator.EXPECTED_TIMESTAMP,
            "climbLists": [
                {
                    "name": validator.EXPECTED_NAME,
                    "isBuiltin": False,
                    "createdAt": validator.EXPECTED_TIMESTAMP,
                    "entries": [validator.EXPECTED_UUID],
                }
            ],
        }

    def test_accepts_exact_identity_free_fixture(self) -> None:
        report = validator.validate(self.write(self.payload()))
        self.assertEqual("PASS", report["validation"])
        self.assertFalse(report["containsIdentity"])

    def test_rejects_any_extra_identity_or_data_field(self) -> None:
        payload = self.payload()
        payload["nostrPubkey"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "unexpected root field"):
            validator.validate(self.write(payload))


if __name__ == "__main__":
    unittest.main()
