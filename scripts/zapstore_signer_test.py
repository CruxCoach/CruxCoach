#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().with_name("zapstore-signer.py")
SPEC = importlib.util.spec_from_file_location("zapstore_signer", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
zapstore_signer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(zapstore_signer)


PUBLISHER_HEX = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
PUBLISHER_NPUB = "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s"
# Split the well-known private key 1 fixture so repository secret scanners do
# not mistake test data for a committed production nsec.
TEST_NSEC = "n" + "sec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl"
TEST_NPUB = "npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d"


class ZapstoreSignerTest(unittest.TestCase):
    def write_private_file(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        path.chmod(0o600)

    def provision_bunker_fixture(self, root: Path, publisher_npub: str = PUBLISHER_NPUB):
        config_home = root / "config"
        env_file = root / "release.env"
        config_file = root / "zapstore.yaml"
        bunker = (
            f"bunker://{PUBLISHER_HEX}"
            "?relay=wss://relay.example&secret=one-time-connection-secret"
        )
        self.write_private_file(env_file, f"SIGN_WITH={bunker}\n")
        config_file.write_text(f"name: CruxCoach\npubkey: {publisher_npub}\n", encoding="utf-8")
        client_key = config_home / "zsp" / "bunker-keys" / f"{PUBLISHER_HEX}.key"
        self.write_private_file(client_key, "11" * 32 + "\n")
        return config_home, env_file, config_file, bunker

    def test_raw_bunker_value_is_preserved_and_matches_publisher(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            config_home, env_file, config_file, bunker = self.provision_bunker_fixture(root)
            with patch.dict(os.environ, {"XDG_CONFIG_HOME": str(config_home)}):
                sign_with, signer_type, publisher = zapstore_signer.preflight(
                    env_file, config_file
                )
            self.assertEqual(sign_with, bunker)
            self.assertIn("&secret=", sign_with)
            self.assertEqual(signer_type, "NIP-46 bunker")
            self.assertEqual(publisher, PUBLISHER_NPUB)

    def test_bunker_publisher_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            config_home, env_file, config_file, _ = self.provision_bunker_fixture(
                root, TEST_NPUB
            )
            with patch.dict(os.environ, {"XDG_CONFIG_HOME": str(config_home)}):
                with self.assertRaisesRegex(
                    zapstore_signer.PreflightError, "different publisher"
                ):
                    zapstore_signer.preflight(env_file, config_file)

    def test_npub_and_browser_are_not_headless_publishers(self) -> None:
        with self.assertRaisesRegex(zapstore_signer.PreflightError, "unsigned events"):
            zapstore_signer.resolve_signer_public_key(PUBLISHER_NPUB)
        with self.assertRaisesRegex(zapstore_signer.PreflightError, "interactive"):
            zapstore_signer.resolve_signer_public_key("browser")

    def test_nsec_derives_the_expected_public_key(self) -> None:
        signer_type, public_key = zapstore_signer.resolve_signer_public_key(TEST_NSEC)
        self.assertEqual(signer_type, "local nsec")
        self.assertEqual(public_key, zapstore_signer.decode_nip19(TEST_NPUB, "npub"))

    def test_shell_quotes_and_duplicate_assignments_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            quoted = root / "quoted.env"
            duplicated = root / "duplicated.env"
            self.write_private_file(quoted, "SIGN_WITH='bunker://example'\n")
            self.write_private_file(
                duplicated,
                "SIGN_WITH=browser\nSIGN_WITH=browser\n",
            )
            with self.assertRaisesRegex(zapstore_signer.PreflightError, "without shell quotes"):
                zapstore_signer.read_sign_with(quoted)
            with self.assertRaisesRegex(zapstore_signer.PreflightError, "more than once"):
                zapstore_signer.read_sign_with(duplicated)

    def test_secret_files_must_not_be_group_readable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            env_file = Path(temporary_directory) / "release.env"
            env_file.write_text("SIGN_WITH=browser\n", encoding="utf-8")
            env_file.chmod(0o640)
            with self.assertRaisesRegex(zapstore_signer.PreflightError, "group or others"):
                zapstore_signer.read_sign_with(env_file)


if __name__ == "__main__":
    unittest.main()
