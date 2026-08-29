#!/usr/bin/env python3
"""Validate a complete PNG and optionally its orientation, using stdlib only."""

from __future__ import annotations

import argparse
import json
import struct
import sys
import zlib
from pathlib import Path


def dimensions(path: Path) -> tuple[int, int]:
    try:
        payload = path.read_bytes()
    except OSError as exc:
        raise ValueError(f"cannot read PNG: {exc}") from exc
    if len(payload) < 45 or payload[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("missing or invalid PNG signature")
    offset = 8
    width = height = 0
    saw_ihdr = saw_idat = saw_iend = False
    chunk_index = 0
    while offset < len(payload):
        if len(payload) - offset < 12:
            raise ValueError("truncated PNG chunk framing")
        length = struct.unpack(">I", payload[offset:offset + 4])[0]
        chunk_type = payload[offset + 4:offset + 8]
        data_start = offset + 8
        data_end = data_start + length
        crc_end = data_end + 4
        if crc_end > len(payload):
            raise ValueError("truncated PNG chunk payload")
        data = payload[data_start:data_end]
        expected_crc = struct.unpack(">I", payload[data_end:crc_end])[0]
        actual_crc = zlib.crc32(chunk_type + data) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise ValueError("invalid PNG chunk CRC")
        if chunk_index == 0 and chunk_type != b"IHDR":
            raise ValueError("PNG IHDR is not the first chunk")
        if chunk_type == b"IHDR":
            if saw_ihdr or length != 13:
                raise ValueError("invalid PNG IHDR")
            width, height = struct.unpack(">II", data[:8])
            if width <= 0 or height <= 0:
                raise ValueError("invalid zero PNG dimension")
            saw_ihdr = True
        elif chunk_type == b"IDAT":
            if not saw_ihdr or length == 0:
                raise ValueError("invalid empty/out-of-order PNG IDAT")
            saw_idat = True
        elif chunk_type == b"IEND":
            if length != 0:
                raise ValueError("invalid PNG IEND")
            saw_iend = True
            offset = crc_end
            if offset != len(payload):
                raise ValueError("trailing bytes after PNG IEND")
            break
        offset = crc_end
        chunk_index += 1
    if not (saw_ihdr and saw_idat and saw_iend):
        raise ValueError("PNG is missing IHDR, nonempty IDAT, or IEND")
    return width, height


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("png", type=Path)
    parser.add_argument("--expect", choices=("portrait", "landscape"))
    args = parser.parse_args()
    try:
        width, height = dimensions(args.png)
    except ValueError as exc:
        print(json.dumps({"valid": False, "error": str(exc)}))
        return 1
    valid = True
    if args.expect == "portrait":
        valid = height > width
    elif args.expect == "landscape":
        valid = width > height
    print(
        json.dumps(
            {
                "expected": args.expect or "any",
                "height": height,
                "valid": valid,
                "width": width,
            },
            indent=2,
            sort_keys=True,
        )
    )
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
