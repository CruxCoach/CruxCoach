import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/community-publish.yml"


def job(text: str, name: str) -> str:
    match = re.search(rf"^  {name}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", text, re.MULTILINE | re.DOTALL)
    assert match, f"job {name} missing"
    return match.group(1)


class CommunityWorkflowPolicyTest(unittest.TestCase):
    """The fork's code must never run in a job that can see a credential."""

    def setUp(self) -> None:
        self.text = WORKFLOW.read_text()

    def test_only_fork_pull_requests_after_successful_checks(self) -> None:
        authorize = job(self.text, "authorize")
        self.assertIn("github.event.workflow_run.conclusion == 'success'", authorize)
        self.assertIn("github.event.workflow_run.event == 'pull_request'", authorize)
        self.assertIn("head_repository.full_name != github.repository", authorize)

    def test_build_job_runs_fork_code_without_credentials(self) -> None:
        build = job(self.text, "build")
        self.assertNotIn("environment:", build)
        self.assertNotIn("secrets.", build)
        self.assertNotIn("github.token", build)
        self.assertIn("persist-credentials: false", build)
        self.assertRegex(build, r"permissions:\n\s+contents: read\n")

    def test_credential_jobs_only_check_out_main(self) -> None:
        for name in ("authorize", "publish"):
            with self.subTest(job=name):
                section = job(self.text, name)
                self.assertIn("environment: apktrack-community-publishing", section)
                self.assertIn("ref: main", section)
                self.assertNotIn("head_sha }}\n          fetch-depth", section)
                self.assertNotIn("./gradlew", section)

    def test_publication_requires_a_delivered_receipt(self) -> None:
        publish = job(self.text, "publish")
        self.assertIn('job.get("receipt_delivered") is not True', publish)
        self.assertIn("verify-apk", publish)


if __name__ == "__main__":
    unittest.main()
