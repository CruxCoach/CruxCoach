#!/usr/bin/env bash
# Publish a signed release APK to GitHub — with no Codeberg involvement at all.
#
# THE ONE PLACE THE RELEASE LOGIC LIVES
# -------------------------------------
# Two callers, one implementation:
#
#   * .github/workflows/release.yml — the primary pipeline since the move off
#     Codeberg. It runs on a SELF-HOSTED runner, which is what makes Actions
#     acceptable at all: the signing key stays on our own filesystem under
#     $CRUXCOACH_SECRETS_DIR and never enters GitHub's secret store. That was
#     the documented objection to Actions, and a self-hosted runner answers it.
#   * a maintainer, by hand — the break-glass path for when no forge will run
#     a pipeline for us. It takes an APK that is already built and signed on a
#     machine we control and needs nothing but a token and a network path.
#
# The workflow CALLS this script rather than reimplementing it. The upload
# ORDER in particular (sidecar first, APK last — see section 5) is far too
# subtle to keep correct in two places; .forgejo/workflows/release.yml is
# where that duplicate used to live, and it is now a fallback only.
#
# USAGE
#   scripts/publish-github-release.sh v0.2.2 [options]
#
#     --apk PATH        APK to publish (default: the release build output)
#     --notes-file PATH release notes, markdown (default: derived from CHANGELOG)
#     --prerelease      mark the GitHub release as a pre-release
#     --repo OWNER/NAME override the target repository
#     --dry-run         do everything except create/upload/push
#
# AUTHENTICATION
#   GITHUB_TOKEN, or a token in the file named by GITHUB_TOKEN_FILE (default
#   ~/.config/cruxcoach/github-release-token). Needs `contents: write` on the
#   target repository — the same token authenticates the API calls AND the tag
#   push, so there is exactly one credential to provision and rotate.
#
#   The token is never echoed, never passed on a command line, and never
#   written to a file. Deliberately NOT SSH for the tag push: ~/.ssh/config
#   pins github.com to the Pages deploy key with `IdentitiesOnly yes`, and
#   that key is scoped to CruxCoach.github.io alone. An SSH push here fails
#   with "Repository not found", which reads like a missing repository rather
#   than a missing permission — the wrong thing to be debugging mid-release.
#
# EXIT CODES
#   0 published and verified   1 refused or failed   2 could not run at all
set -euo pipefail

REPO="CruxCoach/CruxCoach"
API="https://api.github.com"
UPLOADS="https://uploads.github.com"
APK=""
NOTES_FILE=""
PRERELEASE="false"
DRY_RUN="false"
TAG=""

# The signer this project ships under. Same pin as the Forgejo workflow: a
# wrong keystore must not be able to publish, and a release signed by a key
# nobody recognises is worse than no release — the in-app TOFU pin would refuse
# it on every existing install, silently, long after the fact.
EXPECTED_SIGNERS="7f8308e8fe25e41aad0c7f0ac087775acd7d8c6a23da3a824212a8f3fe1bc2c4"

die() { echo "ERROR: $*" >&2; exit 1; }
cannot_run() { echo "ERROR: $*" >&2; exit 2; }
note() { echo "-- $*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:?}"; shift 2;;
    --notes-file) NOTES_FILE="${2:?}"; shift 2;;
    --prerelease) PRERELEASE="true"; shift;;
    --repo) REPO="${2:?}"; shift 2;;
    --dry-run) DRY_RUN="true"; shift;;
    # Print the header block, however long it happens to be. A hardcoded line
    # range silently starts cutting the help off the moment the header grows.
    -h|--help) awk 'NR > 1 { if (!/^#/) exit; print }' "$0"; exit 0;;
    v*) TAG="$1"; shift;;
    *) die "unknown argument: $1";;
  esac
done

[ -n "$TAG" ] || die "a tag is required, e.g. v0.2.2"
case "$TAG" in
  v[0-9]*.[0-9]*.[0-9]*) ;;
  *) die "tag must look like v1.2.3, got '$TAG'";;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
[ -n "$APK" ] || APK="androidApp/build/outputs/apk/release/androidApp-release.apk"
[ -f "$APK" ] || cannot_run "APK not found: $APK (build it first: ./gradlew :androidApp:assembleRelease)"

