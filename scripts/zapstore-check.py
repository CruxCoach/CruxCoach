#!/usr/bin/env python3
"""Decide from ``zsp check`` whether Zapstore will take this release.

zsp 2.x publishes only with an active NIP-C1 proof for the APK's *current*
signing certificate (a proof for an ancestor does not count), and only when
SIGN_WITH is the proof's owner or delegate. ``zsp check`` reports all of it
without signing anything, but exits 0 either way. This reads its JSON and
refuses, before the GitHub release is public, unless

- the APK zsp found is signed by the release certificate,
- a proof for that certificate is active and authorizes SIGN_WITH, and
- its version code is above the last one published on Zapstore.

usage: zsp check zapstore.yaml --json | zapstore-check.py --certificate SHA256
"""

from __future__ import annotations

import argparse
import json
import re
import sys


HEX64 = re.compile(r"^[0-9a-f]{64}$")


def problems_in(document: object, certificate: str) -> list[str]:
    if not isinstance(document, dict) or document.get("operation") != "check" or document.get("ok") is not True:
        return ["zsp check did not report a successful check"]
    apk = document.get("apk")
    if not isinstance(apk, dict):
        return ["zsp check reported no APK"]
    problems = []
    found = str(apk.get("certificate_hash", "")).lower()
    if found != certificate:
        problems.append(f"zsp sees the APK signed by {found or 'nothing'}, not by the release certificate {certificate}")
    if not document.get("proof"):
        problems.append(
            "no active C1 proof for the release certificate on the relays: publish it before the release"
        )
    elif document.get("authorized") is not True:
        problems.append("SIGN_WITH is neither the owner nor the delegate of the C1 proof")
    version = apk.get("version_code")
    published = document.get("published_version_code")
    if not isinstance(version, int) or not isinstance(published, int):
        problems.append("zsp check reported no version codes")
    elif version <= published:
        problems.append(f"version code {version} is not above the published {published}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--certificate", required=True, help="SHA-256 of the release certificate")
    args = parser.parse_args(argv)
    certificate = args.certificate.strip().lower()
    if not HEX64.fullmatch(certificate):
        parser.error(f"not a SHA-256 certificate digest: {args.certificate!r}")
    try:
        document = json.load(sys.stdin)
    except json.JSONDecodeError as exc:
        print(f"ERROR: zsp check did not print JSON: {exc}", file=sys.stderr)
        return 1
    problems = problems_in(document, certificate)
    for problem in problems:
        print(f"ERROR: {problem}", file=sys.stderr)
    if problems:
        return 1
    apk = document["apk"]
    print(
        f"Zapstore will take {apk.get('version_name')} ({apk['version_code']}): "
        f"C1 proof active for {certificate}, SIGN_WITH authorized"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
