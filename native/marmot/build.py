#!/usr/bin/env python3
"""Build the pinned JNI library only. Never builds, signs or installs an APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import prepare

ROOT = Path(__file__).resolve().parent


def adapter_digest():
    digest = hashlib.sha256()
    files = sorted((ROOT / "src").rglob("*.rs")) + [ROOT / name for name in (
        "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "build.py", "prepare.py", "mdk-extension.patch", "mdk-tree.sha256")]
    for path in sorted(files):
        digest.update(path.relative_to(ROOT).as_posix().encode() + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-ndk", type=Path)
    parser.add_argument("--harness", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target-dir", type=Path, help="Separate build directory for clean reproduction")
    args = parser.parse_args()
    prepare.main()
    before = adapter_digest()
    if (ROOT / "licenses/cargo-lock.sha256").read_text().strip() != hashlib.sha256((ROOT / "Cargo.lock").read_bytes()).hexdigest():
        raise SystemExit("Refresh and review native license inventory for the changed Cargo.lock")
    env = os.environ.copy()
    target_dir = (args.target_dir or ROOT / "target").resolve()
    command = ["cargo", "build", "--locked", "--lib", "--target-dir", str(target_dir)]
    env["SOURCE_DATE_EPOCH"] = "1789066191" # Qualified upstream commit timestamp.
    mappings = [(ROOT.parents[1], "/src/cruxcoach"), (Path.home() / ".cargo", "/cargo"), (target_dir, "/build")]
    env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    rustflags = [f"--remap-path-prefix={source}={target}" for source, target in mappings]
    if args.harness:
        if args.android_ndk:
            raise SystemExit("Harness identities must never be packaged for Android")
        command += ["--features", "local-harness"]
    if args.android_ndk:
        ndk = args.android_ndk.resolve()
        revision = (ndk / "source.properties").read_text()
        if "27.2.12479018" not in revision:
            raise SystemExit("Expected Android NDK 27.2.12479018")
        llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        env["CC_aarch64_linux_android"] = str(llvm / "aarch64-linux-android28-clang")
        env["AR_aarch64_linux_android"] = str(llvm / "llvm-ar")
        env["RANLIB_aarch64_linux_android"] = str(llvm / "llvm-ranlib")
        env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = env["CC_aarch64_linux_android"]
        env["ANDROID_NDK_HOME"] = str(ndk)
        rustflags += ["-C link-arg=-Wl,--exclude-libs,ALL", "-C link-arg=-Wl,-z,max-page-size=16384"]
        env["CFLAGS_aarch64_linux_android"] = " ".join(f"-ffile-prefix-map={source}={target}" for source, target in mappings + [(ndk, "/ndk")])
        command += ["--release", "--target", "aarch64-linux-android"]
        source = target_dir / "aarch64-linux-android/release/libcruxcoach_marmot.so"
        destination = args.output / "arm64-v8a/libcruxcoach_marmot.so"
    else:
        source = target_dir / "debug/libcruxcoach_marmot.so"
        destination = args.output / "libcruxcoach_marmot.so"
    env["RUSTFLAGS"] = " ".join(rustflags)
    subprocess.run(command, cwd=ROOT, env=env, check=True)
    if adapter_digest() != before:
        raise SystemExit("Native adapter source changed during the build; rebuild a coherent input tree")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    manifest = {
        "adapter_source_sha256": before,
        "notices_sha256": hashlib.sha256((ROOT / "licenses/THIRD-PARTY-NOTICES.txt").read_bytes()).hexdigest(),
        "mdk_revision": prepare.REVISION,
        "mdk_patched_tree_sha256": (ROOT / "mdk-tree.sha256").read_text().strip(),
        "rustc": subprocess.check_output(["rustc", "--version", "--verbose"], cwd=ROOT, text=True).strip(),
        "features": ["local-harness"] if args.harness else [],
        "ndk": "27.2.12479018" if args.android_ndk else None,
        "android_api": 28 if args.android_ndk else None,
        "source_date_epoch": env["SOURCE_DATE_EPOCH"],
        "mdk_archive_sha256": prepare.ARCHIVE_SHA256,
        "patch_sha256": hashlib.sha256((ROOT / "mdk-extension.patch").read_bytes()).hexdigest(),
        "cargo_lock_sha256": hashlib.sha256((ROOT / "Cargo.lock").read_bytes()).hexdigest(),
        "library_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
        "target": "aarch64-linux-android" if args.android_ndk else "x86_64-unknown-linux-gnu",
    }
    destination.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