# ---- token -------------------------------------------------------------
# Precedence: explicit env, explicit file, then the conventional location. The
# default path means the usual invocation needs no environment at all — one
# less thing to remember at the exact moment when forgetting it produces a
# confusing 404 rather than a clear "no token".
TOKEN="${GITHUB_TOKEN:-}"
TOKEN_PATH="${GITHUB_TOKEN_FILE:-$HOME/.config/cruxcoach/github-release-token}"
if [ -z "$TOKEN" ] && [ -f "$TOKEN_PATH" ]; then
  # The file holds the bare token and nothing else; strip whatever whitespace
  # an editor left behind.
  TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_PATH")"
  PERMS="$(stat -c%a "$TOKEN_PATH" 2>/dev/null || echo '?')"
  case "$PERMS" in
    600|400) ;;
    *) echo "WARNING: $TOKEN_PATH is mode $PERMS — should be 600" >&2;;
  esac
fi
[ -n "$TOKEN" ] || cannot_run "no token found. Put it in $TOKEN_PATH (mode 600), or set GITHUB_TOKEN. Needs 'Contents: Read and write' on $REPO"

# curl reads the header from stdin so the token never appears in the process
# list, where any other user on the machine could read it.
gh_curl() {
  curl -sS --max-time 120 \
    -H @<(printf 'Authorization: Bearer %s\n' "$TOKEN") \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "User-Agent: cruxcoach-release/1.0" \
    "$@"
}

json_field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('$1',''))"; }

# ---- 1. the APK must be the real thing ---------------------------------
note "verifying the APK before anything leaves this machine"
APKSIGNER="$(find "${ANDROID_SDK_ROOT:-$HOME/android-sdk}/build-tools" \
  -mindepth 2 -maxdepth 2 -type f -name apksigner -print 2>/dev/null | sort -V | tail -1 || true)"
[ -x "$APKSIGNER" ] || cannot_run "apksigner not found — set ANDROID_SDK_ROOT"

OUT="$("$APKSIGNER" verify --print-certs -v "$APK")" || die "apksigner refused $APK"
echo "$OUT" | grep -q "^Verified using v3 scheme (APK Signature Scheme v3): true$" \
  || die "v3 signing is OFF — a future key rotation would be uninstallable"

MATCHED=0
for want in $EXPECTED_SIGNERS; do
  echo "$OUT" | grep -qi "certificate SHA-256 digest: $want" && MATCHED=1
done
[ "$MATCHED" = "1" ] || die "APK signer is not in EXPECTED_SIGNERS — refusing to publish"

# The APK must be the version the tag claims. Publishing 0.2.1 bytes under a
# v0.2.2 tag would install fine and then never update again, because the
# updater compares the version it reads from the release, not the file name.
#
# Read with aapt2, not by grepping the binary manifest: that returned nothing
# on a real APK and the check then skipped itself without a word — a guard that
# quietly does not run is worse than no guard, because it also removes the
# reason to look. So a missing aapt2 is a hard stop here, not a shrug.
AAPT2="$(find "${ANDROID_SDK_ROOT:-$HOME/android-sdk}/build-tools" \
  -mindepth 2 -maxdepth 2 -type f -name aapt2 -print 2>/dev/null | sort -V | tail -1 || true)"
[ -x "$AAPT2" ] || cannot_run "aapt2 not found — cannot confirm the APK matches $TAG"
APK_VERSION="$("$AAPT2" dump badging "$APK" 2>/dev/null \
  | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
[ -n "$APK_VERSION" ] || die "could not read versionName from $APK"

# Compare against the tag's BASE version, i.e. everything before the first
# '-'. Dev builds are tagged v0.2.2-dev.<sha> while versionName stays 0.2.2,
# because the suffix identifies the build, not the version the APK reports.
# Comparing the whole tag would reject every prerelease — the guard is meant
# to catch 0.2.1 bytes under a v0.2.2 tag, not to ban a suffix.
TAG_BASE="${TAG#v}"; TAG_BASE="${TAG_BASE%%-*}"
[ "$APK_VERSION" = "$TAG_BASE" ] \
  || die "APK is version $APK_VERSION but the tag says $TAG (base $TAG_BASE)"
note "versionName $APK_VERSION matches the tag"

APK_SIZE=$(stat -c%s "$APK")
note "signer ok, v3 on, ${APK_SIZE} bytes"

