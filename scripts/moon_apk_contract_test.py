from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import moon_apk_contract as contract
import moon_latest_version


SIGNER = "9903070fe6cf4674e1a8875c07c911aa859a4fee666a8c83a409084d3ec94caf"
ROTATED = "b" * 64
STAMP = "c" * 64

# `apksigner verify --print-certs` output as printed by build-tools 36.0.0 and 37.0.0.
BUILD_TOOLS_36 = f"""\
Signer #1 certificate DN: CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US
Signer #1 certificate SHA-256 digest: {SIGNER}
Signer #1 certificate SHA-1 digest: {"d" * 40}
Signer #1 certificate MD5 digest: {"e" * 32}
Source Stamp Signer certificate DN: CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US
Source Stamp Signer certificate SHA-256 digest: {STAMP}
Source Stamp Signer certificate SHA-1 digest: {"f" * 40}
"""
BUILD_TOOLS_37 = f"""\
V3.0 Signer: certificate DN: CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US
V3.0 Signer: certificate SHA-256 digest: {SIGNER}
V3.0 Signer: certificate SHA-1 digest: {"d" * 40}
V3.0 Signer: certificate MD5 digest: {"e" * 32}
Source Stamp Signer: certificate DN: CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US
Source Stamp Signer: certificate SHA-256 digest: {STAMP}
Source Stamp Signer: certificate SHA-1 digest: {"f" * 40}
"""


class MoonApkContractTest(unittest.TestCase):
    def test_signer_digest_reads_build_tools_36_and_37_output(self):
        self.assertEqual({SIGNER}, contract.parse_signer_digests(BUILD_TOOLS_36))
        self.assertEqual({SIGNER}, contract.parse_signer_digests(BUILD_TOOLS_37))
        for scheme in ("V1", "V2", "V3.0"):
            line = f"{scheme} Signer: certificate SHA-256 digest: {SIGNER.upper()}\n"
            self.assertEqual({SIGNER}, contract.parse_signer_digests(line))

    def test_signer_digest_reports_every_signer_and_rotation(self):
        outputs = (
            f"Signer #1 certificate SHA-256 digest: {SIGNER}\n"
            f"Signer #2 certificate SHA-256 digest: {ROTATED}\n",
            f"V2 Signer #1: certificate SHA-256 digest: {SIGNER}\n"
            f"V2 Signer #2: certificate SHA-256 digest: {ROTATED}\n",
            "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: "
            f"{ROTATED}\nSigner (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {SIGNER}\n",
            "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: "
            f"{ROTATED}\nV3.0 Signer: (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {SIGNER}\n",
        )
        for output in outputs:
            self.assertEqual({SIGNER, ROTATED}, contract.parse_signer_digests(output))
        self.assertFalse(
            contract.apk_identity_matches(
                [{"package": "com.trainingboard.moon", "version_code": 377, "version_name": "1.3.77", "split": None}],
                contract.parse_signer_digests(outputs[-1]),
                "com.trainingboard.moon",
                SIGNER,
            )
        )

    def test_signer_digest_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "no signer certificate found"):
            contract.parse_signer_digests(f"Source Stamp Signer: certificate SHA-256 digest: {STAMP}\n")
        with self.assertRaisesRegex(ValueError, "unrecognized apksigner certificate line"):
            contract.parse_signer_digests(
                f"V3.0 Signer: certificate SHA-256 digest: {SIGNER}\n"
                f"V4 Signer [1]: certificate SHA-256 digest: {ROTATED}\n"
            )

    def test_latest_version_uses_numeric_order(self):
        listing = "Versions: 1.3.9, 1.2.45, 1.3.68, 1.10.2"
        self.assertEqual("1.10.2", moon_latest_version.latest_version(listing))

    def test_parses_base_and_split_badging(self):
        base = contract.parse_badging(
            "package: name='com.trainingboard.moon' versionCode='368' versionName='1.3.68'\n"
        )
        split = contract.parse_badging(
            "package: name='com.trainingboard.moon' versionCode='368' versionName='' split='config.en'\n"
        )
        self.assertEqual(368, base["version_code"])
        self.assertEqual("1.3.68", base["version_name"])
        self.assertIsNone(base["split"])
        self.assertEqual("config.en", split["split"])

    def test_rejects_archive_path_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "bad.xapk"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("../escape.apk", b"not an apk")
            with zipfile.ZipFile(archive_path) as archive:
                with self.assertRaisesRegex(ValueError, "unsafe archive member"):
                    contract.safe_archive_members(archive)

    def test_identity_rejects_a_split_from_a_different_version(self):
        metadata = [
            {
                "package": "com.trainingboard.moon",
                "version_code": 368,
                "version_name": "1.3.68",
                "split": None,
            },
            {
                "package": "com.trainingboard.moon",
                "version_code": 367,
                "version_name": "",
                "split": "config.arm64_v8a",
            },
        ]

        self.assertFalse(
            contract.apk_identity_matches(
                metadata,
                {"a" * 64},
                "com.trainingboard.moon",
                "a" * 64,
            )
        )

    def test_marker_scan_reads_compressed_flutter_aot(self):
        groups = {
            "navigation": ["Logbook"],
            "contract": [" completed, "],
            "missing": ["not present"],
        }
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "split.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(
                    "lib/arm64-v8a/libapp.so",
                    b"binary-prefix Logbook 4 problems (1 completed, 7 tries) binary-suffix",
                )
            found, evidence = contract.scan_markers([apk], groups)
        self.assertEqual({"navigation": True, "contract": True, "missing": False}, found)
        self.assertEqual(["lib/arm64-v8a/libapp.so"], evidence)

    def test_markdown_states_static_limit(self):
        report = {
            "version_name": "1.3.69",
            "version_code": 369,
            "verdict": "compatible-static",
            "new_version": True,
            "package": "com.trainingboard.moon",
            "signer_sha256": "a" * 64,
            "artifact_sha256": "b" * 64,
            "markers": {"logbook": True},
        }
        text = contract.markdown_report(report)
        self.assertIn("does not prove runtime", text)
        self.assertIn("versionCode 369", text)


if __name__ == "__main__":
    unittest.main()
