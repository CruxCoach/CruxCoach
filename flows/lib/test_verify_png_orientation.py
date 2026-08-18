from __future__ import annotations

import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from verify_png_orientation import dimensions


class PngOrientationTest(unittest.TestCase):
    @staticmethod
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    @classmethod
    def png(cls, width: int = 1, height: int = 2) -> bytes:
        ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
        raw = b"".join(b"\x00" + (b"\x00\x00\x00" * width) for _ in range(height))
        return b"\x89PNG\r\n\x1a\n" + cls.chunk(b"IHDR", ihdr) + cls.chunk(b"IDAT", zlib.compress(raw)) + cls.chunk(b"IEND", b"")

    def test_reads_ihdr_dimensions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "screen.png"
            path.write_bytes(self.png(1080, 2400))
            self.assertEqual((1080, 2400), dimensions(path))

    def test_rejects_non_png(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "screen.png"
            path.write_bytes(b"not a png")
            with self.assertRaisesRegex(ValueError, "PNG signature"):
                dimensions(path)

    def test_rejects_truncated_header_only_png(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "screen.png"
            path.write_bytes(self.png()[:24])
            with self.assertRaisesRegex(ValueError, "signature|missing|truncated"):
                dimensions(path)

    def test_rejects_bad_chunk_crc(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "screen.png"
            payload = bytearray(self.png())
            payload[-1] ^= 0xFF
            path.write_bytes(payload)
            with self.assertRaisesRegex(ValueError, "CRC"):
                dimensions(path)


if __name__ == "__main__":
    unittest.main()
