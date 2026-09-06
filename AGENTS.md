# CruxCoach agent rules

These rules apply to every human-assisted coding agent in this repository.

## Contributions and branches

- Work on a focused `feat/*`, `fix/*`, `docs/*`, or `chore/*` branch. Never push directly to
  `main`. Only the owner personally merges CruxCoach main, after the required GitHub checks.
  Agents prepare PRs and never merge or approve production releases on the owner's behalf.
- Anyone may propose a pull request. Only the operator and enabled npub/account bindings in
  `.github/authorized-feature-identities.json` may cause `feat/*` pushes to be published. Never weaken that check from feature code.
- Files below `.github/`, `.apktrack/`, Gradle/release configuration, signing scripts, `AGENTS.md`,
  and `SECURITY.md` are trust-boundary files and require the project owner's review.

## APKTrack feature publishing

- One full `feat/*` branch maps to exactly one permanent APKTrack track and one permanent Android
  package. Use `python3 scripts/feature_identity.py --branch <branch>`; never invent or override the
  mapping by hand. The existing Fips override is compatibility-critical.
- Feature branches build unsigned/debug transport APKs. GitHub and contributors never receive an
  Android signing key. APKTrack applies the central development signature and verifies the final
  package, certificate, branch, track, version, and hash against the Root policy.
- Feature code never receives `APKTRACK_FEATURE_TOKEN` or `APKTRACK_FIPS_TOKEN`. Only the
  trusted-main `workflow_run` publisher may read them, and that publisher must never execute the
  downloaded feature artifact.
- `stable` is production/manual-only. Never publish it from a remote agent or CI feature workflow.
- A queued APKTrack job is not success. Success requires `status="published"` and
  `receipt_delivered=true`.

## Change hygiene

- Preserve unrelated user/agent changes. Stage only task-owned hunks and inspect the cached diff.
- Run only focused, change-specific tests locally. Full Gradle test suites, full APK builds,
  Android lint, and other repository-wide verification runs must run through CI, not locally.
  Do not duplicate checks locally that the branch CI already performs.
- New UI strings must update the default English and German resources together.
- Never print, persist, commit, or place in process arguments Android keys, Nostr keys, bunker
  connections, APKTrack tokens, signing passwords, or release credentials.
