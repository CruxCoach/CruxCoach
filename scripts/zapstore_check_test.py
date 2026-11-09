#!/usr/bin/env python3

from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().with_name("zapstore-check.py")
SPEC = importlib.util.spec_from_file_location("zapstore_check", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
zapstore_check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(zapstore_check)


CERT = "fc85193abd5716ef94f3d761cea79e6bd384eaee7a07a8b8d8044ddbf5293422"
OLD = "4c66b0a8059d164324a1a00667b9e1dbafab9b4f6a05817b7884df90d576f263"
NPUB = "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s"

# The shape zsp 2.0.1 prints for `zsp check` without a terminal (SPEC.md, Output).
READY = {
    "ok": True,
    "operation": "check",
    "apk": {
        "app_id": "com.cruxcoach.android",
        "name": "CruxCoach",
        "version_name": "0.2.5",
        "version_code": 25,
        "certificate_hash": CERT,
        "hash": "ab" * 32,
    },
    "listed": True,
    "proof": {"owner": NPUB, "expires_at": "2028-11-10T00:00:00Z"},
    "published_version_code": 24,
    "signer": NPUB,
    "authorized": True,
    "next_step": {"command": "zsp publish", "reason": "Publish version 0.2.5 (25)."},
    "warnings": [],
}


def changed(**fields):
    document = copy.deepcopy(READY)
    for key, value in fields.items():
        if key.startswith("apk_"):
            document["apk"][key[4:]] = value
        else:
            document[key] = value
    return document


class ZapstoreCheckTest(unittest.TestCase):
    def test_a_ready_release_passes(self):
        self.assertEqual(zapstore_check.problems_in(READY, CERT), [])

    def test_another_certificate_is_refused(self):
        self.assertEqual(
            zapstore_check.problems_in(changed(apk_certificate_hash=OLD), CERT),
            [f"zsp sees the APK signed by {OLD}, not by the release certificate {CERT}"],
        )

    def test_a_missing_proof_is_refused(self):
        problems = zapstore_check.problems_in(changed(proof=None, authorized=False), CERT)
        self.assertEqual(len(problems), 1)
        self.assertIn("no active C1 proof", problems[0])

    def test_an_unauthorized_signer_is_refused(self):
        self.assertEqual(
            zapstore_check.problems_in(changed(authorized=False), CERT),
            ["SIGN_WITH is neither the owner nor the delegate of the C1 proof"],
        )

    def test_a_version_code_already_published_is_refused(self):
        self.assertEqual(
            zapstore_check.problems_in(changed(published_version_code=25), CERT),
            ["version code 25 is not above the published 25"],
        )

    def test_a_failed_or_foreign_document_is_refused(self):
        for document in ({"ok": False, "operation": "check"}, {"ok": True, "operation": "publish"}, [], None):
            self.assertEqual(
                zapstore_check.problems_in(document, CERT),
                ["zsp check did not report a successful check"],
            )


if __name__ == "__main__":
    unittest.main()
