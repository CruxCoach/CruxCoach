# Releasing on GitHub

GitHub is the primary forge. Dispatching `.github/workflows/release.yml` from
`main` runs it on a **self-hosted** runner: unit tests, one signed APK, the tag,
the GitHub release, a byte-identical Codeberg release, both SHA-256 sidecars,
the Zapstore publish, and the website and download-server update.

It is a manual trigger, and it refuses to run off `main`. The Zapstore signer
prompts a phone twice per release (see below), so an unattended run would stall
waiting for a human — and the second prompt falls after the GitHub release is
already public. There are no dev prereleases: the pipeline builds full releases
only.

The old Codeberg/Forgejo workflow has been retired. Forgejo cannot enforce the
same server-side protected-environment policy as GitHub here, so a workflow
dispatched from an attacker-controlled ref could rewrite its own in-file
guards before reaching the signing runner. The manual script remains the
forge-independent break-glass path. Codeberg is now a publication target of
the protected GitHub workflow, not a second place that builds or signs.

## Why GitHub Actions is acceptable, given the signing key

The standing objection to Actions was that it would put the release signing key
into GitHub's secret store. That key is the one thing here whose loss or
compromise cannot be undone: a rotation is only installable from 0.2.3 onwards,
and only on Android 9+. Everything else in this system has a second path; the
key does not.

A **self-hosted runner** answers the objection rather than accepting it. The
job runs on our own machine, reads the keystore from `$CRUXCOACH_SECRETS_DIR`
on the local filesystem, and uploads nothing. The workflow declares
`permissions: contents: read` and defines no secrets — nothing in the GitHub
secret store is load-bearing, so a GitHub-side compromise cannot reach the key.

The trade is different, not free: a self-hosted runner executes repository
code on a host that holds the signing key. The release job therefore has three
independent main-only fences: a job-level ref condition evaluated before runner
assignment, a first-step ref assertion before checkout, and a checkout pinned
to `refs/heads/main`. More importantly, it targets the GitHub `release`
environment, whose server-side custom deployment branch policy allows only the
`main` branch. That environment and policy are operational configuration, not
workflow-file decoration; deleting or broadening them reopens the signing-host
boundary. Verify them with:

```bash
gh api repos/CruxCoach/CruxCoach/environments/release
gh api repos/CruxCoach/CruxCoach/environments/release/deployment-branch-policies
```

Keep the runner off pull-request triggers, keep write access to `main` as
narrow as it is today, and keep the dispatch permission with it — starting a
run is starting a signature. Do not restore a forge fallback unless it can
enforce an equivalent server-side protected-runner or deployment policy.

## One implementation for CI and break-glass use

`scripts/publish-github-release.sh` owns primary publication and
`scripts/mirror-codeberg-release.sh` owns secondary-forge mirroring. The
workflow calls both; it does not duplicate either API implementation. Both
scripts enforce sidecar-before-APK ordering and post-verify what the forge
stored. The Codeberg script additionally downloads both assets and compares
them byte-for-byte with the already published GitHub build.

The script is also the **break-glass path**. Run by hand it takes an APK that
is already built and signed on a machine we control and needs nothing but a
token and a network path to GitHub — no forge has to be willing to run a
pipeline for us:

```bash
./gradlew :androidApp:assembleRelease
scripts/publish-github-release.sh v0.2.2 --dry-run     # refuses loudly, changes nothing
scripts/publish-github-release.sh v0.2.2
scripts/mirror-codeberg-release.sh v0.2.2 --dry-run
scripts/mirror-codeberg-release.sh v0.2.2
```

## What the script refuses to do

Before anything leaves the machine:

- the APK is not signed by `EXPECTED_SIGNERS` — a wrong keystore would produce
  a release that every existing install silently rejects through the TOFU pin,
  and the rejection would only surface much later
- v3 signing is off — a future key rotation would be uninstallable
- `versionName` does not match the tag's base version — the updater compares
  the version it reads, not the file name, so 0.2.1 bytes under a `v0.2.2` tag
  would install and then never update again

"Base version" means everything before the first `-`. The pipeline no longer
produces suffixed tags, but the comparison keeps the rule so a hand-made tag
like `v0.2.2-hotfix.1` still validates against a `versionName` of `0.2.2`.

It uploads the `.sha256` sidecar **before** the APK. The updater ignores a
release unless both assets exist, so a run that dies between the two must not
leave "APK present, sidecar missing": that looks complete to a human, is
invisible to every in-app updater, and the tag already exists to block a
rebuild. Afterwards it reads the release back and compares the stored asset
size against the bytes sent — names alone would not catch a truncated upload.

Re-running is safe: an existing release is reused and same-named assets are
replaced.

## Authentication: one token, no SSH

GitHub uses `~/.config/cruxcoach/github-release-token`, mode 600, with
`Contents: Read and write` on `CruxCoach/CruxCoach`. Override with
`GITHUB_TOKEN` or `GITHUB_TOKEN_FILE`. The runner runs as the same user, so it
finds the file itself and nothing has to be handed to it.

