import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from feature_identity import identity_for


class FeatureIdentityTest(unittest.TestCase):
    def test_legacy_fips_mapping_is_stable(self) -> None:
        identity = identity_for("feat/board-cell-mesh-mvp-20260814")
        self.assertEqual(identity.track, "fips")
        self.assertEqual(identity.package, "com.cruxcoach.android.dev.feat.board_cell_mesh_mvp")
        self.assertEqual(identity.label, "FIPS")

    def test_new_branches_are_unique_and_deterministic(self) -> None:
        first = identity_for("feat/board-mesh")
        self.assertEqual(first, identity_for("feat/board-mesh"))
        self.assertNotEqual(first.track, identity_for("feat/board_mesh").track)
        self.assertNotEqual(first.package, identity_for("feat/board_mesh").package)
        self.assertTrue(first.track.startswith("feat-board-mesh-"))
        self.assertTrue(first.package.startswith("com.cruxcoach.android.dev.f_"))
        self.assertEqual(first.label, "board-mesh")

    def test_launcher_label_is_language_independent(self) -> None:
        android_app = Path(__file__).resolve().parents[1] / "androidApp"
        manifest = (android_app / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        gradle = (android_app / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('android:label="${appLabel}"', manifest)
        self.assertIn('manifestPlaceholders["appLabel"] = featureLabel!!', gradle)

    def test_rejects_non_feature_branches(self) -> None:
        with self.assertRaises(ValueError):
            identity_for("main")

    def test_metadata_verification_rejects_changed_apk(self) -> None:
        script = Path(__file__).with_name("feature_identity.py")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "feature.apk"
            metadata = root / "publication.json"
            apk.write_bytes(b"first")
            subprocess.run(
                ["python3", str(script), "--branch", "feat/test", "--write-metadata", str(metadata),
                 "--apk", str(apk), "--commit", "a" * 40, "--version-code", "1000001"],
                check=True,
            )
            self.assertEqual(json.loads(metadata.read_text())["commit"], "a" * 40)
            apk.write_bytes(b"changed")
            result = subprocess.run(
                ["python3", str(script), "--branch", "feat/test", "--verify-metadata", str(metadata),
                 "--apk", str(apk), "--commit", "a" * 40],
                check=False,
            )
            self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
