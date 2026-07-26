from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_newest_sort_trace import verify


UUID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
UUID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"


class NewestSortTraceTest(unittest.TestCase):
    def write(self, text: str) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "logcat.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_accepts_uuid_and_timestamp_desc_asc_desc(self) -> None:
        text = "\n".join((
            f"D PERF: BROWSE TOP uuid={UUID_A} createdAt=2026-07-24T10:00:00Z sort=NEWEST dir=DESC",
            f"D PERF: BROWSE TOP uuid={UUID_B} createdAt=2020-01-01 00:00:00 sort=NEWEST dir=ASC",
            f"D PERF: BROWSE TOP uuid={UUID_A} createdAt=2026-07-24T10:00:00Z sort=NEWEST dir=DESC",
        ))
        result = verify(self.write(text))
        self.assertTrue(result["valid"])
        self.assertNotIn(UUID_A, str(result))

    def test_rejects_name_level_false_positive_same_uuid(self) -> None:
        text = "\n".join((
            f"D PERF: BROWSE TOP uuid={UUID_A} createdAt=2026-07-24 sort=NEWEST dir=DESC",
            f"D PERF: BROWSE TOP uuid={UUID_A} createdAt=2026-07-24 sort=NEWEST dir=ASC",
            f"D PERF: BROWSE TOP uuid={UUID_A} createdAt=2026-07-24 sort=NEWEST dir=DESC",
        ))
        result = verify(self.write(text))
        self.assertFalse(result["valid"])
        self.assertIn("identical", " ".join(result["errors"]))

    def test_rejects_non_restored_desc_uuid(self) -> None:
        text = "\n".join((
            f"D PERF: BROWSE TOP uuid={UUID_A} createdAt=2026-07-24 sort=NEWEST dir=DESC",
            f"D PERF: BROWSE TOP uuid={UUID_B} createdAt=2020-01-01 sort=NEWEST dir=ASC",
            f"D PERF: BROWSE TOP uuid={UUID_B} createdAt=2020-01-01 sort=NEWEST dir=DESC",
        ))
        result = verify(self.write(text))
        self.assertFalse(result["valid"])
        self.assertIn("restored DESC", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
