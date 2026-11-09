#!/usr/bin/env python3
"""Check a YubiKey-signed release APK against the unsigned build it came from.

From 0.2.5 on the release workflow builds the APK unsigned, and the maintainer
signs it on a YubiKey (docs/KEY_ROTATION.md). Before anything is published,
the signed APK has to prove three things:

1. Same contents: the same zip entries as the unsigned build, each with the
   same bytes (per-entry SHA-256), the same compression and at least the same
   alignment. Signing adds a signature block, never a file, so anything else
   means the signer changed the app.
2. Signed as planned: ``apksigner verify`` passes with v3 only (v1 and v2
   off) and exactly one signer, the new release certificate. For a minSdk-28
   APK apksigner reports v1 and v2 as false even when they are there (it
   stops at the strongest scheme those devices use), so the APK Signing Block
   is read as well: a v3 block, no v2 and no v3.1 block. v1 files would be
   extra entries under 1.
3. The rotation proof: ``apksigner lineage`` lists exactly the expected
   certificates in order, the old release certificate first. Without it no
   installed CruxCoach would take the update. The old certificate must keep
   its installed-data capability (or the update loses the app's data) and
   must not have the rollback capability (or the old key could still sign
   updates).

Without --unsigned, as in scripts/publish-github-release.sh (the by-hand path,
which has no unsigned build to compare with), only 2 and 3 are checked.

usage: verify-release-signature.py [--unsigned APK] --signed APK --apksigner PATH
           --signer SHA256 --lineage SHA256 [SHA256 ...]
"""

from __future__ import annotations

import argparse
import hashlib
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path


HEX64 = re.compile(r"^[0-9a-f]{64}$")
# Placeholder in every pin until the new certificate exists. A release must
# never pass with it, so it is refused by name rather than by "no match".
PLACEHOLDER = "0" * 64

SCHEME = re.compile(r"^Verified using (v[0-9.]+) scheme \(.*\): (true|false)$")
SIGNER_COUNT = re.compile(r"^Number of signers: ([0-9]+)$")
# apksigner up to build-tools 36: "Signer #1 certificate SHA-256 digest: …"
# (with an SDK-ranged rotation "Signer (minSdkVersion=…, maxSdkVersion=…) …");
# build-tools 37 names the scheme: "V3.0 Signer: certificate SHA-256 digest: …".
SIGNER_DIGEST = re.compile(
    r"^(V[0-9]+(\.[0-9]+)? )?Signer( #[0-9]+)?:? "
    r"(\(minSdkVersion=[0-9]+, maxSdkVersion=[0-9]+\) )?"
    r"certificate SHA-256 digest: ([0-9a-fA-F]{64})$"
)
# The source stamp names the distributing store, not an app signer.
STAMP_DIGEST = re.compile(r"^Source Stamp Signer:? certificate SHA-256 digest: [0-9a-fA-F]{64}$")
LINEAGE_DIGEST = re.compile(r"^Signer #([0-9]+) in lineage certificate SHA-256 digest: ([0-9a-fA-F]{64})$")
LINEAGE_CAPABILITY = re.compile(r"^Has (.+?) capability\s*: (true|false)$")
# What the oldest certificate in the lineage must and must not be trusted with.
OLDEST_CAPABILITIES = {"installed data": True, "rollback": False}

# IDs in the APK Signing Block (apksig's ApkSigningBlockUtils and V*SchemeConstants).
SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK = 0x7109871A
V3_BLOCK = 0xF05368C0
V31_BLOCK = 0x1B93AD61

# Android maps stored (uncompressed) entries straight from the APK: native
# libraries need page alignment (16 KB pages on newer devices), everything
# else 4 bytes. Only these are kept; any other boundary is chance.
LIBRARY_ALIGNMENTS = (16384, 4096, 4)
OTHER_ALIGNMENTS = (4,)


def required_alignment(name: str, offset: int) -> int:
    choices = LIBRARY_ALIGNMENTS if name.endswith(".so") else OTHER_ALIGNMENTS
    return next((a for a in choices if offset % a == 0), 1)


def pin(value: str) -> str:
    digest = value.strip().lower()
    if digest == PLACEHOLDER:
        raise argparse.ArgumentTypeError(
            "the certificate pin is still the placeholder; put in the new release certificate first"
        )
    if not HEX64.fullmatch(digest):
        raise argparse.ArgumentTypeError(f"not a SHA-256 certificate digest: {value!r}")
    return digest


