#!/usr/bin/env python3
"""Derive and verify one durable APKTrack identity per feat/* branch."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tomllib
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / ".apktrack" / "feature-tracks.toml"
TRACK_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
PACKAGE_RE = re.compile(
    r"^com\.cruxcoach\.android\.dev(?:\.[A-Za-z][A-Za-z0-9_]*)+$"
)
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class FeatureIdentity:
    branch: str
    track: str
    package: str
    label: str


def load_config() -> dict[str, object]:
    with CONFIG.open("rb") as stream:
        value = tomllib.load(stream)
    if value.get("schema_version") != 1:
        raise ValueError("unsupported feature-track configuration")
    return value


def identity_for(branch: str) -> FeatureIdentity:
    if not branch.startswith("feat/") or branch.endswith("/") or ".." in branch:
        raise ValueError("published feature branch must be a safe feat/* name")
    config = load_config()
    overrides = config.get("overrides", {})
    override = overrides.get(branch) if isinstance(overrides, dict) else None
    digest = hashlib.sha256(branch.encode()).hexdigest()
    display = branch.removeprefix("feat/")
    slug = re.sub(r"[^a-z0-9]+", "-", display.lower()).strip("-") or "branch"
    if isinstance(override, dict):
        track = str(override.get("track", ""))
        package = str(override.get("package", ""))
        label = str(override.get("label", ""))
    else:
        track = f"feat-{slug[:43].rstrip('-')}-{digest[:8]}"
        package = f"com.cruxcoach.android.dev.f_{digest[:12]}"
        label = f"CruxCoach Dev · {display[:40]}"
    if not TRACK_RE.fullmatch(track):
        raise ValueError("derived feature track is not a valid APKTrack slug")
    if not PACKAGE_RE.fullmatch(package):
        raise ValueError("derived feature package escaped the development namespace")
    if not label or len(label) > 80 or any(character in label for character in "\r\n"):
        raise ValueError("derived feature label is invalid")
    return FeatureIdentity(branch=branch, track=track, package=package, label=label)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def write_github_output(path: Path, values: dict[str, object]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            text = str(value)
            if any(character in text for character in "\r\n"):
                raise ValueError(f"unsafe multiline GitHub output: {key}")
            stream.write(f"{key}={text}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch", required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--write-metadata", type=Path)
    parser.add_argument("--verify-metadata", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--commit")
    parser.add_argument("--version-code", type=int)
    args = parser.parse_args()
    identity = identity_for(args.branch)
    values: dict[str, object] = asdict(identity)

    if args.write_metadata is not None:
        if args.apk is None or args.commit is None or args.version_code is None:
            parser.error("--write-metadata requires --apk, --commit, and --version-code")
        if not COMMIT_RE.fullmatch(args.commit) or args.version_code <= 0:
            raise ValueError("metadata commit or version code is invalid")
        values.update(
            commit=args.commit,
            version_code=args.version_code,
            apk_sha256=sha256_file(args.apk),
            apk_name=args.apk.name,
        )
        args.write_metadata.write_text(
            json.dumps(values, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8"
        )

    if args.verify_metadata is not None:
        if args.apk is None or args.commit is None:
            parser.error("--verify-metadata requires --apk and --commit")
        metadata = json.loads(args.verify_metadata.read_text(encoding="utf-8"))
        expected = {**asdict(identity), "commit": args.commit, "apk_sha256": sha256_file(args.apk)}
        for key, value in expected.items():
            if metadata.get(key) != value:
                raise ValueError(f"feature artifact metadata mismatch: {key}")
        values["version_code"] = int(metadata["version_code"])
        values["apk_sha256"] = metadata["apk_sha256"]

    if args.github_output is not None:
        write_github_output(args.github_output, values)
    else:
        print(json.dumps(values, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"feature identity error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
