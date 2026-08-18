from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import audit_exported_backup as auditor


class ExportedBackupAuditTest(unittest.TestCase):
    def payload(self) -> dict:
        first_uuid = "11111111111111111111111111111111"
        second_uuid = "22222222222222222222222222222222"
        return {
            "version": 3,
            "app": "CruxCoach",
            "exportedAt": "2026-07-24T12:00:00Z",
            "nostrPubkey": "a" * 64,
            "profile": None,
            "assessments": [],
            "bodyStats": [],
            "workoutLogs": [],
            "climbLogs": [],
            "trainingPlans": [],
            "boardAscents": [
                {
                    "climbUuid": first_uuid,
                    "angle": 45,
                    "quality": 5,
                    "bidCount": 1,
                    "comment": auditor.FIRST_COMMENT,
                },
                {
                    "climbUuid": second_uuid,
                    "angle": 50,
                    "quality": 4,
                    "bidCount": 1,
                    "comment": auditor.SECOND_COMMENT,
                },
            ],
            "boardBids": [
                {
                    "climbUuid": second_uuid,
                    "angle": 50,
                    "bidCount": 3,
                    "comment": auditor.ATTEMPT_COMMENT,
                }
            ],
            "boardSessions": [],
            "climbLists": [
                {
                    "name": "Favoriten",
                    "isBuiltin": True,
                    "externalId": None,
                    "entries": [first_uuid],
                },
                {
                    "name": "Ignoriert",
                    "isBuiltin": True,
                    "externalId": auditor.IGNORED_EXTERNAL_ID,
                    "entries": [second_uuid],
                },
            ],
            "boardClimbs": [],
            "boardClimbStats": [],
        }

    def audit_payload(self, payload: dict) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backup.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            return auditor.audit(path)

    def test_accepts_controlled_current_schema_and_redacts_identity(self) -> None:
        report = self.audit_payload(self.payload())
        serialized = json.dumps(report)
        self.assertEqual("PASS", report["audit"])
        self.assertNotIn("a" * 64, serialized)
        self.assertNotIn(auditor.FIRST_COMMENT, serialized)

    def test_rejects_favorites_and_ignored_folding(self) -> None:
        payload = self.payload()
        payload["climbLists"][0]["entries"].append(
            payload["boardAscents"][1]["climbUuid"]
        )
        with self.assertRaisesRegex(ValueError, "Favorites membership"):
            self.audit_payload(payload)

    def test_rejects_secret_key_name(self) -> None:
        payload = self.payload()
        payload["privateKey"] = "not-even-a-real-key"
        with self.assertRaisesRegex(ValueError, "sensitive key name"):
            self.audit_payload(payload)

    def test_rejects_nsec_shaped_value(self) -> None:
        payload = self.payload()
        payload["boardAscents"][0]["comment"] = "nsec1" + "q" * 58
        with self.assertRaisesRegex(ValueError, "nsec-shaped value"):
            self.audit_payload(payload)


if __name__ == "__main__":
    unittest.main()
