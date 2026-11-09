#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import os
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().with_name("verify-release-signature.py")
SPEC = importlib.util.spec_from_file_location("verify_release_signature", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


# Certificate digests of throwaway test keys, not of any CruxCoach key.
OLD = "4c66b0a8059d164324a1a00667b9e1dbafab9b4f6a05817b7884df90d576f263"
NEW = "fc85193abd5716ef94f3d761cea79e6bd384eaee7a07a8b8d8044ddbf5293422"

# Real output for a v3-only APK signed by NEW with the lineage OLD -> NEW.
VERIFY_36 = f"""Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=new
Signer #1 certificate SHA-256 digest: {NEW}
Signer #1 certificate SHA-1 digest: 46091fb29494e57615a49ff725c458e44c882c70
Signer #1 certificate MD5 digest: 2073c9d25d973173b2aadb1e4210e8dc
Signer #1 key algorithm: EC
Signer #1 key size (bits): 256
Signer #1 public key SHA-256 digest: a4ac3c1442e7bc5c96b5148dcc4d5e58be1201b547b995b3d9d362a15ad2b419
"""
VERIFY_37 = f"""Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
V3.0 Signer: certificate DN: CN=new
V3.0 Signer: certificate SHA-256 digest: {NEW}
V3.0 Signer: certificate SHA-1 digest: 46091fb29494e57615a49ff725c458e44c882c70
V3.0 Signer: key algorithm: EC
V3.0 Signer: public key SHA-256 digest: a4ac3c1442e7bc5c96b5148dcc4d5e58be1201b547b995b3d9d362a15ad2b419
"""
LINEAGE = f"""Signer #1 in lineage certificate DN: CN=old
Signer #1 in lineage certificate SHA-256 digest: {OLD}
Signer #1 in lineage certificate SHA-1 digest: da5f6386069afd6729f2b5fa4fc6e96d48f4bc55
Has installed data capability: true
Has rollback capability      : false
Signer #2 in lineage certificate DN: CN=new
Signer #2 in lineage certificate SHA-256 digest: {NEW}
Has installed data capability: true
"""


def write_zip(path: Path, entries: dict[str, tuple[bytes, int]]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, (data, compression) in entries.items():
            archive.writestr(zipfile.ZipInfo(name), data, compress_type=compression)


def with_signing_block(data: bytes, block_ids: list[int]) -> bytes:
    """Put an APK Signing Block with these IDs in front of the central directory."""

    end = data.rfind(b"PK\x05\x06")
    central_directory = struct.unpack("<I", data[end + 16:end + 20])[0]
    pairs = b"".join(struct.pack("<QI", 4 + 8, block_id) + b"\0" * 8 for block_id in block_ids)
    size = len(pairs) + 8 + 16
    block = struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"
    moved = central_directory + len(block)
    eocd = data[end:end + 16] + struct.pack("<I", moved) + data[end + 20:]
    return data[:central_directory] + block + data[central_directory:end] + eocd


ENTRIES = {
    "AndroidManifest.xml": (b"manifest" * 40, zipfile.ZIP_DEFLATED),
    "resources.arsc": (b"resources" * 50, zipfile.ZIP_STORED),
    "classes.dex": (b"dex" * 300, zipfile.ZIP_DEFLATED),
}


class EntryComparisonTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.unsigned = self.root / "unsigned.apk"
        self.signed = self.root / "signed.apk"
        write_zip(self.unsigned, ENTRIES)

    def test_the_same_entries_in_another_order_pass(self):
        write_zip(self.signed, dict(reversed(list(ENTRIES.items()))))
        self.assertEqual(verify.compare_entries(self.unsigned, self.signed), [])

    def test_a_signing_block_alone_passes(self):
        self.signed.write_bytes(with_signing_block(self.unsigned.read_bytes(), [verify.V3_BLOCK]))
        self.assertEqual(verify.compare_entries(self.unsigned, self.signed), [])

    def test_changed_bytes_are_refused(self):
        changed = dict(ENTRIES, **{"classes.dex": (b"dex" * 299 + b"evil", zipfile.ZIP_DEFLATED)})
        write_zip(self.signed, changed)
        self.assertEqual(
            verify.compare_entries(self.unsigned, self.signed),
            ["classes.dex has other bytes in the signed APK"],
        )

    def test_v1_signature_files_are_extra_entries(self):
        write_zip(self.signed, dict(ENTRIES, **{
            "META-INF/MANIFEST.MF": (b"Manifest-Version: 1.0\r\n", zipfile.ZIP_DEFLATED),
            "META-INF/CERT.SF": (b"sf", zipfile.ZIP_DEFLATED),
        }))
        self.assertEqual(verify.compare_entries(self.unsigned, self.signed), [
            "the signed APK adds META-INF/CERT.SF",
            "the signed APK adds META-INF/MANIFEST.MF",
        ])

    def test_a_missing_entry_is_refused(self):
        write_zip(self.signed, {k: v for k, v in ENTRIES.items() if k != "classes.dex"})
        self.assertEqual(verify.compare_entries(self.unsigned, self.signed), ["the signed APK lacks classes.dex"])

    def test_recompression_is_refused(self):
        write_zip(self.signed, dict(ENTRIES, **{"resources.arsc": (ENTRIES["resources.arsc"][0], zipfile.ZIP_DEFLATED)}))
        self.assertEqual(
            verify.compare_entries(self.unsigned, self.signed),
            ["resources.arsc is compressed differently in the signed APK"],
        )

    def test_a_duplicate_entry_is_refused(self):
        with zipfile.ZipFile(self.signed, "w") as archive:
            for name, (data, compression) in ENTRIES.items():
                archive.writestr(zipfile.ZipInfo(name), data, compress_type=compression)
            with self.assertWarns(UserWarning):
                archive.writestr(zipfile.ZipInfo("classes.dex"), b"other", compress_type=zipfile.ZIP_DEFLATED)
        self.assertIn("signed.apk: entry classes.dex appears twice", verify.compare_entries(self.unsigned, self.signed))

    def test_not_a_zip_is_refused(self):
        self.signed.write_bytes(b"not an apk")
        problems = verify.compare_entries(self.unsigned, self.signed)
        self.assertEqual(len(problems), 1)
        self.assertIn("cannot read the APKs as zip files", problems[0])

    def test_alignment_rules(self):
        self.assertEqual(verify.required_alignment("lib/arm64-v8a/libx.so", 16384 * 3), 16384)
        self.assertEqual(verify.required_alignment("lib/arm64-v8a/libx.so", 4096 * 3), 4096)
        # Any other stored entry needs only 4 bytes, wherever it happens to sit.
        self.assertEqual(verify.required_alignment("resources.arsc", 16384), 4)
        self.assertEqual(verify.required_alignment("assets/x.bin", 6), 1)


class SigningBlockTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("a.txt", b"a")
        self.zip = buffer.getvalue()
        self.apk = self.root / "a.apk"

    def problems(self, ids: list[int] | None) -> list[str]:
        self.apk.write_bytes(self.zip if ids is None else with_signing_block(self.zip, ids))
        return verify.check_signing_block(self.apk)

    def test_v3_alone_passes(self):
        self.assertEqual(self.problems([verify.V3_BLOCK, 0x42726577]), [])

    def test_v2_next_to_v3_is_refused(self):
        self.assertEqual(
            self.problems([verify.V2_BLOCK, verify.V3_BLOCK]),
            ["the APK Signing Block has a v2 signature; the release is v3 only"],
        )

    def test_v31_is_refused(self):
        problems = self.problems([verify.V3_BLOCK, verify.V31_BLOCK])
        self.assertEqual(len(problems), 1)
        self.assertIn("--rotation-min-sdk-version 28", problems[0])

    def test_no_block_is_refused(self):
        self.assertEqual(self.problems(None), ["the APK Signing Block has no v3 signature"])


class ApksignerOutputTest(unittest.TestCase):
    def test_both_formats_pass(self):
        self.assertEqual(verify.check_verify_output(VERIFY_36, NEW), [])
        self.assertEqual(verify.check_verify_output(VERIFY_37, NEW), [])

    def test_another_signer_is_refused(self):
        problems = verify.check_verify_output(VERIFY_37, OLD)
        self.assertEqual(problems, [f"the signer is not the release certificate {OLD}: {NEW}"])

    def test_v2_is_refused(self):
        output = VERIFY_36.replace("(APK Signature Scheme v2): false", "(APK Signature Scheme v2): true")
        self.assertEqual(verify.check_verify_output(output, NEW), ["v2 signature is present; the release is v3 only"])

    def test_missing_v3_is_refused(self):
        output = VERIFY_37.replace("(APK Signature Scheme v3): true", "(APK Signature Scheme v3): false")
        self.assertEqual(verify.check_verify_output(output, NEW), ["v3 signature is missing; the release is v3 only"])

    def test_two_signers_are_refused(self):
        output = VERIFY_36.replace("Number of signers: 1", "Number of signers: 2") + (
            f"Signer #2 certificate SHA-256 digest: {OLD}\n"
        )
        problems = verify.check_verify_output(output, NEW)
        self.assertIn("expected exactly one signer, apksigner reports 2", problems)
        self.assertEqual(len(problems), 2)

    def test_an_unknown_signer_line_is_refused(self):
        output = VERIFY_37 + f"V9 Mystery: other certificate SHA-256 digest: {OLD}\n"
        self.assertEqual(
            verify.check_verify_output(output, NEW),
            [f"unknown signer line from apksigner: V9 Mystery: other certificate SHA-256 digest: {OLD}"],
        )

    def test_a_source_stamp_is_not_a_signer(self):
        output = VERIFY_37 + f"Source Stamp Signer: certificate SHA-256 digest: {OLD}\n"
        self.assertEqual(verify.check_verify_output(output, NEW), [])

    def test_unknown_output_is_refused(self):
        problems = verify.check_verify_output("Verifies\n", NEW)
        self.assertIn("apksigner did not report the v3 scheme", problems)

    def test_lineage_in_order_passes(self):
        self.assertEqual(verify.check_lineage_output(LINEAGE, [OLD, NEW]), [])

    def test_lineage_in_the_wrong_order_or_length_is_refused(self):
        self.assertEqual(
            verify.check_lineage_output(LINEAGE, [NEW, OLD]),
            [f"the lineage is {OLD} -> {NEW}, expected {NEW} -> {OLD}"],
        )
        self.assertEqual(
            verify.check_lineage_output(LINEAGE, [NEW]),
            [f"the lineage is {OLD} -> {NEW}, expected {NEW}"],
        )
        self.assertEqual(
            verify.check_lineage_output("", [OLD, NEW]),
            [f"the lineage is missing, expected {OLD} -> {NEW}"],
        )

    def test_the_old_certificate_keeps_installed_data_and_loses_rollback(self):
        rollback = LINEAGE.replace("Has rollback capability      : false", "Has rollback capability      : true")
        self.assertEqual(
            verify.check_lineage_output(rollback, [OLD, NEW]),
            [f"{OLD} has the rollback capability in the lineage"],
        )
        # Only the first "installed data" line belongs to the old certificate.
        no_data = LINEAGE.replace("Has installed data capability: true", "Has installed data capability: false", 1)
        self.assertEqual(
            verify.check_lineage_output(no_data, [OLD, NEW]),
            [f"{OLD} lacks the installed data capability in the lineage"],
        )
        unknown = "\n".join(line for line in LINEAGE.splitlines() if not line.startswith("Has "))
        self.assertEqual(verify.check_lineage_output(unknown, [OLD, NEW]), [
            f"apksigner did not report the installed data capability of {OLD}",
            f"apksigner did not report the rollback capability of {OLD}",
        ])


class ArgumentsTest(unittest.TestCase):
    def test_the_placeholder_pin_is_refused(self):
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(io.StringIO()) as errors:
            verify.parse_args([
                "--unsigned", str(SCRIPT), "--signed", str(SCRIPT), "--apksigner", "apksigner",
                "--signer", "0" * 64, "--lineage", OLD, "0" * 64,
            ])
        self.assertEqual(raised.exception.code, 2)
        self.assertIn("still the placeholder", errors.getvalue())
        with self.assertRaises(Exception):
            verify.pin("0" * 64)
        self.assertEqual(verify.pin(NEW.upper()), NEW)


def _sdk_tool(name: str) -> str | None:
    root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not root:
        return None
    found = sorted(
        Path(root, "build-tools").glob(f"*/{name}"),
        key=lambda p: [int(x) if x.isdigit() else 0 for x in p.parent.name.split(".")],
    )
    return str(found[-1]) if found else None


def _android_jar() -> str | None:
    root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    jars = sorted(Path(root, "platforms").glob("android-*/android.jar")) if root else []
    return str(jars[-1]) if jars else None


@unittest.skipUnless(
    _sdk_tool("apksigner") and _sdk_tool("aapt2") and _android_jar() and shutil.which("keytool"),
    "needs the Android SDK (apksigner, aapt2, a platform) and keytool",
)
class RealApksignerTest(unittest.TestCase):
    """The whole check against the newest apksigner on this machine, with throwaway keys."""

    @classmethod
    def setUpClass(cls):
        cls.root = Path(tempfile.mkdtemp())
        cls.apksigner = _sdk_tool("apksigner")
        root = cls.root
        (root / "pw").write_text("test-only\n")
        (root / "pw2").write_text("test-only\n")
        (root / "AndroidManifest.xml").write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.rotation">'
            '<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35"/><application/></manifest>'
        )
        subprocess.run(
            [_sdk_tool("aapt2"), "link", "-o", str(root / "base.apk"), "--manifest",
             str(root / "AndroidManifest.xml"), "-I", _android_jar()],
            check=True, capture_output=True,
        )
        with zipfile.ZipFile(root / "base.apk") as base, zipfile.ZipFile(root / "unsigned.apk", "w") as out:
            for info in base.infolist():
                out.writestr(info, base.read(info.filename))
            out.writestr(zipfile.ZipInfo("classes.dex"), b"dex" * 1000, compress_type=zipfile.ZIP_DEFLATED)
        cls.certs = {}
        for key in ("old", "new"):
            subprocess.run(
                ["keytool", "-genkeypair", "-keystore", str(root / f"{key}.jks"), "-storepass:file", str(root / "pw"),
                 "-keypass:file", str(root / "pw"), "-alias", key, "-keyalg", "EC", "-groupname", "secp256r1",
                 "-validity", "2", "-dname", f"CN={key}"],
                check=True, capture_output=True,
            )
            der = subprocess.run(
                ["keytool", "-exportcert", "-keystore", str(root / f"{key}.jks"), "-storepass:file",
                 str(root / "pw"), "-alias", key],
                check=True, capture_output=True,
            ).stdout
            cls.certs[key] = hashlib.sha256(der).hexdigest()
        subprocess.run(
            [cls.apksigner, "rotate", "--out", str(root / "lineage.bin"),
             "--old-signer", "--ks", str(root / "old.jks"), "--ks-pass", f"file:{root / 'pw'}",
             "--new-signer", "--ks", str(root / "new.jks"), "--ks-pass", f"file:{root / 'pw2'}"],
            check=True, capture_output=True,
        )

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.root)

    def sign(self, name: str, *options: str) -> Path:
        out = self.root / f"{name}.apk"
        subprocess.run(
            [self.apksigner, "sign", *options, "--in", str(self.root / "unsigned.apk"), "--out", str(out)],
            check=True, capture_output=True,
        )
        return out

    def run_check(self, signed: Path, unsigned: bool = True) -> list[str]:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return verify.verify(
                self.root / "unsigned.apk" if unsigned else None, signed, self.apksigner, self.certs["new"],
                [self.certs["old"], self.certs["new"]],
            )

    def test_new_key_alone_v3_only_with_lineage_passes(self):
        signed = self.sign(
            "rotated", "--ks", str(self.root / "new.jks"), "--ks-pass", f"file:{self.root / 'pw'}",
            "--lineage", str(self.root / "lineage.bin"), "--rotation-min-sdk-version", "28",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "false", "--v3-signing-enabled", "true",
        )
        self.assertEqual(self.run_check(signed), [])
        # The by-hand path, which has no unsigned build to compare with.
        self.assertEqual(self.run_check(signed, unsigned=False), [])

    def test_a_lineage_that_lets_the_old_key_roll_back_is_refused(self):
        lineage = self.root / "rollback.bin"
        subprocess.run(
            [self.apksigner, "rotate", "--out", str(lineage),
             "--old-signer", "--ks", str(self.root / "old.jks"), "--ks-pass", f"file:{self.root / 'pw'}",
             "--set-rollback", "true",
             "--new-signer", "--ks", str(self.root / "new.jks"), "--ks-pass", f"file:{self.root / 'pw2'}"],
            check=True, capture_output=True,
        )
        signed = self.sign(
            "rollback", "--ks", str(self.root / "new.jks"), "--ks-pass", f"file:{self.root / 'pw'}",
            "--lineage", str(lineage), "--rotation-min-sdk-version", "28",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "false", "--v3-signing-enabled", "true",
        )
        self.assertEqual(self.run_check(signed), [f"{self.certs['old']} has the rollback capability in the lineage"])

    def test_v2_next_to_v3_is_refused(self):
        # apksigner itself refuses v2 with the new key and a lineage, so this
        # one has no lineage; apksigner verify still reports v2 as false.
        signed = self.sign(
            "with-v2", "--ks", str(self.root / "new.jks"), "--ks-pass", f"file:{self.root / 'pw'}",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
        )
        self.assertIn("the APK Signing Block has a v2 signature; the release is v3 only", self.run_check(signed))

    def test_the_old_key_without_lineage_is_refused(self):
        signed = self.sign(
            "old", "--ks", str(self.root / "old.jks"), "--ks-pass", f"file:{self.root / 'pw'}",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "false", "--v3-signing-enabled", "true",
        )
        problems = self.run_check(signed)
        self.assertIn(f"the signer is not the release certificate {self.certs['new']}: {self.certs['old']}", problems)
        self.assertTrue(any(p.startswith("apksigner lineage failed") for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
