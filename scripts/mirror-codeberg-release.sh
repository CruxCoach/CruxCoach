#!/usr/bin/env bash
# Mirror the exact APK already published on GitHub to a Codeberg release.
#
# This script never builds or signs. The GitHub release publisher owns those
# checks; this second publisher receives the very same APK, recreates the same
# sha256sum sidecar, uploads sidecar first and APK last, and downloads both
# assets again to prove that Codeberg stored byte-identical files.
#
# Authentication is read from CODEBERG_TOKEN, CODEBERG_TOKEN_FILE, or the
# runner-local ~/.config/cruxcoach/codeberg-release-token (mode 600). The token
# is passed to curl through a header file descriptor and to git through a
# one-shot credential helper, so it is never part of a command line or remote.
set -euo pipefail

REPO="CruxCoach/CruxCoach"
API="https://codeberg.org/api/v1"
APK=""
NOTES_FILE=""
DRY_RUN="false"
TAG=""

die() { echo "ERROR: $*" >&2; exit 1; }
cannot_run() { echo "ERROR: $*" >&2; exit 2; }
note() { echo "-- $*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:?}"; shift 2;;
    --notes-file) NOTES_FILE="${2:?}"; shift 2;;
    --repo) REPO="${2:?}"; shift 2;;
    --dry-run) DRY_RUN="true"; shift;;
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
[ -s "$APK" ] || cannot_run "APK not found or empty: $APK"
if [ -n "$NOTES_FILE" ]; then
  [ -f "$NOTES_FILE" ] || cannot_run "release notes not found: $NOTES_FILE"
  BODY="$(cat "$NOTES_FILE")"
else
  BODY="CruxCoach $TAG"
fi

COMMIT="$(git rev-parse "${TAG}^{commit}" 2>/dev/null)" \
  || cannot_run "local tag $TAG is missing; publish the GitHub release first"
APK_SIZE="$(stat -c%s "$APK")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
APK_NAME="CruxCoach-${TAG}.apk"
SHA_NAME="${APK_NAME}.sha256"
cp "$APK" "$WORK/$APK_NAME"
(cd "$WORK" && sha256sum "$APK_NAME" > "$SHA_NAME")
APK_SHA="$(cut -d' ' -f1 < "$WORK/$SHA_NAME")"

TOKEN="${CODEBERG_TOKEN:-}"
if [ -n "${CODEBERG_TOKEN_FILE:-}" ]; then
  TOKEN_PATH="$CODEBERG_TOKEN_FILE"
elif [ -n "${CRUXCOACH_SECRETS_DIR:-}" ] && \
     [ -f "$CRUXCOACH_SECRETS_DIR/codeberg-release-token" ]; then
  TOKEN_PATH="$CRUXCOACH_SECRETS_DIR/codeberg-release-token"
else
  TOKEN_PATH="$HOME/.config/cruxcoach/codeberg-release-token"
fi
if [ -z "$TOKEN" ] && [ -f "$TOKEN_PATH" ]; then
  TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_PATH")"
  PERMS="$(stat -c%a "$TOKEN_PATH" 2>/dev/null || echo '?')"
  case "$PERMS" in
    600|400) ;;
    *) cannot_run "$TOKEN_PATH is mode $PERMS; require 600 or 400";;
  esac
fi
[ -n "$TOKEN" ] || cannot_run \
  "no Codeberg token found; provision $TOKEN_PATH mode 600 with repository write access"

cb_curl() {
  curl -sS --max-time 300 \
    -H @<(printf 'Authorization: token %s\n' "$TOKEN") \
    -H "Accept: application/json" \
    -H "User-Agent: cruxcoach-release-mirror/1.0" \
    "$@"
}

note "mirroring $TAG at $COMMIT ($APK_SIZE bytes, sha256 $APK_SHA)"
if [ "$DRY_RUN" = "true" ]; then
  note "[dry-run] would push $TAG and replace $SHA_NAME then $APK_NAME on Codeberg"
  exit 0
fi

# Push only the explicit release tag. The token stays out of argv and git
# configuration; clearing inherited helpers also prevents an unrelated cached
# credential from silently selecting a different Codeberg identity.
CODEBERG_GIT_URL="https://codeberg.org/${REPO}.git"
# shellcheck disable=SC2016
GIT_TERMINAL_PROMPT=0 \
CRUXCOACH_CODEBERG_TOKEN="$TOKEN" \
  git -c credential.helper= \
      -c credential.helper='!f() { printf "username=x-access-token\npassword=%s\n" "$CRUXCOACH_CODEBERG_TOKEN"; }; f' \
      push --force "$CODEBERG_GIT_URL" "refs/tags/$TAG" 2>&1 | sed 's/^/   /' \
  || cannot_run "could not push $TAG to Codeberg"

RELEASE_JSON="$WORK/release.json"
HTTP_CODE="$(cb_curl -o "$RELEASE_JSON" -w '%{http_code}' \
  "$API/repos/$REPO/releases/tags/$TAG")"
if [ "$HTTP_CODE" = "200" ]; then
  RELEASE_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$RELEASE_JSON")"
  note "Codeberg release already exists (id $RELEASE_ID); replacing its matching assets"
