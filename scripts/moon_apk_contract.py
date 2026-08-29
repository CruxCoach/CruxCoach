#!/usr/bin/env python3
"""Fail-closed static compatibility probe for the official Moon Android app.

The probe never executes code from the downloaded APK. It verifies every split's
signing certificate, reads package metadata with Android build tools, and looks
for the Flutter AOT strings on which CruxCoach's accessibility importer relies.
Static evidence can only produce "compatible-static", never a runtime guarantee.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
from typing import Iterable
import zipfile


MAX_ARCHIVE_BYTES = 200 * 1024 * 1024
MAX_MEMBER_BYTES = 100 * 1024 * 1024
MAX_UNPACKED_BYTES = 400 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_archive_members(archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    members = archive.infolist()
    total = 0
    for member in members:
        path = PurePosixPath(member.filename)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError(f"unsafe archive member: {member.filename!r}")
        if member.file_size > MAX_MEMBER_BYTES:
            raise ValueError(f"archive member too large: {member.filename!r}")
        total += member.file_size
        if total > MAX_UNPACKED_BYTES:
            raise ValueError("archive expands beyond the safety limit")
    return members


def materialize_apks(artifact: Path, destination: Path) -> list[Path]:
    if artifact.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("Moon artifact exceeds the 200 MiB safety limit")
    if artifact.suffix.lower() == ".apk":
        target = destination / "base.apk"
        shutil.copyfile(artifact, target)
        return [target]
    with zipfile.ZipFile(artifact) as archive:
        members = safe_archive_members(archive)
        apk_members = [member for member in members if member.filename.lower().endswith(".apk")]
        if not apk_members:
            raise ValueError("XAPK contains no APK splits")
        result = []
        for index, member in enumerate(apk_members):
            target = destination / f"split-{index:03d}-{PurePosixPath(member.filename).name}"
            with archive.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
            result.append(target)
        return result


BADGING = re.compile(
    r"^package: name='(?P<package>[^']+)' versionCode='(?P<code>\d+)' "
    r"versionName='(?P<name>[^']*)'(?P<rest>.*)$"
)


def parse_badging(output: str) -> dict[str, object]:
    first = output.splitlines()[0] if output.splitlines() else ""
    match = BADGING.match(first)
    if not match:
        raise ValueError("could not parse aapt2 package metadata")
    split = re.search(r"\bsplit='([^']+)'", match.group("rest"))
    return {
        "package": match.group("package"),
        "version_code": int(match.group("code")),
        "version_name": match.group("name"),
        "split": split.group(1) if split else None,
    }


def run_text(command: list[str]) -> str:
    completed = subprocess.run(command, check=True, text=True, capture_output=True)
    return completed.stdout + completed.stderr


def signer_digest(apksigner: Path, apk: Path) -> str:
    output = run_text([str(apksigner), "verify", "--print-certs", str(apk)])
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output)
    if not match:
        raise ValueError(f"no signer certificate found for {apk.name}")
    return match.group(1).lower()


def scan_markers(apks: Iterable[Path], groups: dict[str, list[str]]) -> tuple[dict[str, bool], list[str]]:
    encoded = {name: [value.encode("utf-8") for value in values] for name, values in groups.items()}
    found = {name: False for name in groups}
    interesting_entries: set[str] = set()
    scanned_bytes = 0
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            for member in safe_archive_members(archive):
                if member.is_dir():
                    continue
                lower = member.filename.lower()
                interesting = (
                    lower.endswith("libapp.so")
                    or "flutter_assets" in lower
                    or lower.endswith("resources.arsc")
                )
                if not interesting:
                    continue
                scanned_bytes += member.file_size
                if scanned_bytes > MAX_UNPACKED_BYTES:
                    raise ValueError("relevant APK contents exceed the scan safety limit")
                interesting_entries.add(member.filename)
                data = archive.read(member)
                for name, alternatives in encoded.items():
                    if not found[name] and any(marker in data for marker in alternatives):
                        found[name] = True
    return found, sorted(interesting_entries)


def find_android_tool(name: str, explicit: str | None) -> Path:
    if explicit:
        candidate = Path(explicit)
        if candidate.is_file():
            return candidate
        raise ValueError(f"{name} does not exist: {candidate}")
    located = shutil.which(name)
    if located:
        return Path(located)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted((Path(sdk) / "build-tools").glob(f"*/{name}"), reverse=True)
        if candidates:
            return candidates[0]
    raise ValueError(f"Android tool not found: {name}")


def markdown_report(report: dict[str, object]) -> str:
    marker_lines = "\n".join(
        f"- {'✅' if present else '❌'} `{name}`"
        for name, present in report["markers"].items()
    )
    return (
        f"## Moon importer static compatibility check\n\n"
        f"- Version: `{report['version_name']}` (`versionCode {report['version_code']}`)\n"
        f"- Verdict: **{report['verdict']}**\n"
        f"- Newer than reviewed baseline: `{str(report['new_version']).lower()}`\n"
        f"- Package: `{report['package']}`\n"
        f"- Signing certificate SHA-256: `{report['signer_sha256']}`\n"
        f"- Downloaded artifact SHA-256: `{report['artifact_sha256']}`\n\n"
        f"### Importer contract markers\n\n{marker_lines}\n\n"
        "This is a static Flutter/AOT contract check. It does not prove runtime "
        "Accessibility navigation or node grouping; a new version still needs a canary run.\n"
    )


def apk_identity_matches(
    metadata: list[dict[str, object]],
    digests: set[str],
    expected_package: str,
    expected_signer: str,
) -> bool:
    bases = [item for item in metadata if item["split"] is None]
    if len(bases) != 1:
        return False
    base = bases[0]
    return (
        base["package"] == expected_package
        and digests == {expected_signer.lower()}
        and all(item["package"] == expected_package for item in metadata)
        and all(item["version_code"] == base["version_code"] for item in metadata)
    )


def analyze(args: argparse.Namespace) -> dict[str, object]:
    artifact = Path(args.artifact)
    baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
    aapt2 = find_android_tool("aapt2", args.aapt2)
    apksigner = find_android_tool("apksigner", args.apksigner)

    with tempfile.TemporaryDirectory(prefix="moon-contract-") as temporary:
        apks = materialize_apks(artifact, Path(temporary))
        if len(apks) > 64:
            raise ValueError(f"unexpected split count: {len(apks)}")
        metadata = [parse_badging(run_text([str(aapt2), "dump", "badging", str(apk)])) for apk in apks]
        bases = [item for item in metadata if item["split"] is None]
        if len(bases) != 1:
            raise ValueError(f"expected exactly one base APK, found {len(bases)}")
        base = bases[0]
        digests = {signer_digest(apksigner, apk) for apk in apks}
        markers, evidence_entries = scan_markers(apks, baseline["marker_groups"])

    expected_package = baseline["package"]
    expected_signer = baseline["signer_sha256"].lower()
    identity_ok = apk_identity_matches(metadata, digests, expected_package, expected_signer)
    contract_ok = all(markers.values())
    version_code = int(base["version_code"])
    report: dict[str, object] = {
        "schema": 1,
        "package": base["package"],
        "version_name": base["version_name"],
        "version_code": version_code,
        "reviewed_version_code": int(baseline["reviewed_version_code"]),
        "new_version": version_code > int(baseline["reviewed_version_code"]),
        "identity_ok": identity_ok,
        "contract_ok": contract_ok,
        "verdict": "compatible-static" if identity_ok and contract_ok else "incompatible-static",
        "signer_sha256": sorted(digests)[0] if len(digests) == 1 else sorted(digests),
        "artifact_sha256": sha256(artifact),
        "split_count": len(metadata),
        "markers": markers,
        "evidence_entries": evidence_entries,
    }
    report["should_alert"] = bool(report["new_version"] or report["verdict"] != "compatible-static")
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--json", required=True)
    parser.add_argument("--markdown")
    parser.add_argument("--github-output")
    parser.add_argument("--aapt2")
    parser.add_argument("--apksigner")
    args = parser.parse_args()
    try:
        report = analyze(args)
    except Exception as error:  # A malformed/untrusted artifact must fail closed.
        print(f"moon contract analysis failed: {error}")
        return 1
    Path(args.json).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    markdown = markdown_report(report)
    if args.markdown:
        Path(args.markdown).write_text(markdown, encoding="utf-8")
    if args.github_output:
        with Path(args.github_output).open("a", encoding="utf-8") as output:
            for key in ("version_code", "version_name", "verdict", "new_version", "should_alert"):
                print(f"{key}={str(report[key]).lower() if isinstance(report[key], bool) else report[key]}", file=output)
    print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