# ---- 2. sidecar --------------------------------------------------------
# coreutils format, the shape the in-app IntegrityVerifier parses.
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cp "$APK" "$WORK/CruxCoach-${TAG}.apk"
( cd "$WORK" && sha256sum "CruxCoach-${TAG}.apk" > "CruxCoach-${TAG}.apk.sha256" )
SHA_FILE="$WORK/CruxCoach-${TAG}.apk.sha256"
note "sidecar: $(cut -d' ' -f1 < "$SHA_FILE")"

# ---- 3. the commit has to exist on GitHub ------------------------------
# A release created for a tag GitHub does not have would be built from the
# default branch instead, quietly publishing bytes that match no commit there.
#
# The push goes over HTTPS with the token, never SSH — see AUTHENTICATION at
# the top for why SSH cannot work here. The remote is kept credential-free:
# a URL with an embedded token would sit in .git/config long after the run,
# and from there into every backup and `git remote -v` paste.
GITHUB_REMOTE_URL="https://github.com/${REPO}.git"
if ! CURRENT_REMOTE="$(git remote get-url github 2>/dev/null)"; then
  note "adding 'github' remote"
  [ "$DRY_RUN" = "true" ] || git remote add github "$GITHUB_REMOTE_URL"
elif [ "$CURRENT_REMOTE" != "$GITHUB_REMOTE_URL" ]; then
  # Earlier versions of this script left an SSH remote behind. Correct it, so
  # a hand-run `git push github` does not fail the same way this used to.
  note "'github' remote points at $CURRENT_REMOTE — pointing it at HTTPS"
  [ "$DRY_RUN" = "true" ] || git remote set-url github "$GITHUB_REMOTE_URL"
fi
COMMIT="$(git rev-parse HEAD)"
note "target commit $COMMIT"

if [ "$DRY_RUN" = "false" ]; then
  git tag -f "$TAG" "$COMMIT" >/dev/null
  # The token reaches git through the environment of a one-shot credential
  # helper: not on the command line, where the whole process list can read it,
  # and not in .git/config, where it would outlive the run. The first, empty
  # `-c credential.helper=` clears any system or global helper so ours is the
  # only one asked; GIT_TERMINAL_PROMPT=0 turns a credential failure into an
  # error instead of a prompt that hangs a CI job forever.
  #
  # Push the explicit URL rather than the remote name: whatever a previous run
  # or a human left in .git/config cannot then quietly redirect the push.
  #
  # Keep the pipe through `sed` — and keep `set -o pipefail` at the top of the
  # file. pipefail is the only reason the `||` below sees git's exit status
  # rather than sed's, which is always 0.
  # SC2016 is the intent, not a bug: the single quotes keep $CRUXCOACH_GIT_TOKEN
  # unexpanded here so that git's own shell resolves it inside the helper.
  # shellcheck disable=SC2016
  GIT_TERMINAL_PROMPT=0 \
  CRUXCOACH_GIT_TOKEN="$TOKEN" \
    git -c credential.helper= \
        -c credential.helper='!f() { printf "username=x-access-token\npassword=%s\n" "$CRUXCOACH_GIT_TOKEN"; }; f' \
        push -f "$GITHUB_REMOTE_URL" "refs/tags/$TAG" 2>&1 | sed 's/^/   /' \
    || cannot_run "could not push $TAG to GitHub — does the token have 'Contents: Read and write' on ${REPO}?"
fi

# ---- 4. release --------------------------------------------------------
EXISTING="$(gh_curl "$API/repos/$REPO/releases/tags/$TAG" || true)"
RELEASE_ID="$(printf '%s' "$EXISTING" | json_field id 2>/dev/null || true)"

if [ -n "$RELEASE_ID" ] && [ "$RELEASE_ID" != "None" ]; then
  note "release for $TAG already exists (id $RELEASE_ID) — reusing it"
else
  [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ] \
    && BODY="$(cat "$NOTES_FILE")" \
    || BODY="CruxCoach $TAG"
  if [ "$DRY_RUN" = "true" ]; then
    note "[dry-run] would create release $TAG on $REPO"
    RELEASE_ID="dry-run"
  else
    PAYLOAD="$(python3 -c "