elif [ "$HTTP_CODE" = "404" ]; then
  CREATE_JSON="$WORK/create.json"
  for ATTEMPT in 1 2 3; do
    HTTP_CODE="$(cb_curl -o "$CREATE_JSON" -w '%{http_code}' \
      -X POST -H 'Content-Type: application/json' \
      -d "$(python3 -c 'import json,sys; print(json.dumps({"tag_name":sys.argv[1]}))' "$TAG")" \
      "$API/repos/$REPO/releases")"
    [ "$HTTP_CODE" = "201" ] && break
    echo "   release creation failed (attempt $ATTEMPT): HTTP $HTTP_CODE" >&2
    sleep 3
  done
  [ "$HTTP_CODE" = "201" ] || die "Codeberg release creation failed"
  RELEASE_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$CREATE_JSON")"
  note "created Codeberg release id $RELEASE_ID"
else
  sed 's/^/   /' "$RELEASE_JSON" >&2 || true
  die "Codeberg release lookup failed: HTTP $HTTP_CODE"
fi

PATCH_JSON="$WORK/patch.json"
python3 -c '
import json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "name": "CruxCoach " + sys.argv[2],
    "body": sys.argv[3],
    "draft": False,
    "prerelease": False,
}))
' "$PATCH_JSON" "$TAG" "$BODY"
HTTP_CODE="$(cb_curl -o "$WORK/patch-response.json" -w '%{http_code}' \
  -X PATCH -H 'Content-Type: application/json' --data-binary "@$PATCH_JSON" \
  "$API/repos/$REPO/releases/$RELEASE_ID")"
[ "$HTTP_CODE" = "200" ] || die "Codeberg release metadata update failed: HTTP $HTTP_CODE"

replace_asset() {
  local FILE="$1" NAME="$2" ASSET_ID CODE
  HTTP_CODE="$(cb_curl -o "$RELEASE_JSON" -w '%{http_code}' \
    "$API/repos/$REPO/releases/$RELEASE_ID")"
  [ "$HTTP_CODE" = "200" ] || return 1
  ASSET_ID="$(python3 -c '
import json, sys
for asset in json.load(open(sys.argv[1])).get("assets", []):
    if asset.get("name") == sys.argv[2]:
        print(asset["id"])
        break
' "$RELEASE_JSON" "$NAME")"
  if [ -n "$ASSET_ID" ]; then
    note "replacing existing Codeberg asset $NAME"
    CODE="$(cb_curl -o /dev/null -w '%{http_code}' -X DELETE \
      "$API/repos/$REPO/releases/$RELEASE_ID/assets/$ASSET_ID")"
    [ "$CODE" = "204" ] || [ "$CODE" = "200" ] || return 1
  fi
  for ATTEMPT in 1 2 3; do
    CODE="$(cb_curl -o "$WORK/upload.json" -w '%{http_code}' -X POST \
      -F "attachment=@${FILE};filename=${NAME}" \
      "$API/repos/$REPO/releases/$RELEASE_ID/assets")"
    [ "$CODE" = "201" ] && return 0
    echo "   upload of $NAME failed (attempt $ATTEMPT): HTTP $CODE" >&2
    sleep 3
  done
  return 1
}

# Load-bearing ordering: the updater considers a release only when both files
# exist. A failed long APK upload therefore leaves an obviously incomplete
# release containing only the tiny sidecar, never a deceptive APK-only one.
replace_asset "$WORK/$SHA_NAME" "$SHA_NAME" || die "Codeberg sidecar upload failed"
replace_asset "$WORK/$APK_NAME" "$APK_NAME" || die "Codeberg APK upload failed"

HTTP_CODE="$(cb_curl -o "$RELEASE_JSON" -w '%{http_code}' \
  "$API/repos/$REPO/releases/$RELEASE_ID")"
[ "$HTTP_CODE" = "200" ] || die "Codeberg post-verification lookup failed: HTTP $HTTP_CODE"
python3 -c '
import json, sys
release, apk_name, sha_name, apk_size = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3], int(sys.argv[4])
assets = {asset["name"]: asset for asset in release.get("assets", [])}
missing = [name for name in (apk_name, sha_name) if name not in assets]
if missing:
    raise SystemExit("missing Codeberg asset(s): " + ", ".join(missing))
if int(assets[apk_name].get("size", -1)) != apk_size:
    raise SystemExit("Codeberg APK size mismatch")
' "$RELEASE_JSON" "$APK_NAME" "$SHA_NAME" "$APK_SIZE"

BASE="https://codeberg.org/$REPO/releases/download/$TAG"
curl --fail --location --retry 4 --retry-all-errors --connect-timeout 10 --max-time 300 \
  "$BASE/$SHA_NAME" --output "$WORK/stored.sha256"
curl --fail --location --retry 4 --retry-all-errors --connect-timeout 10 --max-time 300 \
  "$BASE/$APK_NAME" --output "$WORK/stored.apk"
cmp -s "$WORK/$SHA_NAME" "$WORK/stored.sha256" || die "Codeberg sidecar is not byte-identical"
cmp -s "$WORK/$APK_NAME" "$WORK/stored.apk" || die "Codeberg APK is not byte-identical"

echo
echo "Mirrored $TAG to https://codeberg.org/$REPO/releases/tag/$TAG"
