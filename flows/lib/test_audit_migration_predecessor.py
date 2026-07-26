from __future__ import annotations

import unittest
from pathlib import Path

from audit_migration_predecessor import EXPECTED_VALUES, audit, parse_string_values


REPO = Path(__file__).resolve().parents[2]


class MigrationPredecessorAuditTest(unittest.TestCase):
    def test_parses_exact_android_string_values(self) -> None:
        values = parse_string_values(
            '<resources><string name="moves">%d moves</string><string name="stars">%d stars</string></resources>'
        )
        self.assertEqual("%d moves", values["moves"])
        self.assertEqual("%d stars", values["stars"])

    def test_all_public_tags_match_exact_localized_values_and_bindings(self) -> None:
        result = audit(REPO, REPO / "flows/migration-predecessor-setup.yaml")
        self.assertTrue(result["valid"], result["errors"])
        for record in result["tags"]:
            self.assertEqual(EXPECTED_VALUES, record["localized_values"])


if __name__ == "__main__":
    unittest.main()