Codeberg independently uses
`~/.config/cruxcoach/codeberg-release-token`, mode 600, with repository write
access on `CruxCoach/CruxCoach`. Override with `CODEBERG_TOKEN` or
`CODEBERG_TOKEN_FILE`. Neither forge credential is stored in GitHub, embedded
in a remote URL, printed, or persisted by either publisher.

The same token authenticates the API calls **and** the tag push. The push
therefore goes over HTTPS, not SSH, and that is deliberate: `~/.ssh/config`
pins `github.com` to `id_ed25519_github_pages` with `IdentitiesOnly yes`, and
that key is a deploy key for `CruxCoach.github.io` alone. An SSH push to
`CruxCoach/CruxCoach` fails with "Repository not found", which reads like a
missing repository rather than a missing permission — the wrong thing to be
debugging in the middle of a release.

The token never appears in the process list (curl reads the `Authorization`
header from a file descriptor; git gets it through the environment of a
one-shot credential helper) and never lands in `.git/config` — the `github`
remote is stored credential-free, because a URL with an embedded token outlives
the run and travels into every backup.

## Zapstore signing: which methods can work in CI

`zsp` signs the release event with whatever `SIGN_WITH` names. Four forms
exist, and only some of them can work on a headless runner:

| `SIGN_WITH` | Headless? | Notes |
|---|---|---|
| `nsec1…` | yes | Publisher key in the clear, wherever the value is stored |
| `bunker://…` | yes, but see below | NIP-46 remote signer. CI holds a revocable connection token, not the key |
| `npub1…` | **no** | zsp 0.4.8 emits unsigned events; it does not discover a signer |
| `browser` | **no** | NIP-07, wants a human at a browser window |

**This pipeline signs with Amber, and a release therefore needs the
maintainer's phone.** `SIGN_WITH` is a `bunker://` URL served by Amber, the
Android NIP-46 signer holding the publisher key; the zsp client key under
`~/.config/zsp/bunker-keys/` has been provisioned since 2026-04-14. Verified
end-to-end on 2026-08-11: all three release events (32267, 30063, 3063) came
back signed by the publisher key with valid signatures.

The connection does **not** auto-approve — each request raises a prompt that
has to be tapped. A release therefore raises **two** prompts: the signer proof
before the build, and the publish itself. Both steps allow 300s; 120s expired
while the phone was still being unlocked, and the retry then raised a second
prompt for the same release. Keep the two timeouts in step.

The practical consequence: **a release cannot run unattended.** Do not merge to
`main` and walk away, and do not schedule one for 02:00 — the job will build,
then fail at the signer gate. If unattended releases are wanted later, switch
the Amber connection to automatic approval and the constraint disappears; the
timeouts can stay as they are.

Amber *also* appears in this project as the user's identity signer inside the
app (backups, profile, DMs). That is a separate role and unrelated to
publishing — do not conflate the two.

What the workflows do: `scripts/zapstore-signer.py` reads only `SIGN_WITH` from
`$CRUXCOACH_SECRETS_DIR/.env` on the runner's filesystem. The file uses zsp's
raw `KEY=value` syntax: no shell quotes, and it is never sourced. That detail is
load-bearing for a `bunker://` URL, whose unquoted `&secret=…` is valid zsp data
but a shell control operator. The helper accepts only signing-capable headless
modes, derives their public key, and requires it to equal `zapstore.yaml`'s
publisher npub. For a bunker it also requires an existing mode-600 zsp client
key under `~/.config/zsp/bunker-keys/`, so a release cannot become its first
interactive authorisation attempt.

The same preflight runs before either workflow creates a public release. The
static check happens before the APK build; after signature verification, a
second gate runs `zsp publish --offline` against the finished APK. That requires
the bunker to be online and the request to be approved, while uploading no
APK and publishing no Nostr event. Only then may the workflow create the forge
release. The validated value is exported only inside these signing steps and
masked before `zsp` runs. It is deliberately **not** a GitHub secret — same
reasoning as the APK keystore — and deliberately **not** copied to `./.env`: a
job killed with SIGKILL runs no `trap`, and the workspace is reused between
jobs. Each signing step removes a possible legacy copy first.

Provision a bunker once, interactively, as the runner user; that establishes
the NIP-46 client and creates the cached client-key file. Put the raw bunker
URL in `$CRUXCOACH_SECRETS_DIR/.env`, mode 600. Subsequent release runs reuse
that client and retain the existing publisher identity — they still need each
request approved in Amber unless the connection is set to approve
automatically.

Note that the `secret=` in a freshly issued bunker URL is single-use: it
authorises the first connection, after which the cached client key carries the
session. A second client presenting the same consumed secret is answered with
`unauthorized`, which reads like a broken URL but is not.

## Release notes are checked before the keystore is touched

`RELEASE_NOTES.md` is the GitHub release body *and* the Zapstore
`release_notes`, and `changelog-extract.sh` matches on the `## [<version>]`
prefix — so a section still headed *Unreleased* extracts cleanly and publishes
the word to both, plus the in-app "what's new". The tag then exists and blocks
a rebuild.