def _entry_digest(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> str:
    digest = hashlib.sha256()
    with archive.open(info) as data:
        for chunk in iter(lambda: data.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _data_offset(raw, info: zipfile.ZipInfo) -> int:
    """Where the entry's data starts, read from its local header."""

    raw.seek(info.header_offset)
    header = raw.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise zipfile.BadZipFile(f"no local header for {info.filename}")
    name_length, extra_length = struct.unpack("<HH", header[26:30])
    return info.header_offset + 30 + name_length + extra_length


def _entries(path: Path) -> tuple[dict[str, tuple[str, int, int | None]], list[str]]:
    """name -> (sha256, compression, data offset of a stored entry)."""

    problems: list[str] = []
    entries: dict[str, tuple[str, int, int | None]] = {}
    with open(path, "rb") as raw, zipfile.ZipFile(raw) as archive:
        for info in archive.infolist():
            if info.filename in entries:
                problems.append(f"{path.name}: entry {info.filename} appears twice")
                continue
            offset = _data_offset(raw, info) if info.compress_type == zipfile.ZIP_STORED else None
            entries[info.filename] = (_entry_digest(archive, info), info.compress_type, offset)
    return entries, problems


def compare_entries(unsigned: Path, signed: Path) -> list[str]:
    try:
        before, problems = _entries(unsigned)
        after, more = _entries(signed)
    except (OSError, zipfile.BadZipFile) as exc:
        return [f"cannot read the APKs as zip files: {exc}"]
    problems += more
    for name in sorted(after.keys() - before.keys()):
        problems.append(f"the signed APK adds {name}")
    for name in sorted(before.keys() - after.keys()):
        problems.append(f"the signed APK lacks {name}")
    for name in sorted(before.keys() & after.keys()):
        digest, compression, offset = before[name]
        signed_digest, signed_compression, signed_offset = after[name]
        if signed_digest != digest:
            problems.append(f"{name} has other bytes in the signed APK")
        elif signed_compression != compression:
            problems.append(f"{name} is compressed differently in the signed APK")
        elif offset is not None and signed_offset is not None:
            alignment = required_alignment(name, offset)
            if signed_offset % alignment:
                problems.append(f"{name} lost its {alignment}-byte alignment in the signed APK")
    return problems


def signing_block_ids(path: Path) -> list[int]:
    """The IDs in the APK Signing Block, which sits right before the central directory."""

    with open(path, "rb") as raw:
        raw.seek(0, 2)
        size = raw.tell()
        tail_size = min(size, 22 + 0xFFFF)
        raw.seek(size - tail_size)
        tail = raw.read(tail_size)
        end = tail.rfind(b"PK\x05\x06")
        if end < 0 or end + 22 > len(tail):
            raise ValueError("no end of central directory")
        central_directory = struct.unpack("<I", tail[end + 16:end + 20])[0]
        if central_directory < 32:
            return []
        raw.seek(central_directory - 24)
        footer = raw.read(24)
        if footer[8:] != SIGNING_BLOCK_MAGIC:
            return []
        block_size = struct.unpack("<Q", footer[:8])[0]
        start = central_directory - block_size - 8
        if start < 0:
            raise ValueError("APK Signing Block larger than the file")
        raw.seek(start)
        if struct.unpack("<Q", raw.read(8))[0] != block_size:
            raise ValueError("APK Signing Block sizes disagree")
        pairs = raw.read(block_size - 24)
    ids = []
    position = 0
    while position < len(pairs):
        if position + 12 > len(pairs):
            raise ValueError("truncated APK Signing Block")
        length, block_id = struct.unpack("<QI", pairs[position:position + 12])
        if length < 4 or position + 8 + length > len(pairs):
            raise ValueError("malformed APK Signing Block")
        ids.append(block_id)
        position += 8 + length
    return ids


def check_signing_block(path: Path) -> list[str]:
    try:
        ids = signing_block_ids(path)
    except (OSError, ValueError, struct.error) as exc:
        return [f"cannot read the APK Signing Block: {exc}"]
    problems = []
    if V3_BLOCK not in ids:
        problems.append("the APK Signing Block has no v3 signature")
    if V2_BLOCK in ids:
        problems.append("the APK Signing Block has a v2 signature; the release is v3 only")
    if V31_BLOCK in ids:
        problems.append(
            "the APK Signing Block has a v3.1 signature: the rotation targets newer Android only"
            " (sign with --rotation-min-sdk-version 28)"
        )
    return problems


def check_verify_output(output: str, signer: str) -> list[str]:
    """Problems with ``apksigner verify --print-certs -v`` output."""

    schemes: dict[str, bool] = {}
    count: int | None = None
    digests: set[str] = set()
    problems: list[str] = []
    for line in output.splitlines():
        line = line.strip()
        if match := SCHEME.match(line):
            schemes[match.group(1)] = match.group(2) == "true"
        elif match := SIGNER_COUNT.match(line):
            count = int(match.group(1))
        elif "certificate SHA-256 digest:" in line:
            if STAMP_DIGEST.match(line):
                continue
            match = SIGNER_DIGEST.match(line)
            if not match:
                # An unknown format could hide a second signer.
                problems.append(f"unknown signer line from apksigner: {line}")
                continue
            digests.add(match.group(5).lower())
    for scheme, wanted in (("v1", False), ("v2", False), ("v3", True)):
        if scheme not in schemes:
            problems.append(f"apksigner did not report the {scheme} scheme")
        elif schemes[scheme] != wanted:
            problems.append(f"{scheme} signature is {'missing' if wanted else 'present'}; the release is v3 only")
    if count != 1:
        problems.append(f"expected exactly one signer, apksigner reports {count if count is not None else 'none'}")
    if digests != {signer}:
        found = ", ".join(sorted(digests)) or "none"
        problems.append(f"the signer is not the release certificate {signer}: {found}")
    return problems


def check_lineage_output(output: str, lineage: list[str]) -> list[str]:
    """Problems with ``apksigner lineage --print-certs`` output."""

    found: list[str] = []
    capabilities: list[dict[str, bool]] = []
    for line in output.splitlines():
        line = line.strip()
        if match := LINEAGE_DIGEST.match(line):
            if int(match.group(1)) != len(found) + 1:
                return [f"lineage signers are not numbered in order: {line}"]
            found.append(match.group(2).lower())
            capabilities.append({})
        elif (match := LINEAGE_CAPABILITY.match(line)) and capabilities:
            capabilities[-1][match.group(1)] = match.group(2) == "true"
    if found != lineage:
        return [
            "the lineage is " + (" -> ".join(found) or "missing")
            + ", expected " + " -> ".join(lineage)
        ]
    problems = []
    for capability, wanted in OLDEST_CAPABILITIES.items():
        actual = capabilities[0].get(capability)
        if actual is None:
            problems.append(f"apksigner did not report the {capability} capability of {found[0]}")
        elif actual != wanted:
            problems.append(
                f"{found[0]} {'lacks' if wanted else 'has'} the {capability} capability in the lineage"
            )
    return problems


def _apksigner(apksigner: str, *args: str) -> tuple[int, str]:
    result = subprocess.run(
        [apksigner, *args], capture_output=True, text=True, check=False
    )
    return result.returncode, result.stdout + result.stderr


def verify(unsigned: Path | None, signed: Path, apksigner: str, signer: str, lineage: list[str]) -> list[str]:
    problems = []
    if unsigned is not None:
        problems = compare_entries(unsigned, signed)
        report("same contents as the unsigned build", problems)

    code, output = _apksigner(apksigner, "verify", "--print-certs", "-v", str(signed))
    signature = [f"apksigner verify failed: {output.strip()}"] if code else check_verify_output(output, signer)
    signature += check_signing_block(signed)
    report(f"v3 only, one signer {signer}", signature)

    code, output = _apksigner(apksigner, "lineage", "--in", str(signed), "--print-certs")
    proof = [f"apksigner lineage failed: {output.strip()}"] if code else check_lineage_output(output, lineage)
    report("lineage " + " -> ".join(lineage), proof)
    return problems + signature + proof


def report(check: str, problems: list[str]) -> None:
    if not problems:
        print(f"OK: {check}")
    for problem in problems:
        print(f"ERROR: {problem}", file=sys.stderr)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--unsigned", type=Path, help="the APK the release workflow built")
    parser.add_argument("--signed", type=Path, required=True, help="the APK that came back signed")
    parser.add_argument("--apksigner", required=True, help="path to apksigner")
    parser.add_argument("--signer", type=pin, required=True, help="SHA-256 of the release certificate")
    parser.add_argument(
        "--lineage", type=pin, nargs="+", required=True,
        help="the expected lineage, oldest certificate first",
    )
    args = parser.parse_args(argv)
    if args.lineage[-1] != args.signer:
        parser.error("the lineage has to end with the release certificate")
    for apk in (args.unsigned, args.signed):
        if apk is not None and not apk.is_file():
            parser.error(f"APK not found: {apk}")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if verify(args.unsigned, args.signed, args.apksigner, args.signer, args.lineage):
        print("The signed APK is refused: nothing may be published with it.", file=sys.stderr)
        return 1
    if args.unsigned is None:
        print("The APK is signed as planned (no unsigned build to compare with).")
    else:
        print("The signed APK is the unsigned build, signed as planned.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
