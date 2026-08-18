from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_deep_link_trace import verify


UUID_A = "01234567-89ab-cdef-0123-456789abcdef"
UUID_B = "fedcba98-7654-3210-fedc-ba9876543210"
UNKNOWN = "00000000-0000-0000-0000-000000000000"


def complete(value: str) -> str:
    return "\n".join((
        f"D PERF: NAV START: DeepLink → ClimbDetail({value})",
        f"D PERF: loadClimb start ({value})",
        f"D PERF: loadClimb complete ({value})",
        f"D PERF: NAV COMPLETE: BoardClimbDetail({value}) (total=4ms)",
    ))


class DeepLinkTraceTest(unittest.TestCase):
    def write(self, text: str) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "logcat.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_accepts_share_warm_cold_and_unknown_fallback(self) -> None:
        text = "\n".join((
            f"D PERF: SHARE SOURCE: ClimbDetail({UUID_A})",
            complete(UUID_A), complete(UUID_A),
            f"D PERF: NAV START: DeepLink → ClimbDetail({UNKNOWN})",
            f"D PERF: loadClimb start ({UNKNOWN})",
        ))
        result = verify(self.write(text), 2)
        self.assertTrue(result["valid"])
        self.assertEqual(2, result["complete_roundtrips"])
        self.assertNotIn(UUID_A, str(result))

    def test_rejects_openlink_different_from_share_source(self) -> None:
        text = "\n".join((
            f"D PERF: SHARE SOURCE: ClimbDetail({UUID_A})",
            complete(UUID_B),
            f"D PERF: NAV START: DeepLink → ClimbDetail({UNKNOWN})",
            f"D PERF: loadClimb start ({UNKNOWN})",
        ))
        result = verify(self.write(text), 1)
        self.assertFalse(result["valid"])
        self.assertIn("differs from share source", " ".join(result["errors"]))

    def test_rejects_unknown_uuid_render(self) -> None:
        text = "\n".join((
            f"D PERF: SHARE SOURCE: ClimbDetail({UUID_A})", complete(UUID_A), complete(UNKNOWN),
        ))
        result = verify(self.write(text), 1)
        self.assertFalse(result["valid"])
        self.assertIn("unexpectedly rendered", " ".join(result["errors"]))


if __name__ == "__main__":
    unittest.main()