The workflow therefore refuses, before copying the signing config: the
changelog section for the version must exist and be non-empty, and neither its
heading nor the first line of `RELEASE_NOTES.md` may still say *Unreleased* or
*TBD*. It also requires the notes heading to mention the version, which catches
the opposite mistake — last release's notes under a new tag. Nothing is exempt;
every run of this pipeline is a full release.

## Migration order

The steps below are ordered, and the order is the load-bearing part.

1. **Register the self-hosted runner** on `github.com/CruxCoach/CruxCoach`,
   as the same user that holds `$CRUXCOACH_SECRETS_DIR` and the release token.
2. **Turn the Codeberg push mirror off.** A Forgejo push mirror syncs with
   `--mirror` semantics: it makes the target match the source and **deletes
   refs on the target that the source does not have**. While it is running,
   the first tag pushed directly to GitHub is a ref Codeberg does not know
   about, and the next mirror run removes it — together with the release that
   hangs off it. The mirror must be off *before* anything is pushed straight
   to GitHub, not merely before the switch is announced.
3. **Provision both runner-local forge token files**, mode 600.
4. **Publish one release** and confirm GitHub and Codeberg each have the same
   APK and sidecar.
5. **Only then**, flip the updater source list — next section.

## Then, and only then: enable the source

`update-sources.json` in `cruxcoach-pages` carries a `github` entry with
`enabled: false`. Flip it once the release exists:

```bash
curl -s -H 'User-Agent: cruxcoach-check' \
  https://api.github.com/repos/CruxCoach/CruxCoach/releases \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin)), 'releases')"
```

Enabling it earlier is worse than leaving it out. An empty releases endpoint
answers every check with "no releases", and the sweep counts that as a healthy
source that happens to have no update — nothing is logged as wrong while the
source insures nothing.

Existing 0.2.2 installs pick the change up within the 24 h manifest TTL. Doing
this **while Codeberg still works** is the point: adding the replacement after
the old host is gone is a race against every install that has not refreshed.

The website's nightly `tools/update-download-link.mjs` has the same ordering
condition for a different reason: it reads `releases/latest`, which answers
**404** on a repository with no releases. That is a loud failure rather than a
quiet one — the script exits non-zero and the previous links stay — but it will
run red every night until the first GitHub release exists.

## The release mirror: GitHub → Codeberg

A forge repository mirror copies Git refs, not release records or assets. The
protected release runner therefore mirrors the release explicitly after the
GitHub publication. It does not rebuild: the same signed APK in the workspace
is uploaded to Codeberg, with the same coreutils-format sidecar. A retry after
GitHub succeeded downloads that existing GitHub APK and verifies its sidecar,
then repairs Codeberg with those exact bytes. This preserves one hash per
version across both forges.

The mirror is idempotent. It reuses an existing release, replaces only the two
expected assets, uploads the sidecar before the APK, verifies names and sizes
through the API, then downloads both and requires byte-for-byte equality. A
missing Codeberg credential or transient Codeberg failure turns the release
run red after GitHub is public; dispatching the same main version repairs the
mirror without producing a second build.

Keeping the old direction (Codeberg → GitHub) running is not an option once
GitHub is primary: a Forgejo push mirror pushes with `--mirror` semantics, so
it makes the target match the source and deletes refs the source does not have.
The first tag pushed straight to GitHub is exactly such a ref, and the next
mirror run would remove it along with the release hanging off it.

## For 0.2.3

Move the compiled-in default (`UPDATER_API_BASE` in `androidApp/build.gradle.kts`)
to `https://api.github.com`, so fresh installs do not depend on the runtime list
at all. Keep Codeberg in `update-sources.json` for as long as it answers — the
sweep asks every source and takes the highest version, so a second forge costs
one request and buys a fallback.

## Known differences between the two forges

Both were checked against the shipped client on 2026-08-07 and are covered by
`ForgeSourceGitHubCompatTest`:

| | Forgejo (Codeberg) | GitHub |
|---|---|---|
| API root | `https://codeberg.org/api/v1` | `https://api.github.com` |
| Web host for assets | same host | `github.com`, not `api.github.com` |
| Page size parameter | `limit` | `per_page` |
| Asset upload | multipart `attachment=` | raw body to `uploads.github.com` |
| Missing User-Agent | tolerated | **403** |
| Deleting a release | removes the tag too | leaves the tag; delete `git/refs/tags/:tag` separately |
| `releases/latest`, none published | `404` | `404` (and `releases` returns `[]`) |

The client sends both page-size parameters and always sets a User-Agent.

Unauthenticated GitHub API calls are limited to 60 per hour per IP. A device
checking every two hours is far below that; a large gym behind one carrier NAT
could in principle approach it, at which point the sweep simply falls through to
the other sources — it is a degradation, not an outage.

## One pipeline, two release endpoints

There is no second Forgejo release workflow. The only production build is the
manual, protected GitHub workflow on `main`; Codeberg receives its output as a
mirror target in that same run. This structurally prevents two non-reproducible
builds from being published under one version number.
