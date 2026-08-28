from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import moon_apk_contract as contract
import moon_latest_version


class MoonApkContractTest(unittest.TestCase):
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
