#!/usr/bin/env python3
"""Fail closed unless an exact GitHub login is in the trusted allowlist."""

from argparse import ArgumentParser
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ALLOWLIST = ROOT / ".github/authorized-feature-maintainers.txt"
LOGIN = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")


def authorized_logins(path: Path) -> set[str]:
    """Parse blank lines and full-line/inline comments without admitting them."""
    logins: set[str] = set()
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        candidate = raw_line.split("#", 1)[0].strip()
        if candidate and LOGIN.fullmatch(candidate):
            logins.add(candidate)
    return logins


def is_authorized(actor: str, path: Path) -> bool:
    """Every parse/read ambiguity denies publication."""
    if not LOGIN.fullmatch(actor):
        return False
    try:
        return actor in authorized_logins(path)
    except (OSError, UnicodeError):
        return False


def main() -> int:
    parser = ArgumentParser()
    parser.add_argument("--actor", required=True)
    parser.add_argument("--allowlist", type=Path, default=DEFAULT_ALLOWLIST)
    args = parser.parse_args()

    return 0 if is_authorized(args.actor, args.allowlist) else 1


if __name__ == "__main__":
    raise SystemExit(main())
