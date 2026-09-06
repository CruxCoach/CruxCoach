"""Regression checks for secretless, isolated feature CI publication."""
import hashlib
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FeatureCIBoundaryTest(unittest.TestCase):
    def test_build_cannot_request_publisher_credentials(self):
        workflow = (ROOT / '.github/workflows/feature-publish.yml').read_text()
        build = workflow.split('\n  build:\n', 1)[1].split('\n  publish:\n', 1)[0]
        self.assertNotIn('id-token: write', build)
        self.assertNotIn('environment:', build)
        self.assertNotIn('secrets.', workflow)
        self.assertNotIn('APKTRACK_AGENT_TOKEN', workflow)
        self.assertEqual(workflow.count('id-token: write'), 2)

    def test_reservation_and_publication_use_job_identity(self):
        workflow = (ROOT / '.github/workflows/feature-publish.yml').read_text()
        self.assertEqual(workflow.count('--github-ci-run "$SOURCE_RUN"'), 2)
        self.assertIn('apktrack reserve-version --config .apktrack/project.toml', workflow)
        self.assertIn('apktrack publish-build', workflow)
        self.assertIn('Recheck current developer authorization', workflow)
        self.assertIn('receipt_delivered', workflow)

    def test_installed_client_matches_reviewed_bytes(self):
        wheel = ROOT / '.github/vendor/apktrack-0.3.0-py3-none-any.whl'
        digest = hashlib.sha256(wheel.read_bytes()).hexdigest()
        workflow = (ROOT / '.github/workflows/feature-publish.yml').read_text()
        self.assertEqual(workflow.count(digest), 2)
        self.assertNotIn('WHEEL_URL:', workflow)
        self.assertEqual(workflow.count('--require-hashes --only-binary=:all:'), 2)
        lock = (ROOT / '.github/vendor/apktrack-ci-requirements.txt').read_text()
        self.assertIn('--hash=sha256:' + digest, lock)


if __name__ == '__main__':
    unittest.main()
