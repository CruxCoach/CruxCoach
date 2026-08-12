#!/usr/bin/env python3
"""Load and validate the Zapstore publisher without evaluating .env as shell.

zsp treats .env as a very small key/value file: after trimming a line, the
text following ``SIGN_WITH=`` is the value.  In particular, a bunker URL is
normally unquoted and contains ``&``.  Sourcing that file in a shell executes
the ampersands as control operators and can leave SIGN_WITH empty.

This helper intentionally mirrors zsp's data-file behaviour, validates that
the signer can run headlessly, and pins it to zapstore.yaml's publisher key.
It never prints the signer unless --emit-value is explicitly requested; that
mode is for capture into a shell variable, not for interactive use.
"""

from __future__ import annotations

import argparse
import os
import re
import stat
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
SECP256K1_FIELD = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
SECP256K1_ORDER = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
SECP256K1_GENERATOR = (
    0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
    0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8,
)


class PreflightError(ValueError):
    """A release-blocking signer configuration error."""


def _secure_regular_file(path: Path, label: str) -> None:
    try:
        file_stat = path.stat()
    except OSError as exc:
        raise PreflightError(f"{label} is not readable: {path}: {exc.strerror}") from exc
    if not stat.S_ISREG(file_stat.st_mode):
        raise PreflightError(f"{label} is not a regular file: {path}")
    if stat.S_IMODE(file_stat.st_mode) & 0o077:
        raise PreflightError(f"{label} must not be accessible by group or others: {path}")


def read_sign_with(env_file: Path) -> str:
    """Read exactly one SIGN_WITH using zsp's literal .env semantics."""

    _secure_regular_file(env_file, "Zapstore environment file")
    try:
        lines = env_file.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise PreflightError(f"cannot read Zapstore environment file: {env_file}") from exc

    matches: list[str] = []
    for raw_line in lines:
        line = raw_line.strip()
        if line.startswith("SIGN_WITH="):
            matches.append(line.removeprefix("SIGN_WITH="))

    if not matches:
        raise PreflightError(f"SIGN_WITH is missing from {env_file}")
    if len(matches) != 1:
        raise PreflightError(f"SIGN_WITH occurs more than once in {env_file}")

    sign_with = matches[0]
    if not sign_with:
        raise PreflightError(f"SIGN_WITH is empty in {env_file}")
    if sign_with[:1] in {"'", '"'} or sign_with[-1:] in {"'", '"'}:
        raise PreflightError(
            "SIGN_WITH must use zsp's raw KEY=value format, without shell quotes"
        )
    if any(character in sign_with for character in "\r\n\x00"):
        raise PreflightError("SIGN_WITH contains a forbidden control character")
    return sign_with


def read_publisher_npub(config_file: Path) -> str:
    try:
        lines = config_file.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise PreflightError(f"cannot read Zapstore config: {config_file}") from exc

    matches: list[str] = []
    for raw_line in lines:
        match = re.fullmatch(r"\s*pubkey:\s*(npub1[023456789acdefghjklmnpqrstuvwxyz]+)\s*", raw_line)
        if match:
            matches.append(match.group(1))
    if len(matches) != 1:
        raise PreflightError(f"expected exactly one unquoted pubkey in {config_file}")
    return matches[0]


def _bech32_polymod(values: list[int]) -> int:
    generators = (0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3)
    checksum = 1
    for value in values:
        top = checksum >> 25
        checksum = ((checksum & 0x1FFFFFF) << 5) ^ value
        for index, generator in enumerate(generators):
            if (top >> index) & 1:
                checksum ^= generator
    return checksum


def _bech32_hrp_expand(hrp: str) -> list[int]:
    return [ord(character) >> 5 for character in hrp] + [0] + [
        ord(character) & 31 for character in hrp
    ]


def _convert_bits(values: list[int], from_bits: int, to_bits: int) -> bytes:
    accumulator = 0
    bit_count = 0
    result = bytearray()
    max_value = (1 << to_bits) - 1
    for value in values:
        if value < 0 or value >= (1 << from_bits):
            raise PreflightError("invalid bech32 data value")
        accumulator = (accumulator << from_bits) | value
        bit_count += from_bits
        while bit_count >= to_bits:
            bit_count -= to_bits
            result.append((accumulator >> bit_count) & max_value)
    if bit_count >= from_bits or ((accumulator << (to_bits - bit_count)) & max_value):
        raise PreflightError("invalid bech32 padding")
    return bytes(result)


def decode_nip19(value: str, expected_hrp: str) -> bytes:
    if value.lower() != value and value.upper() != value:
        raise PreflightError(f"mixed-case {expected_hrp} value")
    value = value.lower()
    separator = value.rfind("1")
    if separator < 1 or separator + 7 > len(value):
        raise PreflightError(f"malformed {expected_hrp} value")
    hrp = value[:separator]
    if hrp != expected_hrp:
        raise PreflightError(f"expected {expected_hrp}, got {hrp or 'no prefix'}")
    try:
        data = [BECH32_CHARSET.index(character) for character in value[separator + 1 :]]
    except ValueError as exc:
        raise PreflightError(f"invalid character in {expected_hrp} value") from exc
    if _bech32_polymod(_bech32_hrp_expand(hrp) + data) != 1:
        raise PreflightError(f"invalid {expected_hrp} checksum")
    decoded = _convert_bits(data[:-6], 5, 8)
    if len(decoded) != 32:
        raise PreflightError(f"{expected_hrp} payload must be 32 bytes")
    return decoded


