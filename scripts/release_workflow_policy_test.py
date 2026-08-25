"""Regression checks for the self-hosted production release trust boundary."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ReleaseWorkflowPolicyTest(unittest.TestCase):
    def test_github_release_is_main_only_and_environment_protected(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        job = workflow.split("\n  release:\n", 1)[1]

        self.assertIn("if: github.ref == 'refs/heads/main'", job)
        self.assertIn("environment: release", job)
        self.assertIn("ref: refs/heads/main", job)

        guard = job.index("- name: Require protected main ref")
        checkout = job.index("- name: Checkout")
        tests = job.index("- name: Run unit tests")
        self.assertLess(guard, checkout)
        self.assertLess(checkout, tests)

    def test_unprotected_forgejo_release_fallback_is_retired(self):
        self.assertFalse((ROOT / ".forgejo/workflows/release.yml").exists())


if __name__ == "__main__":
    unittest.main()
