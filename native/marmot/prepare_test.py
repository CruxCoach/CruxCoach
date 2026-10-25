"""Security checks of the source-cache verifier; no network or real credentials."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import prepare


class SourceCacheTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.previous = prepare.ROOT
        self.root = Path(self.temporary.name)
        prepare.ROOT = self.root
        self.source = self.root / ".mdk"
        self.source.mkdir()
        (self.source / "code.rs").write_text("synthetic source")
        (self.root / "mdk-extension.patch").write_text("synthetic patch")
        fingerprint = prepare.REVISION + ":" + hashlib.sha256(b"synthetic patch").hexdigest()
        self.marker = self.source / ".cruxcoach-source"
        self.marker.write_text(fingerprint)
        self.original = prepare.source_digest(self.source)
        (self.root / "mdk-tree.sha256").write_text(self.original + "\n")

    def tearDown(self):
        prepare.ROOT = self.previous
        self.temporary.cleanup()

    def test_valid_cache_then_changed_source_refused(self):
        prepare.main()
        (self.source / "code.rs").write_text("changed synthetic source")
        with self.assertRaises(SystemExit):
            prepare.main()

    def test_only_root_metadata_marker_is_excluded_from_source_hash(self):
        self.marker.write_text("different metadata")
        self.assertEqual(self.original, prepare.source_digest(self.source))
        nested = self.source / "nested"
        nested.mkdir()
        (nested / ".cruxcoach-source").write_text("must be authenticated too")
        self.assertNotEqual(self.original, prepare.source_digest(self.source))

    def test_cache_marker_symlink_and_source_symlink_escape_are_refused(self):
        outside = self.root / "outside"
        outside.write_bytes(self.marker.read_bytes())
        self.marker.unlink()
        self.marker.symlink_to(outside)
        with self.assertRaises(SystemExit):
            prepare.main()
        self.marker.unlink()
        (self.source / "link.rs").symlink_to(outside)
        with self.assertRaises(SystemExit):
            prepare.source_digest(self.source)


if __name__ == "__main__":
    unittest.main()
