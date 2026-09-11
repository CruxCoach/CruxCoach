#!/usr/bin/env python3
"""Fetch hash-pinned MDK source and apply the reviewed local adapter extension.

No upstream build/install scripts run. Cargo.lock pins all transitive sources.
"""
import hashlib
import io
from pathlib import Path
import subprocess
import tarfile
import urllib.request

REVISION = "615d0c1cf48dbb231ecce0e3c8cf5cc3677eda57"
ARCHIVE_SHA256 = "414bb00dd79cc4fed3615fd9c0e0fdc6b7340d9987edbc75ecc22160e4b9ce1e"
ROOT = Path(__file__).resolve().parent


def source_digest(destination):
    digest = hashlib.sha256()
    for path in sorted(destination.rglob("*")):
        if path == destination / ".cruxcoach-source":
            continue
        if path.is_symlink():
            if not path.resolve().is_relative_to(destination.resolve()):
                raise SystemExit("Source symlink escapes qualified tree")
            digest.update(path.relative_to(destination).as_posix().encode() + b"\0link\0")
            digest.update(str(path.readlink()).encode() + b"\0")
            continue
        if path.is_file():
            digest.update(path.relative_to(destination).as_posix().encode() + b"\0")
            digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def verify_tree(destination):
    expected = (ROOT / "mdk-tree.sha256").read_text().strip()
    if source_digest(destination) != expected:
        raise SystemExit("Qualified MDK source tree changed; inspect cache before rebuilding")


def main():
    patch = ROOT / "mdk-extension.patch"
    fingerprint = REVISION + ":" + hashlib.sha256(patch.read_bytes()).hexdigest()
    destination = ROOT / ".mdk"
    marker = destination / ".cruxcoach-source"
    if destination.exists():
        if (marker.is_file() and not marker.is_symlink() and marker.stat().st_size == len(fingerprint)
                and marker.read_text() == fingerprint):
            verify_tree(destination)
            return
        raise SystemExit("Source cache differs; inspect and remove native/marmot/.mdk explicitly.")
    request = urllib.request.Request(
        f"https://codeload.github.com/marmot-protocol/mdk/tar.gz/{REVISION}"
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        archive = response.read(32 * 1024 * 1024)
    if hashlib.sha256(archive).hexdigest() != ARCHIVE_SHA256:
        raise SystemExit("MDK archive checksum mismatch")
    destination.mkdir(mode=0o700)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as source:
        for member in source.getmembers():
            parts = Path(member.name).parts[1:]
            if not parts:
                continue
            member.name = str(Path(*parts))
            source.extract(member, destination, filter="data")
    subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-i", str(patch)],
                   cwd=destination, check=True)
    verify_tree(destination)
    marker.write_text(fingerprint)


if __name__ == "__main__":
    main()
