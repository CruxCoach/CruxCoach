#!/usr/bin/env python3
"""Verify the vendored FIPS tree against its recorded provenance.

CruxCoach vendors the FIPS crate in-repo (``native/fips``) rather than pinning
a Git dependency. A Git pin is only as durable as the upstream object it names:
the reviewed integration commit lives on a branch head, and a branch that moves
or is pruned takes the build with it. Vendoring makes the exact source part of
this repository, which is also what lets CI build without reaching a third
party at all.

What that costs is a provenance question — "is this really FIPS at that commit,
plus exactly the patches we say?" — and this script answers it.

Two modes:

``--offline`` (the default, and what CI runs)
    Recompute the digest of the vendored tree and compare it with
    ``vendored_sha256`` in ``native/fips/VENDOR.toml``. Then reverse the
    recorded patches, check the pristine tree against ``upstream_sha256``,
    reapply the patches, and require the original tree again. This detects an
    edit to either the source or patch series without any upstream access.

``--upstream <path-to-fips-clone>``
    Additionally check the vendored tree against the real upstream commit:
    every vendored file must be byte-identical to upstream, except the files
    the recorded patches touch, and applying those patches to a pristine
    checkout must reproduce the vendored tree exactly.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
VENDOR_DIR = REPO_ROOT / "native" / "fips"
# Provenance metadata and the patch series are ours, not upstream's, so they
# are outside the digest of "the source we build".
NON_SOURCE = {"VENDOR.toml", "patches"}


def load_metadata(path: pathlib.Path) -> dict[str, object]:
    try:
        import tomllib
    except ModuleNotFoundError:  # pragma: no cover - Python < 3.11
        import tomli as tomllib  # type: ignore[no-redef]
    with path.open("rb") as handle:
        return tomllib.load(handle)


def source_files(root: pathlib.Path) -> list[pathlib.Path]:
    files = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if relative.parts[0] in NON_SOURCE:
            continue
        files.append(relative)
    return files


def tree_digest(root: pathlib.Path) -> str:
    """A single digest over ``<sha256>  <path>`` lines, sorted by path."""
    lines = []
    for relative in source_files(root):
        digest = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        lines.append(f"{digest}  {relative.as_posix()}")
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()


def verify_offline(metadata: dict[str, object]) -> list[str]:
    expected = metadata["vendored_sha256"]
    actual = tree_digest(VENDOR_DIR)
    if actual != expected:
        return [
            "the vendored FIPS tree does not match its recorded digest",
            f"  recorded: {expected}",
            f"  actual:   {actual}",
            "  Edit upstream sources only through native/fips/patches/, then",
            "  refresh vendored_sha256 in native/fips/VENDOR.toml.",
        ]
    return verify_patch_series_offline(metadata)


def apply_patch_series(
    root: pathlib.Path,
    patch_paths: list[pathlib.Path],
    *,
    reverse: bool,
) -> list[str]:
    ordered = reversed(patch_paths) if reverse else patch_paths
    direction = "reverse" if reverse else "forward"
    option = "--reverse" if reverse else "--forward"
    for patch_path in ordered:
        result = subprocess.run(
            ["patch", "-p1", "--batch", option, "-i", str(patch_path)],
            cwd=root,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            return [
                f"could not apply {patch_path.name} in {direction} direction",
                result.stdout.strip(),
                result.stderr.strip(),
            ]
    return []


def verify_patch_series_offline(metadata: dict[str, object]) -> list[str]:
    if shutil.which("patch") is None:
        return ["`patch` is required to verify the vendored FIPS patch series"]

    recorded_names = list(metadata.get("patches", []))
    patch_dir = VENDOR_DIR / "patches"
    actual_names = sorted(path.name for path in patch_dir.glob("*.patch"))
    if sorted(recorded_names) != actual_names:
        return [
            "the recorded FIPS patch list does not match the patch directory",
            f"  recorded: {sorted(recorded_names)}",
            f"  actual:   {actual_names}",
        ]
    patch_paths = [patch_dir / name for name in recorded_names]

    with tempfile.TemporaryDirectory() as work_dir:
        reconstructed = pathlib.Path(work_dir) / "reconstructed"
        shutil.copytree(
            VENDOR_DIR,
            reconstructed,
            ignore=shutil.ignore_patterns("VENDOR.toml", "patches"),
        )
        problems = apply_patch_series(reconstructed, patch_paths, reverse=True)
        if problems:
            return problems

        expected_upstream = metadata["upstream_sha256"]
        actual_upstream = tree_digest(reconstructed)
        if actual_upstream != expected_upstream:
            return [
                "reversing the recorded patches does not reproduce upstream",
                f"  recorded: {expected_upstream}",
                f"  actual:   {actual_upstream}",
            ]

        problems = apply_patch_series(reconstructed, patch_paths, reverse=False)
        if problems:
            return problems
        rebuilt = tree_digest(reconstructed)
        vendored = tree_digest(VENDOR_DIR)
        if rebuilt != vendored:
            return [
                "reapplying the recorded patches does not reproduce the "
                "vendored tree",
                f"  rebuilt:  {rebuilt}",
                f"  vendored: {vendored}",
            ]
    return []


def verify_against_upstream(
    metadata: dict[str, object], clone: pathlib.Path
) -> list[str]:
    commit = metadata["commit"]
    paths = list(metadata["paths"])
    patches = list(metadata.get("patches", []))

    try:
        subprocess.run(
            ["git", "-C", str(clone), "cat-file", "-e", f"{commit}^{{commit}}"],
            check=True,
            capture_output=True,
        )
    except subprocess.CalledProcessError:
        return [f"{clone} does not contain upstream commit {commit}"]

    with tempfile.TemporaryDirectory() as work_dir:
        pristine = pathlib.Path(work_dir) / "pristine"
        pristine.mkdir()
        archive = subprocess.run(
            ["git", "-C", str(clone), "archive", commit, "--", *paths],
            check=True,
            capture_output=True,
        ).stdout
        subprocess.run(
            ["tar", "-x", "-C", str(pristine)], input=archive, check=True
        )

        patch_paths = []
        for patch in patches:
            patch_path = VENDOR_DIR / "patches" / patch
            if not patch_path.is_file():
                return [f"recorded patch is missing: {patch_path}"]
            patch_paths.append(patch_path)
        problems = apply_patch_series(pristine, patch_paths, reverse=False)
        if problems:
            return [f"patch series does not apply to upstream {commit}", *problems]

        # Reuse the same digest definition, so "reproduced" means exactly what
        # the offline check means.
        rebuilt = tree_digest(pristine)
        vendored = tree_digest(VENDOR_DIR)
        if rebuilt != vendored:
            problems = [
                f"upstream {commit} plus the recorded patches does not "
                "reproduce the vendored tree",
                f"  rebuilt:  {rebuilt}",
                f"  vendored: {vendored}",
            ]
            upstream_files = set(source_files(pristine))
            vendor_files = set(source_files(VENDOR_DIR))
            for extra in sorted(vendor_files - upstream_files):
                problems.append(f"  only in vendored tree: {extra}")
            for missing in sorted(upstream_files - vendor_files):
                problems.append(f"  only upstream: {missing}")
            for shared in sorted(upstream_files & vendor_files):
                if (pristine / shared).read_bytes() != (VENDOR_DIR / shared).read_bytes():
                    problems.append(f"  differs: {shared}")
            return problems
    return []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--upstream",
        type=pathlib.Path,
        help="path to a jmcorgan/fips clone containing the recorded commit",
    )
    args = parser.parse_args(argv)

    metadata_path = VENDOR_DIR / "VENDOR.toml"
    if not metadata_path.is_file():
        print(f"missing provenance record: {metadata_path}", file=sys.stderr)
        return 1
    metadata = load_metadata(metadata_path)

    problems = verify_offline(metadata)
    if not problems and args.upstream is not None:
        if shutil.which("patch") is None:
            print("`patch` is required for --upstream", file=sys.stderr)
            return 1
        problems = verify_against_upstream(metadata, args.upstream)

    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1

    scope = "and reproduces upstream" if args.upstream else "matches its recorded digest"
    print(f"vendored FIPS at {metadata['commit']} {scope}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
