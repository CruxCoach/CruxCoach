from pathlib import Path
import tempfile
import unittest

from scripts.authorized_feature_maintainer import authorized_logins, is_authorized


class AuthorizedFeatureMaintainerTest(unittest.TestCase):
    def test_strips_blank_lines_and_full_line_or_inline_comments(self):
        with tempfile.TemporaryDirectory() as directory:
            allowlist = Path(directory) / "maintainers.txt"
            allowlist.write_text(
                "# maintainers\n  alice  # owner\n\n bob\r\n # mallory\n",
                encoding="utf-8",
            )

            self.assertEqual({"alice", "bob"}, authorized_logins(allowlist))

    def test_ignores_malformed_logins_instead_of_broadening_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            allowlist = Path(directory) / "maintainers.txt"
            allowlist.write_text("good-login\nbad login\n-leading\ntrailing-\n", encoding="utf-8")

            self.assertEqual({"good-login"}, authorized_logins(allowlist))

    def test_authorization_denies_missing_file_malformed_actor_and_non_member(self):
        with tempfile.TemporaryDirectory() as directory:
            allowlist = Path(directory) / "maintainers.txt"
            allowlist.write_text("trusted-owner\n", encoding="utf-8")

            self.assertTrue(is_authorized("trusted-owner", allowlist))
            self.assertFalse(is_authorized("someone-else", allowlist))
            self.assertFalse(is_authorized("trusted-owner # injected", allowlist))
            self.assertFalse(is_authorized("trusted-owner", allowlist.with_name("missing.txt")))


if __name__ == "__main__":
    unittest.main()
