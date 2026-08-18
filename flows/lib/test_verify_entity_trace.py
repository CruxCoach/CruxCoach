from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_entity_trace import verify


UUID_A = "01234567-89ab-cdef-0123-456789abcdef"
UUID_B = "fedcba98-7654-3210-fedc-ba9876543210"


def chain(value: str) -> str:
    return "\n".join(
        (
            f"D PERF: 🧭 NAV START: BoardBrowser → ClimbDetail({value})",
            f"D PERF: loadClimb start ({value.replace('-', '').upper()})",
            f"D PERF: loadClimb complete ({value})",
            f"D PERF: NAV COMPLETE: BoardClimbDetail({value}) (total=4ms)",
        )
    )


class EntityTraceTest(unittest.TestCase):
    def write(self, text: str) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "logcat.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_accepts_normalized_uuid_and_multiple_chains(self) -> None:
        result = verify(self.write(chain(UUID_A) + "\n" + chain(UUID_B)), 2)
        self.assertTrue(result["valid"])
        self.assertEqual(2, result["complete_chains"])
        self.assertNotIn(UUID_A, str(result))

    def test_rejects_tap_to_load_mismatch(self) -> None:
        text = chain(UUID_A).replace(
            f"loadClimb start ({UUID_A.replace('-', '').upper()})",
            f"loadClimb start ({UUID_B})",
        )
        result = verify(self.write(text), 1)
        self.assertFalse(result["valid"])
        self.assertIn("load-start UUID differs", " ".join(result["errors"]))

    def test_rejects_missing_render_completion(self) -> None:
        text = "\n".join(chain(UUID_A).splitlines()[:-1])
        result = verify(self.write(text), 1)
        self.assertFalse(result["valid"])
        self.assertIn("incomplete", " ".join(result["errors"]))

    def test_rejects_new_tap_before_prior_entity_finishes(self) -> None:
        text = (
            f"D PERF: NAV START: BoardBrowser → ClimbDetail({UUID_A})\n"
            f"D PERF: NAV START: BoardBrowser → ClimbDetail({UUID_B})\n"
            + "\n".join(chain(UUID_B).splitlines()[1:])
        )
        result = verify(self.write(text), 1)
        self.assertFalse(result["valid"])
        self.assertIn("before prior chain", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