import json,sys
print(json.dumps({'tag_name': sys.argv[1], 'target_commitish': sys.argv[2],
                  'name': 'CruxCoach ' + sys.argv[1], 'body': sys.argv[3],
                  'draft': False, 'prerelease': sys.argv[4] == 'true'}))
" "$TAG" "$COMMIT" "$BODY" "$PRERELEASE")"
    RESP="$(gh_curl -X POST -d "$PAYLOAD" "$API/repos/$REPO/releases")"
    RELEASE_ID="$(printf '%s' "$RESP" | json_field id)"
    [ -n "$RELEASE_ID" ] && [ "$RELEASE_ID" != "None" ] \
      || { printf '%s\n' "$RESP" >&2; die "could not create the release"; }
    note "created release id $RELEASE_ID"
  fi
fi

# ---- 5. assets ---------------------------------------------------------
# Sidecar FIRST, APK last — the same ordering the Forgejo workflow uses and for
# the same reason: the updater ignores a release unless BOTH assets exist, so a
# run that dies between the two uploads must not leave "APK present, sidecar
# missing". That state looks complete to a human and is invisible to every
# in-app updater, while the tag already exists and blocks a rebuild.
upload_asset() {
  local FILE="$1" NAME="$2" TYPE="$3" CODE
  # Remove a partial asset of the same name first; GitHub refuses duplicates.
  local OLD
  OLD="$(gh_curl "$API/repos/$REPO/releases/$RELEASE_ID/assets" \
    | python3 -c "
import json,sys
for a in json.load(sys.stdin):
    if a['name'] == sys.argv[1]: print(a['id'])
" "$NAME" 2>/dev/null || true)"
  if [ -n "$OLD" ]; then
    note "replacing existing asset $NAME"
    gh_curl -X DELETE "$API/repos/$REPO/releases/assets/$OLD" >/dev/null || true
  fi
  for ATTEMPT in 1 2 3; do
    CODE="$(gh_curl -o "$WORK/upload.json" -w '%{http_code}' -X POST \
      -H "Content-Type: $TYPE" --data-binary "@$FILE" \
      "$UPLOADS/repos/$REPO/releases/$RELEASE_ID/assets?name=$NAME")"
    [ "$CODE" = "201" ] && return 0
    echo "   upload of $NAME failed (attempt $ATTEMPT): HTTP $CODE" >&2
    sed 's/^/   /' "$WORK/upload.json" >&2 || true
    sleep 3
  done
  return 1
}

if [ "$DRY_RUN" = "true" ]; then
  note "[dry-run] would upload CruxCoach-${TAG}.apk.sha256 then CruxCoach-${TAG}.apk"
else
  upload_asset "$SHA_FILE" "CruxCoach-${TAG}.apk.sha256" "text/plain" \
    || die "sidecar upload failed"
  upload_asset "$WORK/CruxCoach-${TAG}.apk" "CruxCoach-${TAG}.apk" \
    "application/vnd.android.package-archive" || die "APK upload failed"
fi

# ---- 6. read it back ---------------------------------------------------
# Names alone are not enough: a truncated upload still has the right name. The
# API reports the stored size, so compare it against the bytes we sent.
if [ "$DRY_RUN" = "false" ]; then
  note "verifying what GitHub actually stored"
  gh_curl "$API/repos/$REPO/releases/$RELEASE_ID/assets" \
    | python3 -c "
import json, sys
want_apk, want_sha, apk_size = sys.argv[1], sys.argv[2], int(sys.argv[3])
assets = {a['name']: a for a in json.load(sys.stdin)}
missing = [n for n in (want_apk, want_sha) if n not in assets]
if missing:
    sys.exit('post-verify: missing asset(s): ' + ', '.join(missing))
if assets[want_apk]['size'] != apk_size:
    sys.exit('post-verify: APK stored as %d bytes, sent %d'
             % (assets[want_apk]['size'], apk_size))
for n in (want_apk, want_sha):
    print('   %-34s %9d bytes  %s' % (n, assets[n]['size'], assets[n]['state']))
" "CruxCoach-${TAG}.apk" "CruxCoach-${TAG}.apk.sha256" "$APK_SIZE" \
    || die "post-verify failed — the release is incomplete"
fi

echo
echo "Published $TAG to https://github.com/$REPO/releases/tag/$TAG"
echo "No Codeberg endpoint was contacted."
