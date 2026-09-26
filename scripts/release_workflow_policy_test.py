"""Regression checks for the self-hosted production release trust boundary."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ReleaseWorkflowPolicyTest(unittest.TestCase):
    def test_moon_checker_is_daily_least_privilege_and_never_uploads_apk(self):
        workflow = (ROOT / ".github/workflows/moon-compatibility.yml").read_text()
        self.assertIn("cron: '17 4 * * *'", workflow)
        self.assertIn("contents: read", workflow)
        self.assertIn("issues: write", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("APKKEEP_SHA256:", workflow)
        self.assertIn("--list-versions", workflow)
        self.assertIn("if: failure()", workflow)
        upload = workflow.split("- name: Upload compatibility evidence", 1)[1]
        upload = upload.split("- name: Open one review issue", 1)[0]
        self.assertNotIn(".apk", upload)
        self.assertNotIn(".xapk", upload)

    def test_github_release_is_main_only_and_environment_protected(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        job = workflow.split("\n  release:\n", 1)[1]

        self.assertIn("if: github.ref == 'refs/heads/main'", job)
        self.assertIn("environment: release", job)
        self.assertIn("ref: refs/heads/main", job)
        self.assertIn("persist-credentials: false", job)

        publisher = (ROOT / "scripts/publish-github-release.sh").read_text()
        self.assertIn("-c http.https://github.com/.extraheader=", publisher)

        guard = job.index("- name: Require protected main ref")
        checkout = job.index("- name: Checkout")
        tests = job.index("- name: Run unit tests")
        self.assertLess(guard, checkout)
        self.assertLess(checkout, tests)

    def test_unprotected_forgejo_release_fallback_is_retired(self):
        self.assertFalse((ROOT / ".forgejo/workflows/release.yml").exists())

    def test_codeberg_mirror_reuses_new_or_recovered_github_bytes(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        create = workflow.index("- name: Create GitHub release")
        recover = workflow.index("- name: Prepare existing release for Zapstore repair")
        mirror = workflow.index("- name: Mirror release to Codeberg")
        zapstore = workflow.index("- name: Ensure verified Zapstore APK fallback")

        self.assertLess(create, mirror)
        self.assertLess(recover, mirror)
        self.assertLess(mirror, zapstore)
        self.assertIn('scripts/mirror-codeberg-release.sh "$TAG"', workflow)

    def test_stable_is_published_only_by_the_release_run_after_every_public_channel(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        zapstore = workflow.index("- name: Ensure verified Zapstore APK fallback")
        website = workflow.index("- name: Publish the release to the download server and the website")
        stable = workflow.index("- name: Publish the release to APKTrack stable")
        verify = workflow.index("- name: Verify every channel carries the release")
        self.assertLess(zapstore, website)
        self.assertLess(website, stable)
        self.assertLess(stable, verify)

        step = workflow[stable:verify]
        # The tag's commit, not whatever main has moved on to by a repair run.
        self.assertIn('COMMIT="$(git rev-parse "${TAG}^{commit}")"', step)
        self.assertIn('"$PUBLISH" "$APK" --commit "$COMMIT"', step)

        # No other workflow may reach the manual-only production track.
        for other in (ROOT / ".github/workflows").glob("*.yml"):
            if other.name == "release.yml":
                continue
            text = other.read_text()
            self.assertNotIn("apktrack-publish-stable", text, other.name)
            self.assertNotIn("--confirm-production", text, other.name)
            self.assertNotIn("publish-manual", text, other.name)

    def test_release_run_ends_by_checking_every_channel(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        verify = workflow[workflow.index("- name: Verify every channel carries the release"):]
        # Still runs after a failed later publish step, so a red run lists
        # every channel that needs the repair path, not just the first.
        self.assertIn("if: ${{ !cancelled() && steps.zapstore.outcome == 'success' }}", verify)
        self.assertIn("id: zapstore", workflow)
        self.assertIn("--wait 900", verify)
        self.assertNotIn("- name:", verify.split("\n", 1)[1], "the check is the last step")

    def test_codeberg_mirror_preserves_release_integrity_contract(self):
        script = (ROOT / "scripts/mirror-codeberg-release.sh").read_text()
        sidecar = script.index('replace_asset "$WORK/$SHA_NAME"')
        apk = script.index('replace_asset "$WORK/$APK_NAME"')

        self.assertLess(sidecar, apk)
        self.assertIn('cmp -s "$WORK/$SHA_NAME" "$WORK/stored.sha256"', script)
        self.assertIn('cmp -s "$WORK/$APK_NAME" "$WORK/stored.apk"', script)
        self.assertNotIn("x-access-token:${", script)


if __name__ == "__main__":
    unittest.main()
