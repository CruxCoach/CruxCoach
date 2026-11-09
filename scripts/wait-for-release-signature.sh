#!/usr/bin/env bash
# Hand the unsigned release APK to the YubiKey signing and wait for it to come back.
#
# From 0.2.5 on the release key is a YubiKey at the maintainer's PC; it never
# reaches the runner (docs/KEY_ROTATION.md). The release workflow builds an
# unsigned APK and calls this script, which
#
#   1. copies it as unsigned.apk, with unsigned.apk.sha256, into
#      "$CRUXCOACH_RELEASE_DROP/<tag>/", a folder the maintainer's PC can reach,
#   2. prints a one-line hint, and
#   3. waits until signed.apk and signed.apk.sha256 appear in the same folder,
#      copies signed.apk to <signed-out> and checks the copy against the
#      sidecar. Only the copy is used later, so the drop folder can change
#      afterwards without changing what is verified and published.
#
# It checks no signature. scripts/verify-release-signature.py does that before
# anything is published.
#
# USAGE
#   scripts/wait-for-release-signature.sh <tag> <unsigned-apk> <signed-out> [options]
#
#     --timeout SECONDS   how long to wait for the signature (default 1800)
#     --interval SECONDS  how often to look (default 10)
#
# EXIT CODES
#   0 signed APK received   1 no valid signed APK in time   2 could not run at all
set -euo pipefail

TIMEOUT=1800
INTERVAL=10
POSITIONAL=()

die() { echo "ERROR: $*" >&2; exit 1; }
cannot_run() { echo "ERROR: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --timeout) TIMEOUT="${2:?}"; shift 2;;
    --interval) INTERVAL="${2:?}"; shift 2;;
    -h|--help) awk 'NR > 1 { if (!/^#/) exit; print }' "$0"; exit 0;;
    -*) cannot_run "unknown option: $1";;
    *) POSITIONAL+=("$1"); shift;;
  esac
done
[ "${#POSITIONAL[@]}" = 3 ] || cannot_run "usage: $0 <tag> <unsigned-apk> <signed-out> [--timeout S] [--interval S]"
TAG="${POSITIONAL[0]}"
UNSIGNED="${POSITIONAL[1]}"
OUT="${POSITIONAL[2]}"

# The tag names a folder, so it must not be able to name any other one.
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || cannot_run "tag must look like v1.2.3, got '$TAG'"
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || cannot_run "--timeout must be whole seconds, got '$TIMEOUT'"
[[ "$INTERVAL" =~ ^[0-9]+(\.[0-9]+)?$ ]] || cannot_run "--interval must be seconds, got '$INTERVAL'"
[ -s "$UNSIGNED" ] || cannot_run "unsigned APK not found: $UNSIGNED"
[ -n "${CRUXCOACH_RELEASE_DROP:-}" ] || cannot_run "CRUXCOACH_RELEASE_DROP must be set on the runner"
# The maintainer sets the drop folder up; a missing one is a setup error, not
# something to create in a place the PC may not see.
[ -d "$CRUXCOACH_RELEASE_DROP" ] || cannot_run "the drop folder in CRUXCOACH_RELEASE_DROP does not exist"
[ -d "$(dirname "$OUT")" ] || cannot_run "folder for the signed copy does not exist: $(dirname "$OUT")"

DIR="$CRUXCOACH_RELEASE_DROP/$TAG"
mkdir -p "$DIR"

# Files left from an earlier run belong to an earlier build: its signature
# must not be taken for this one's, nor its unsigned APK signed by mistake.
for stale in signed.apk signed.apk.sha256 unsigned.apk unsigned.apk.sha256; do
  if [ -e "$DIR/$stale" ]; then
    rm -f "$DIR/$stale"
    echo "Removed $stale left from an earlier run"
  fi
done

# Written under a temporary name and moved into place, so the PC never sees a
# half-copied APK or a sidecar without its APK.
UNSIGNED_SHA="$(sha256sum "$UNSIGNED" | awk '{ print $1 }')"
cp "$UNSIGNED" "$DIR/.unsigned.apk.part"
chmod 0644 "$DIR/.unsigned.apk.part"
mv -f "$DIR/.unsigned.apk.part" "$DIR/unsigned.apk"
printf '%s  unsigned.apk\n' "$UNSIGNED_SHA" > "$DIR/.unsigned.apk.sha256.part"
chmod 0644 "$DIR/.unsigned.apk.sha256.part"
mv -f "$DIR/.unsigned.apk.sha256.part" "$DIR/unsigned.apk.sha256"

# Names the folder by its tag only: the run log is public, the host's paths
# are not. The digest here is the one to sign against: the drop folder is
# writable from outside the run, this log is not.
echo "::notice title=Waiting for the YubiKey signature::Sign $TAG/unsigned.apk (SHA-256 $UNSIGNED_SHA) in the release drop folder and put signed.apk and signed.apk.sha256 next to it, within $((TIMEOUT / 60)) min."

# signed.apk.sha256 holds the digest, alone or as sha256sum writes it.
sidecar_digest() {
  local first
  first="$(awk 'NR == 1 { print $1 }' "$DIR/signed.apk.sha256" | tr 'A-F' 'a-f')"
  [[ "$first" =~ ^[0-9a-f]{64}$ ]] && printf '%s' "$first"
}

DEADLINE=$((SECONDS + TIMEOUT))
PROBLEM=""
while :; do
  if [ -s "$DIR/signed.apk" ] && [ -s "$DIR/signed.apk.sha256" ]; then
    if ! WANT="$(sidecar_digest)"; then
      NEW_PROBLEM="signed.apk.sha256 does not start with a SHA-256 digest"
    elif ! cp "$DIR/signed.apk" "$OUT.part" 2>/dev/null; then
      # Replaced or removed between the test above and the copy.
      NEW_PROBLEM="signed.apk could not be copied"
    else
      GOT="$(sha256sum "$OUT.part" | awk '{ print $1 }')"
      if [ "$GOT" = "$WANT" ]; then
        mv -f "$OUT.part" "$OUT"
        echo "Signed APK received: SHA-256 $GOT"
        exit 0
      fi
      # Also what a copy still in progress looks like, so keep waiting.
      NEW_PROBLEM="signed.apk has SHA-256 $GOT, signed.apk.sha256 says $WANT"
    fi
    rm -f "$OUT.part"
    if [ "$NEW_PROBLEM" != "$PROBLEM" ]; then
      PROBLEM="$NEW_PROBLEM"
      echo "Not yet: $PROBLEM"
    fi
  fi
  if [ "$SECONDS" -ge "$DEADLINE" ]; then
    if [ -n "$PROBLEM" ]; then
      die "no valid signed APK for $TAG within $TIMEOUT s: $PROBLEM"
    fi
    die "no signed APK for $TAG within $TIMEOUT s"
  fi
  sleep "$INTERVAL"
done
