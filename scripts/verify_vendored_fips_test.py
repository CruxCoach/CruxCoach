"""CI gate for the vendored FIPS tree.

The native build compiles whatever is in `native/fips`, so an edit there is an
edit to a dependency that no lockfile checksum covers. This runs the offline
half of `verify_vendored_fips.py` on every CI run, which fails the build if the
vendored source has drifted from the digest recorded in `native/fips/VENDOR.toml`.

The upstream half (`--upstream <clone>`) needs network access and is a
maintainer step, documented in the FEAT-059 architecture notes.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import verify_vendored_fips  # noqa: E402


class VendoredFipsTest(unittest.TestCase):
    def test_vendored_tree_matches_its_recorded_digest(self):
        metadata = verify_vendored_fips.load_metadata(
            verify_vendored_fips.VENDOR_DIR / "VENDOR.toml"
        )
        problems = verify_vendored_fips.verify_offline(metadata)
        self.assertEqual(problems, [], "\n".join(problems))

    def test_every_recorded_patch_is_present(self):
        metadata = verify_vendored_fips.load_metadata(
            verify_vendored_fips.VENDOR_DIR / "VENDOR.toml"
        )
        for patch in metadata["patches"]:
            path = verify_vendored_fips.VENDOR_DIR / "patches" / patch
            self.assertTrue(path.is_file(), f"missing patch: {path}")
            self.assertGreater(path.stat().st_size, 0, f"empty patch: {path}")

    def test_the_cargo_manifest_points_at_the_vendored_crate(self):
        """A stray `git`/`rev` pin would silently reintroduce the upstream
        retention dependency vendoring exists to remove."""
        manifest = (
            verify_vendored_fips.REPO_ROOT / "native" / "fips-bridge" / "Cargo.toml"
        ).read_text(encoding="utf-8")
        self.assertIn('fips = { path = "../fips" }', manifest)
        self.assertNotIn("github.com/jmcorgan/fips", manifest)


if __name__ == "__main__":
    unittest.main()