Point = tuple[int, int] | None


def _point_add(first: Point, second: Point) -> Point:
    if first is None:
        return second
    if second is None:
        return first
    x1, y1 = first
    x2, y2 = second
    if x1 == x2 and (y1 != y2 or y1 == 0):
        return None
    if first == second:
        slope = (3 * x1 * x1) * pow(2 * y1, -1, SECP256K1_FIELD)
    else:
        slope = (y2 - y1) * pow(x2 - x1, -1, SECP256K1_FIELD)
    slope %= SECP256K1_FIELD
    x3 = (slope * slope - x1 - x2) % SECP256K1_FIELD
    y3 = (slope * (x1 - x3) - y1) % SECP256K1_FIELD
    return x3, y3


def _public_key_from_secret(secret: bytes) -> bytes:
    scalar = int.from_bytes(secret, "big")
    if not 1 <= scalar < SECP256K1_ORDER:
        raise PreflightError("SIGN_WITH contains an invalid secp256k1 private key")
    result: Point = None
    addend: Point = SECP256K1_GENERATOR
    while scalar:
        if scalar & 1:
            result = _point_add(result, addend)
        addend = _point_add(addend, addend)
        scalar >>= 1
    if result is None:
        raise PreflightError("could not derive the signer public key")
    return result[0].to_bytes(32, "big")


def _validate_bunker(sign_with: str) -> bytes:
    parsed = urlsplit(sign_with)
    if parsed.scheme != "bunker" or not parsed.hostname:
        raise PreflightError("SIGN_WITH is not a valid bunker:// URL")
    if parsed.username or parsed.password or parsed.port or parsed.fragment:
        raise PreflightError("bunker:// URL contains unsupported authority or fragment fields")
    signer_hex = parsed.hostname.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", signer_hex):
        raise PreflightError("bunker:// URL must contain a 32-byte hex signer public key")

    parameters = parse_qs(parsed.query, keep_blank_values=True)
    relays = parameters.get("relay", [])
    if not relays or any(not relay.startswith("wss://") for relay in relays):
        raise PreflightError("bunker:// URL needs at least one secure wss:// relay")
    if not any(parameters.get("secret", [])):
        raise PreflightError("bunker:// URL needs its provisioned connection secret")

    config_home = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    client_key_file = config_home / "zsp" / "bunker-keys" / f"{signer_hex}.key"
    _secure_regular_file(client_key_file, "zsp bunker client key")
    try:
        client_key = client_key_file.read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as exc:
        raise PreflightError(f"cannot read zsp bunker client key: {client_key_file}") from exc
    if not re.fullmatch(r"[0-9a-fA-F]{64}", client_key):
        raise PreflightError(f"zsp bunker client key is invalid: {client_key_file}")
    return bytes.fromhex(signer_hex)


def resolve_signer_public_key(sign_with: str) -> tuple[str, bytes]:
    if sign_with.startswith("bunker://"):
        return "NIP-46 bunker", _validate_bunker(sign_with)
    if sign_with.startswith("nsec1"):
        return "local nsec", _public_key_from_secret(decode_nip19(sign_with, "nsec"))
    if re.fullmatch(r"[0-9a-fA-F]{1,64}", sign_with):
        secret = bytes.fromhex(sign_with.zfill(64))
        return "local hex key", _public_key_from_secret(secret)
    if sign_with.startswith("npub1"):
        raise PreflightError(
            "SIGN_WITH=npub produces unsigned events in zsp; it cannot publish headlessly"
        )
    if sign_with == "browser":
        raise PreflightError("SIGN_WITH=browser requires interactive NIP-07 approval")
    raise PreflightError(
        "SIGN_WITH must be an nsec, a hex private key, or a provisioned bunker:// URL"
    )


def abbreviated_npub(npub: str) -> str:
    return f"{npub[:12]}…{npub[-6:]}"


def preflight(env_file: Path, config_file: Path) -> tuple[str, str, str]:
    sign_with = read_sign_with(env_file)
    publisher_npub = read_publisher_npub(config_file)
    expected_public_key = decode_nip19(publisher_npub, "npub")
    signer_type, signer_public_key = resolve_signer_public_key(sign_with)
    if signer_public_key != expected_public_key:
        raise PreflightError(
            "SIGN_WITH resolves to a different publisher than "
            f"{abbreviated_npub(publisher_npub)} in {config_file}"
        )
    return sign_with, signer_type, publisher_npub


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate a headless Zapstore signer against zapstore.yaml"
    )
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--config", default=Path("zapstore.yaml"), type=Path)
    parser.add_argument(
        "--emit-value",
        action="store_true",
        help="print SIGN_WITH for capture into a shell variable; never use in logs",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        sign_with, signer_type, publisher_npub = preflight(args.env_file, args.config)
    except PreflightError as exc:
        print(f"zapstore-signer: {exc}", file=sys.stderr)
        return 1

    if args.emit_value:
        print(sign_with)
    else:
        print(
            "Zapstore signer preflight passed: "
            f"{signer_type}, publisher {abbreviated_npub(publisher_npub)}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
