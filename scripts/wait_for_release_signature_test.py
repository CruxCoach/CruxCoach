#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().with_name("wait-for-release-signature.sh")
TAG = "v0.2.5"


class WaitForReleaseSignatureTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.drop = self.root / "drop"
        self.drop.mkdir()
        self.unsigned = self.root / "androidApp-release-unsigned.apk"
        self.unsigned.write_bytes(b"unsigned apk bytes")
        self.out = self.root / "work" / "signed.apk"
        self.out.parent.mkdir()
        self.folder = self.drop / TAG

    def run_script(self, *extra: str, timeout: str = "3", env: dict | None = None) -> subprocess.CompletedProcess:
        environment = dict(os.environ, CRUXCOACH_RELEASE_DROP=str(self.drop))
        environment.update(env or {})
        return subprocess.run(
            ["bash", str(SCRIPT), TAG, str(self.unsigned), str(self.out),
             "--timeout", timeout, "--interval", "0.1", *extra],
            capture_output=True, text=True, env=environment, timeout=30,
        )

    def after_hand_over(self, action) -> None:
        """Run action in the background once unsigned.apk is in the drop folder, as the PC would."""

        def handed_over() -> bool:
            try:
                return (self.folder / "unsigned.apk").read_bytes() == self.unsigned.read_bytes()
            except OSError:
                return False

        def wait_then_act():
            deadline = time.monotonic() + 20
            while not handed_over() and time.monotonic() < deadline:
                time.sleep(0.02)
            action()

        thread = threading.Thread(target=wait_then_act)
        thread.start()
        self.addCleanup(thread.join)

    def sign_later(self, data: bytes, sidecar: str | None = None, delay: float = 0.3) -> None:
        def drop_signature():
            time.sleep(delay)
            (self.folder / "signed.apk").write_bytes(data)
            (self.folder / "signed.apk.sha256").write_text(
                sidecar if sidecar is not None else f"{hashlib.sha256(data).hexdigest()}  signed.apk\n"
            )

        self.after_hand_over(drop_signature)

    def test_hands_over_the_unsigned_apk_and_takes_the_signed_one_back(self):
        self.sign_later(b"signed apk bytes")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.folder / "unsigned.apk").read_bytes(), b"unsigned apk bytes")
        self.assertEqual(
            (self.folder / "unsigned.apk.sha256").read_text(),
            f"{hashlib.sha256(b'unsigned apk bytes').hexdigest()}  unsigned.apk\n",
        )
        self.assertEqual(self.out.read_bytes(), b"signed apk bytes")
        self.assertIn(f"Signed APK received: SHA-256 {hashlib.sha256(b'signed apk bytes').hexdigest()}", result.stdout)
        self.assertEqual(sorted(p.name for p in self.folder.iterdir()), [
            "signed.apk", "signed.apk.sha256", "unsigned.apk", "unsigned.apk.sha256",
        ])

    def test_prints_one_hint_without_the_hosts_path(self):
        self.sign_later(b"signed")
        result = self.run_script()
        hints = [line for line in result.stdout.splitlines() if line.startswith("::notice")]
        self.assertEqual(len(hints), 1, result.stdout)
        self.assertIn(f"{TAG}/unsigned.apk", hints[0])
        self.assertIn(hashlib.sha256(b"unsigned apk bytes").hexdigest(), hints[0])
        self.assertNotIn(str(self.drop), result.stdout + result.stderr)

    def test_a_bare_uppercase_digest_is_accepted(self):
        self.sign_later(b"signed", sidecar=hashlib.sha256(b"signed").hexdigest().upper() + "\n")
        self.assertEqual(self.run_script().returncode, 0)

    def test_times_out_without_a_signature(self):
        started = time.monotonic()
        result = self.run_script(timeout="1")
        self.assertEqual(result.returncode, 1)
        self.assertIn(f"no signed APK for {TAG} within 1 s", result.stderr)
        self.assertLess(time.monotonic() - started, 10)
        self.assertFalse(self.out.exists())

    def test_a_signed_apk_that_does_not_match_its_sidecar_is_not_taken(self):
        self.sign_later(b"signed", sidecar="0" * 64 + "  signed.apk\n", delay=0)
        result = self.run_script(timeout="2")
        self.assertEqual(result.returncode, 1)
        self.assertIn("signed.apk.sha256 says " + "0" * 64, result.stderr)
        self.assertFalse(self.out.exists())
        self.assertFalse(Path(f"{self.out}.part").exists())

    def test_waits_while_the_copy_is_still_in_progress(self):
        data = b"the whole signed apk"

        def copy_slowly():
            # The sidecar arrives first, the APK only half written.
            (self.folder / "signed.apk.sha256").write_text(hashlib.sha256(data).hexdigest() + "\n")
            (self.folder / "signed.apk").write_bytes(data[:5])
            time.sleep(0.6)
            (self.folder / "signed.apk").write_bytes(data)

        self.after_hand_over(copy_slowly)
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.out.read_bytes(), data)

    def test_a_signature_from_an_earlier_run_is_removed_first(self):
        self.folder.mkdir()
        (self.folder / "signed.apk").write_bytes(b"old signature")
        (self.folder / "signed.apk.sha256").write_text(hashlib.sha256(b"old signature").hexdigest() + "\n")
        result = self.run_script(timeout="1")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Removed signed.apk left from an earlier run", result.stdout)
        self.assertFalse((self.folder / "signed.apk").exists())
        self.assertFalse(self.out.exists())

    def test_an_unsigned_apk_from_an_earlier_run_is_replaced_before_the_hint(self):
        self.folder.mkdir()
        (self.folder / "unsigned.apk").write_bytes(b"old build")
        self.sign_later(b"signed")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Removed unsigned.apk left from an earlier run", result.stdout)
        self.assertEqual((self.folder / "unsigned.apk").read_bytes(), b"unsigned apk bytes")

    def test_refuses_to_run_without_its_setup(self):
        result = self.run_script(env={"CRUXCOACH_RELEASE_DROP": ""})
        self.assertEqual(result.returncode, 2)
        self.assertIn("CRUXCOACH_RELEASE_DROP must be set", result.stderr)

        result = self.run_script(env={"CRUXCOACH_RELEASE_DROP": str(self.root / "missing")})
        self.assertEqual(result.returncode, 2)
        self.assertIn("does not exist", result.stderr)

        self.unsigned.unlink()
        self.assertEqual(self.run_script().returncode, 2)

    def test_the_tag_cannot_name_another_folder(self):
        for tag in ("../v0.2.5", "v0.2.5/..", "0.2.5", "v0.2"):
            result = subprocess.run(
                ["bash", str(SCRIPT), tag, str(self.unsigned), str(self.out)],
                capture_output=True, text=True, timeout=30,
                env=dict(os.environ, CRUXCOACH_RELEASE_DROP=str(self.drop)),
            )
            self.assertEqual(result.returncode, 2, tag)
            self.assertIn("tag must look like v1.2.3", result.stderr)
        self.assertEqual(list(self.drop.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
