#!/usr/bin/env python3
"""Collect attribution from the exact locked Cargo sources of the shipped
Android library. Never executes dependency code. Rerun after any Cargo.lock or
MDK pin change; `build.py` refuses a lock whose inventory hash is stale.

Crates that ship no license file get their upstream license text from the
exact recorded VCS revision on GitHub (network access required only then).
"""
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
REPOSITORY = ROOT.parents[1]
TARGET = "aarch64-linux-android"
NOTICE_PREFIXES = ("LICENSE", "COPYING", "NOTICE", "COPYRIGHT")
UPSTREAM_NAMES = ["LICENSE", "LICENSE.md", "LICENSE-MIT", "LICENSE-APACHE", "COPYING"]


def fetch(url):
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            return response.read(1_000_000).decode()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def main():
    metadata = json.loads(subprocess.check_output(
        ["cargo", "metadata", "--locked", "--offline", "--format-version", "1", "--filter-platform", TARGET],
        cwd=ROOT, text=True))
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    needed = set()

    def visit(package_id):
        if package_id in needed:
            return
        needed.add(package_id)
        for dependency in nodes[package_id]["deps"]:
            if any(kind["kind"] != "dev" for kind in dependency["dep_kinds"]):
                visit(dependency["pkg"])

    visit(metadata["resolve"]["root"])
    rows, texts, fetched = [], {}, {}
    packages = sorted((p for p in metadata["packages"] if p["id"] in needed), key=lambda p: (p["name"], p["version"]))
    for package in packages:
        directory = Path(package["manifest_path"]).parent
        files = [f for f in directory.iterdir() if f.is_file() and f.name.upper().startswith(NOTICE_PREFIXES)]
        if ".mdk" in directory.parts:
            files += [ROOT / "licenses/MDK-MIT.txt"]
        if package["name"] == "cruxcoach-marmot":
            files = [REPOSITORY / "LICENSE", REPOSITORY / "NOTICE"]
        if "/git/checkouts/" in str(directory):
            for parent in directory.parents:
                if parent.name == "checkouts":
                    break
                files += [f for f in parent.iterdir() if f.is_file() and f.name.upper().startswith(NOTICE_PREFIXES)]
        entries = []
        for file in sorted(set(files)):
            if file.stat().st_size > 1_000_000:
                raise SystemExit("notice_size_limit")
            content = file.read_text()
            digest = hashlib.sha256(content.encode()).hexdigest()
            texts[digest] = content
            entries.append({"name": file.name, "sha256": digest})
        if not entries:
            vcs_path = directory / ".cargo_vcs_info.json"
            vcs = json.loads(vcs_path.read_text()) if vcs_path.exists() else None
            repository = (package["repository"] or "").removesuffix(".git").rstrip("/")
            if not repository.startswith("https://github.com/"):
                raise SystemExit("unsupported_notice_source:" + package["name"])
            revisions = [vcs["git"]["sha1"]] if vcs else ["master", "main"]
            source = repository + "/" + revisions[0]
            if source not in fetched:
                raw = repository.replace("https://github.com/", "https://raw.githubusercontent.com/")
                for revision in revisions:
                    for name in UPSTREAM_NAMES:
                        url = f"{raw}/{revision}/{name}"
                        content = fetch(url)
                        if content is None:
                            continue
                        digest = hashlib.sha256(content.encode()).hexdigest()
                        texts[digest] = content
                        fetched[source] = {"name": name, "source": url, "sha256": digest}
                        break
                    if source in fetched:
                        break
                else:
                    raise SystemExit("missing_upstream_notice:" + package["name"])
            entries = [fetched[source]]
        rows.append({
            "name": package["name"],
            "version": package["version"],
            "license": package["license"],
            "source": ("CruxCoach repository" if package["name"] == "cruxcoach-marmot"
                       else package["source"] or "MDK pinned local path"),
            "repository": package["repository"],
            "notices": entries,
        })
    output = ROOT / "licenses"
    (output / "dependencies.json").write_text(json.dumps(rows, indent=2, sort_keys=True) + "\n")
    notices = ("CruxCoach Marmot native dependency notices\n\n"
               "Generated from locked Cargo runtime/build sources. SPDX expressions and exact source\n"
               "references are in dependencies.json. Shared license texts are listed once by hash.\n\n")
    for row in rows:
        notices += f"{row['name']} {row['version']} ({row['license']})\n"
        notices += "".join("  notice SHA256 " + n["sha256"] + "\n" for n in row["notices"])
    for digest, content in sorted(texts.items()):
        notices += "\n" + "=" * 72 + "\nNOTICE SHA256 " + digest + "\n" + "=" * 72 + "\n" + content + "\n"
    (output / "THIRD-PARTY-NOTICES.txt").write_text(notices)
    lock = hashlib.sha256((ROOT / "Cargo.lock").read_bytes()).hexdigest()
    (output / "cargo-lock.sha256").write_text(lock + "\n")
    print(json.dumps({"packages": len(rows), "unique_notices": len(texts), "notice_bytes": len(notices.encode())}))


if __name__ == "__main__":
    main()
