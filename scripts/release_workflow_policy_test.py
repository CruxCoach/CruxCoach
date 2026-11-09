"""Regression checks for the self-hosted production release trust boundary."""

from pathlib import Path
import json
import re
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[1]
# Every release up to 0.2.4 is signed by this certificate; the new key's
# lineage starts from it.
PREVIOUS_RELEASE_CERT = "7f8308e8fe25e41aad0c7f0ac087775acd7d8c6a23da3a824212a8f3fe1bc2c4"


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
        tests_job, job = workflow.split("\n  tests:\n", 1)[1].split("\n  release:\n", 1)

        self.assertIn("if: github.ref == 'refs/heads/main'", job)
        self.assertIn("environment: release", job)
        self.assertIn("ref: refs/heads/main", job)
        self.assertIn("persist-credentials: false", job)

        publisher = (ROOT / "scripts/publish-github-release.sh").read_text()
        self.assertIn("-c http.https://github.com/.extraheader=", publisher)

        guard = job.index("- name: Require protected main ref")
        checkout = job.index("- name: Checkout")
        tested = job.index("- name: Require the tested commit")
        build = job.index("- name: Build release APK")
        self.assertLess(guard, checkout)
        self.assertLess(checkout, tested)
        self.assertLess(tested, build)
        self.assertIn('[ "$HEAD_SHA" = "$GITHUB_SHA" ]', job)

    def test_unit_tests_run_on_a_hosted_runner_before_the_release_job(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        tests_job, job = workflow.split("\n  tests:\n", 1)[1].split("\n  release:\n", 1)
        self.assertIn("if: github.ref == 'refs/heads/main'", tests_job)
        self.assertIn("runs-on: ubuntu-latest", tests_job)
        self.assertIn("ref: ${{ github.sha }}", tests_job)
        self.assertIn("python3 -m unittest discover -s scripts -p '*_test.py'", tests_job)
        self.assertIn(":shared:testDebugUnitTest :androidApp:testDebugUnitTest", tests_job)
        self.assertNotIn("environment:", tests_job)
        self.assertNotIn("secrets.", tests_job)

        self.assertTrue(job.startswith("    needs: tests\n"))
        self.assertNotIn("testDebugUnitTest", job, "no test load on the release host")
        build = job[job.index("- name: Build release APK"):job.index("- name: Wait for the YubiKey signature")]
        self.assertIn("-Pkotlin.compiler.execution.strategy=in-process", build)
        self.assertIn('"-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"', build)

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
        # Runs after any failed publish step — Zapstore included — as long as
        # the release itself went out, so a red run lists every channel that
        # needs the repair path, not just the first.
        self.assertIn(
            "if: ${{ !cancelled() && (steps.github_release.outcome == 'success' "
            "|| steps.repair.outcome == 'success') }}",
            verify,
        )
        self.assertNotIn("steps.zapstore.outcome", verify)
        self.assertIn("--wait 900", verify)
        self.assertNotIn("- name:", verify.split("\n", 1)[1], "the check is the last step")

    def test_a_failed_channel_never_skips_the_other_channels(self):
        # 2026-10-25: zsp exited 0 without publishing, the Zapstore step failed,
        # and the implicit success() skipped the website, the download
        # selector, APKTrack stable and the Blossom fallbacks with it.
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("id: github_release", workflow)
        self.assertIn("id: repair", workflow)
        released = (
            "if: ${{ !cancelled() && steps.version.outputs.branch == 'main' && "
            "(steps.github_release.outcome == 'success' || steps.repair.outcome == 'success') }}"
        )
        for name in (
            "Mirror release to Codeberg",
            "Ensure verified Zapstore APK fallback",
            "Publish the release to the download server and the website",
            "Publish the release to APKTrack stable",
        ):
            step = workflow[workflow.index(f"- name: {name}"):]
            header = step.split("run: |", 1)[0]
            self.assertIn(released, header, name)

    def test_zapstore_step_logs_where_zsp_publishes(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        step = workflow[workflow.index("- name: Ensure verified Zapstore APK fallback"):]
        step = step[: step.index("- name: Publish the release to the download server")]
        self.assertIn("zsp targets:", step)
        self.assertIn("zsp publish --skip-preview --quiet", step)
        # The bunker URL is masked before zsp runs, never echoed.
        self.assertLess(step.index('echo "::add-mask::$SIGN_WITH"'), step.index("zsp targets:"))

    def test_codeberg_mirror_preserves_release_integrity_contract(self):
        script = (ROOT / "scripts/mirror-codeberg-release.sh").read_text()
        sidecar = script.index('replace_asset "$WORK/$SHA_NAME"')
        apk = script.index('replace_asset "$WORK/$APK_NAME"')

        self.assertLess(sidecar, apk)
        # Read back through the API: both assets present at their exact size.
        self.assertIn('raise SystemExit("Codeberg APK size mismatch")', script)
        self.assertIn('raise SystemExit("Codeberg sidecar size mismatch")', script)
        self.assertNotIn("x-access-token:${", script)

    def test_release_runs_never_inflate_the_forge_download_counters(self):
        # Forge download counters ARE the download statistic. Reading an asset
        # back added a fake install per run, and deleting + re-uploading an
        # asset that was already there reset the release's real count to zero.
        mirror = (ROOT / "scripts/mirror-codeberg-release.sh").read_text()
        self.assertNotIn("releases/download", mirror)
        self.assertNotIn("stored.apk", mirror)
        self.assertIn("already holds $NAME at the expected size; keeping it", mirror)

        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        step = workflow[workflow.index("- name: Prepare existing release for Zapstore repair"):]
        step = step[: step.index("- name: Mirror release to Codeberg")]
        self.assertIn("https://api.github.com/repos/${REPO_SLUG}/releases/tags/${TAG}", step)
        local = step.index('fetch_verified "the local copy"')
        blossom = step.index("https://blossom.primal.net")
        github = step.index('fetch_verified "GitHub"')
        self.assertLess(local, blossom)
        self.assertLess(blossom, github)
        # The only APK download from GitHub is the last resort, and it says so.
        self.assertEqual(step.count('"${BASE}/${APK_NAME}"'), 1)
        self.assertIn("counts as one download", step)

    def test_the_release_build_is_unsigned_and_no_key_reaches_the_runner(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertNotIn(".signing", workflow)
        self.assertNotIn("Apply signing lineage", workflow)
        self.assertNotIn("lineage.bin", workflow)
        self.assertIsNone(re.search(r"apksigner\"? sign\b", workflow), "nothing is signed on the runner")
        copy = workflow[workflow.index("- name: Copy build config"):workflow.index("- name: Build release APK")]
        self.assertIn("grep -v '^[[:space:]]*RELEASE_'", copy)
        build = workflow[workflow.index("- name: Build release APK"):workflow.index("- name: Wait for the YubiKey signature")]
        self.assertIn("assembleRelease -PunsignedRelease=true", build)
        gradle = (ROOT / "androidApp/build.gradle.kts").read_text()
        self.assertIn('providers.gradleProperty("unsignedRelease")', gradle)
        self.assertIn("unsignedRelease=true but local.properties still sets RELEASE_STORE_FILE", gradle)

    def test_the_signed_apk_is_verified_before_anything_is_published(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        order = [
            "- name: Build release APK",
            "- name: Wait for the YubiKey signature",
            "- name: Verify the YubiKey signature",
            "- name: Verify APK signature",
            "- name: Prove Zapstore signer is available",
            "- name: Create GitHub release",
        ]
        positions = [workflow.index(name) for name in order]
        self.assertEqual(positions, sorted(positions))

        wait = workflow[positions[1]:positions[2]]
        self.assertIn("scripts/wait-for-release-signature.sh", wait)
        self.assertIn("androidApp-release-unsigned.apk", wait)
        self.assertIn("--timeout 1800", wait)

        check = workflow[positions[2]:positions[3]]
        verified = check.index("scripts/verify-release-signature.py")
        self.assertIn('--signer "$RELEASE_CERT_SHA256"', check)
        self.assertIn('--lineage "$PREVIOUS_RELEASE_CERT_SHA256" "$RELEASE_CERT_SHA256"', check)
        # The release path gets the APK only after the check passed.
        installed = check.index('"$RELEASE_DIR/androidApp-release.apk"')
        self.assertLess(verified, installed)

    def test_every_release_certificate_pin_agrees(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        release = re.search(r'^      RELEASE_CERT_SHA256: "([0-9a-f]{64})"$', workflow, re.M).group(1)
        previous = re.search(r'^      PREVIOUS_RELEASE_CERT_SHA256: "([0-9a-f]{64})"$', workflow, re.M).group(1)
        self.assertEqual(previous, PREVIOUS_RELEASE_CERT)
        self.assertNotEqual(release, previous)
        self.assertIn('EXPECTED_SIGNERS="$RELEASE_CERT_SHA256"', workflow)

        publisher = (ROOT / "scripts/publish-github-release.sh").read_text()
        self.assertEqual(re.findall(r'^EXPECTED_SIGNERS="([0-9a-f ]+)"$', publisher, re.M), [release])

        self.assertEqual(re.findall(r'^LINEAGE_FROM="([0-9a-f]{64})"$', publisher, re.M), [previous])

        project = tomllib.loads((ROOT / ".apktrack/project.toml").read_text())
        production = [s for s in project["android"]["scope"] if s["role"] == "production"]
        self.assertEqual([s["certificate_sha256"] for s in production], [release])

        # The signed version 1 the live Root policy started from. A new
        # certificate goes into a new full policy version on the host; an
        # edited version 1 could not be imported.
        policy = json.loads(json.loads((ROOT / ".apktrack/policy.unsigned.json").read_text())["content"])
        self.assertEqual(policy["version"], 1)
        stable = [c for c in policy["certificates"] if "production" in c["roles"]]
        self.assertEqual([c["certificate_sha256"] for c in stable], [previous])

    def test_the_by_hand_publisher_checks_the_lineage_too(self):
        publisher = (ROOT / "scripts/publish-github-release.sh").read_text()
        self.assertIn('scripts/verify-release-signature.py"', publisher)
        self.assertIn('--lineage "$LINEAGE_FROM" "$EXPECTED_SIGNERS"', publisher)
        self.assertLess(
            publisher.index("scripts/verify-release-signature.py"),
            publisher.index("# ---- 2."),
        )

    def test_zsp_calls_use_only_what_zsp_2_knows(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        # Both flags were removed in zsp 2.x, which refuses unknown flags.
        self.assertNotIn("--offline", workflow)
        self.assertNotIn("--skip-certificate-linking", workflow)
        self.assertNotIn("RELAY_URLS:-", workflow)
        proof = workflow[workflow.index("- name: Prove Zapstore signer is available"):]
        proof = proof[: proof.index("- name: Create GitHub release")]
        self.assertIn("zsp check zapstore.yaml --json", proof)
        self.assertIn('scripts/zapstore-check.py --certificate "$RELEASE_CERT_SHA256"', proof)
        publish = workflow[workflow.index("- name: Ensure verified Zapstore APK fallback"):]
        self.assertIn("release_already_published", publish)


if __name__ == "__main__":
    unittest.main()
