"""Signer checks of flows/run-matching-debug-migration.sh across apksigner output formats."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "flows/run-matching-debug-migration.sh"
KEY = "0a52788e289fa7387cf41460889bd2eab6a76d37d61644837b08cfdb9246cb9a"
OTHER = "71c64543b7241f8da8199ed06d5079c7a328b31bbcc8249a6fc84b5879558db9"
STAMP = "eb0484bf670469455430af586afcc4c56f9d2436eef5d0394eacc273f3f56e32"

# `apksigner verify --print-certs` as printed by build-tools 36.0.0 and 37.0.0.
BUILD_TOOLS_36 = (
    "Signer #1 certificate DN: CN=signlab\n"
    f"Signer #1 certificate SHA-256 digest: {KEY}\n"
    f"Signer #1 certificate SHA-1 digest: {'d' * 40}\n"
    f"Source Stamp Signer certificate SHA-256 digest: {STAMP}\n"
)
BUILD_TOOLS_37 = (
    "V3.0 Signer: certificate DN: CN=signlab\n"
    f"V3.0 Signer: certificate SHA-256 digest: {KEY}\n"
    f"V3.0 Signer: certificate SHA-1 digest: {'d' * 40}\n"
    f"Source Stamp Signer: certificate SHA-256 digest: {STAMP}\n"
)

FAKE_AAPT = """#!/usr/bin/env bash
[[ "$1 $2" == "dump badging" ]] || exit 64
case "$(basename "$3")" in
    old.apk) printf "package: name='com.cruxcoach.android' versionCode='7' versionName='0.2.1'\\n"
             printf "application-debuggable\\n" ;;
    new.apk) printf "package: name='com.cruxcoach.android' versionCode='8' versionName='0.2.2'\\n" ;;
    *) exit 65 ;;
esac
"""
# Prints the fixture stored next to the APK, so each test picks the apksigner output.
FAKE_APKSIGNER = """#!/usr/bin/env bash
[[ "$1" == "verify" ]] || exit 64
case "$2" in
    --verbose) exit 0 ;;
    --print-certs) cat "$3.certs" ;;
    *) exit 64 ;;
esac
"""


class MatchingDebugMigrationSignerTest(unittest.TestCase):
    def preflight(self, old_certs: str, new_certs: str) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, body in (("aapt", FAKE_AAPT), ("apksigner", FAKE_APKSIGNER)):
                tool = root / name
                tool.write_text(body, encoding="utf-8")
                tool.chmod(0o700)
            for name, certs in (("old.apk", old_certs), ("new.apk", new_certs)):
                (root / name).write_bytes(name.encode())
                (root / f"{name}.certs").write_text(certs, encoding="utf-8")
            env = dict(os.environ, AAPT=str(root / "aapt"), APKSIGNER=str(root / "apksigner"))
            return subprocess.run(
                ["bash", str(SCRIPT), "--preflight-only",
                 "--old-apk", str(root / "old.apk"), "--new-apk", str(root / "new.apk")],
                env=env, capture_output=True, text=True, check=False,
            )

    def assert_passes(self, old_certs: str, new_certs: str) -> None:
        result = self.preflight(old_certs, new_certs)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS: v0.2.1 assembleDebug", result.stdout)

    def assert_fails(self, old_certs: str, new_certs: str, message: str) -> None:
        result = self.preflight(old_certs, new_certs)
        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn(message, result.stderr)

    def test_reads_build_tools_36_and_37_output(self):
        self.assert_passes(BUILD_TOOLS_36, BUILD_TOOLS_36)
        self.assert_passes(BUILD_TOOLS_37, BUILD_TOOLS_37)
        self.assert_passes(f"V2 Signer: certificate SHA-256 digest: {KEY.upper()}\n", BUILD_TOOLS_37)

    def test_rejects_different_signers(self):
        self.assert_fails(
            BUILD_TOOLS_37,
            BUILD_TOOLS_37.replace(KEY, OTHER),
            "APK public signers differ",
        )

    def test_rejects_zero_several_or_unknown_signer_lines(self):
        rotated = (
            "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: "
            f"{OTHER}\nV3.0 Signer: (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {KEY}\n"
        )
        two_signers = (
            f"Signer #1 certificate SHA-256 digest: {KEY}\n"
            f"Signer #2 certificate SHA-256 digest: {OTHER}\n"
        )
        stamp_only = f"Source Stamp Signer: certificate SHA-256 digest: {STAMP}\n"
        unknown = BUILD_TOOLS_37 + f"V4 Signer [1]: certificate SHA-256 digest: {KEY}\n"
        for certs in (rotated, two_signers, stamp_only, unknown):
            self.assert_fails(certs, BUILD_TOOLS_37, "old APK needs exactly one readable public signer")


if __name__ == "__main__":
    unittest.main()
