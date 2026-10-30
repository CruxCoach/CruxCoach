#!/usr/bin/env python3
"""Trusted helper for community feature builds from forks.

Runs only from a checkout of `main` in the credential-bearing jobs of
`.github/workflows/community-publish.yml`; it never imports or executes code
from the fork. Subcommands:

  authorize   ask APKTrack to admit a fork build and reserve its versionCode
  verify-apk  check the built APK's package and versionCode against the grant
  comment     tell the pull request what happened, in one updated comment
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = "cruxcoach"
APKTRACK_URL = "https://stats.cruxcoach.org/apktrack"
NSITE = "https://npub1uux22z2rhhcvk9fs9dp60k3uc5f7frdqy5chrsemsxwgnyl0y9dqhx0gem.nsite.lol"
MARKER = "<!-- cruxcoach-community-build -->"
TRACK_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
PACKAGE_RE = re.compile(r"^com\.cruxcoach\.android\.dev\.community\.c_[0-9a-f]{12}$")
LOGIN_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

LINK_HELP = (
    "To publish test builds from your fork, connect your GitHub account to the CruxCoach "
    "community once:\n\n"
    "1. Join the CruxCoach community on Buzz (ask for an invite in a comment here if you "
    "are not a member yet).\n"
    "2. In Buzz, send `/link` as a direct message to **CruxCoach Bot**.\n"
    "3. Open the personal link it sends back and sign in to GitHub.\n\n"
    "Then push again, or re-run the checks, and the test build appears here."
)

REASONS = {
    "not_linked": "This GitHub account is not connected to a CruxCoach community member yet.",
    "not_member": "The Buzz account connected to this GitHub account is not a community member.",
    "not_owner": "Test builds are published only from the contributor's own fork.",
    "membership_unknown": "The community member list is out of date. A maintainer has been notified.",
    "disabled": "Community test builds are not enabled yet.",
    "not_authorized": "Community test builds are not enabled yet.",
}


def write_outputs(path: str, values: dict[str, object]) -> None:
    with open(path, "a", encoding="utf-8") as stream:
        for key, value in values.items():
            text = str(value)
            if "\n" in text or "\r" in text:
                raise ValueError(f"output {key} must be one line")
            stream.write(f"{key}={text}\n")


def require_env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise SystemExit(f"{name} is required")
    return value


def authorize(output: str) -> None:
    owner_login = require_env("OWNER_LOGIN")
    body = {
        "github_id": int(require_env("OWNER_ID")),
        "github_login": owner_login,
        "repository": require_env("REPOSITORY"),
        "branch": require_env("BRANCH"),
        "commit": require_env("COMMIT").lower(),
        "minimum": 1,
    }
    request = urllib.request.Request(
        f"{os.environ.get('APKTRACK_URL', APKTRACK_URL)}/v2/community/{PROJECT}/authorizations",
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-APKTrack-Token": require_env("APKTRACK_COMMUNITY_TOKEN"),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 - fixed https URL
            grant = json.load(response)
    except urllib.error.HTTPError as error:
        detail = json.loads(error.read() or b"{}")
        code = detail.get("code") if isinstance(detail, dict) else None
        if error.code in (403, 404, 503) and code in REASONS:
            write_outputs(output, {"authorized": "false", "reason": code})
            print(f"not admitted: {code}")
            return
        raise SystemExit(f"APKTrack refused the authorization ({error.code}): {detail}") from None
    write_outputs(output, outputs_for(grant, body["branch"], owner_login))


def outputs_for(grant: dict[str, object], branch: str, owner_login: str) -> dict[str, object]:
    """Validate APKTrack's answer before any value reaches a later job."""
    track = str(grant.get("track", ""))
    package = str(grant.get("package_name", ""))
    label = str(grant.get("label", ""))
    source_branch = str(grant.get("branch", ""))
    version_code = grant.get("version_code")
    publisher = grant.get("publisher")
    if not TRACK_RE.fullmatch(track) or not track.startswith("c-"):
        raise SystemExit("APKTrack returned an invalid community track")
    if not PACKAGE_RE.fullmatch(package):
        raise SystemExit("APKTrack returned a package outside the community namespace")
    if not label or len(label) > 80 or any(c in label for c in "\"\\\r\n$`"):
        raise SystemExit("APKTrack returned an invalid label")
    if not isinstance(version_code, int) or not 1 <= version_code <= 2_100_000_000:
        raise SystemExit("APKTrack returned an invalid versionCode")
    if not isinstance(publisher, dict) or not LOGIN_RE.fullmatch(str(publisher.get("github_login", ""))):
        raise SystemExit("APKTrack returned an invalid publisher")
    name = publisher.get("display_name") or publisher.get("github_login")
    return {
        "authorized": "true",
        "track": track,
        "package": package,
        "label": label,
        "version_code": version_code,
        "source_branch": source_branch,
        # The app's build only checks that this starts with feat/; it is shown, not used.
        "feature_branch": f"feat/community/{owner_login}/{branch.removeprefix('feat/')}",
        "publisher_login": publisher["github_login"],
        "publisher_name": re.sub(r"[^\w .@-]", "", str(name))[:64] or publisher["github_login"],
    }


def badging(apk: Path) -> tuple[str, int]:
    """Read package and versionCode with the Android SDK's aapt2, never with fork code."""
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    tools = sorted(Path(android_home, "build-tools").glob("*/aapt2"))
    if not tools:
        raise SystemExit("aapt2 from the Android SDK is required")
    result = subprocess.run(  # noqa: S603 - fixed tool, file path argument
        [str(tools[-1]), "dump", "badging", str(apk)], capture_output=True, text=True, check=True
    )
    return parse_badging(result.stdout)


def parse_badging(text: str) -> tuple[str, int]:
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", text, re.MULTILINE)
    if not match:
        raise SystemExit("aapt2 did not report a package")
    return match.group(1), int(match.group(2))


def verify_apk(apk: Path, package: str, version_code: int) -> None:
    actual_package, actual_version = badging(apk)
    if actual_package != package:
        raise SystemExit(f"APK package {actual_package} differs from the authorized {package}")
    if actual_version != version_code:
        raise SystemExit(f"APK versionCode {actual_version} differs from the reserved {version_code}")
    print(f"verified {actual_package} versionCode {actual_version}")


def install_page(track: str, version_code: int) -> str:
    query = urllib.parse.urlencode({"track": track, "build": str(version_code)})
    return f"{NSITE}/{PROJECT}/?{query}#install"


def comment_body(
    *, reason: str | None, publisher_login: str, track: str = "", version_code: int = 0, name: str = ""
) -> str:
    if reason is not None:
        text = REASONS.get(reason, "No test build was published.")
        extra = f"\n\n{LINK_HELP}" if reason in {"not_linked", "not_member"} else ""
        return f"{MARKER}\n**No test build yet.** {text}{extra}"
    return (
        f"{MARKER}\n**Test build {version_code} by @{publisher_login} is ready:** "
        f"[install page]({install_page(track, version_code)})\n\n"
        f"It was built by CruxCoach CI from this pull request and published under {name}'s "
        "community identity. CruxCoach has not reviewed it; the install page asks testers "
        "whether they trust the publisher before the first download."
    )


def github(method: str, path: str, body: object | None = None) -> object:
    request = urllib.request.Request(
        f"https://api.github.com{path}",
        data=None if body is None else json.dumps(body).encode(),
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {require_env('GITHUB_TOKEN')}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 - fixed https URL
        return json.load(response)


def comment(body: str) -> None:
    repository = require_env("GITHUB_REPOSITORY")
    head = urllib.parse.quote(f"{require_env('OWNER_LOGIN')}:{require_env('BRANCH')}", safe=":")
    pulls = github("GET", f"/repos/{repository}/pulls?state=open&head={head}")
    if not isinstance(pulls, list) or not pulls:
        print("no open pull request for this fork branch; nothing to comment on")
        return
    number = int(pulls[0]["number"])
    comments = github("GET", f"/repos/{repository}/issues/{number}/comments?per_page=100")
    existing = [c for c in comments if isinstance(c, dict) and MARKER in str(c.get("body", ""))]
    if existing:
        github("PATCH", f"/repos/{repository}/issues/comments/{existing[-1]['id']}", {"body": body})
    else:
        github("POST", f"/repos/{repository}/issues/{number}/comments", {"body": body})


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    authorize_parser = sub.add_parser("authorize")
    authorize_parser.add_argument("--github-output", required=True)
    verify_parser = sub.add_parser("verify-apk")
    verify_parser.add_argument("apk", type=Path)
    verify_parser.add_argument("--package", required=True)
    verify_parser.add_argument("--version-code", type=int, required=True)
    comment_parser = sub.add_parser("comment")
    comment_parser.add_argument("--reason")
    comment_parser.add_argument("--publisher-login", required=True)
    comment_parser.add_argument("--track", default="")
    comment_parser.add_argument("--version-code", type=int, default=0)
    comment_parser.add_argument("--name", default="")
    args = parser.parse_args(argv)
    if args.command == "authorize":
        authorize(args.github_output)
    elif args.command == "verify-apk":
        verify_apk(args.apk, args.package, args.version_code)
    else:
        comment(
            comment_body(
                reason=args.reason or None,
                publisher_login=args.publisher_login,
                track=args.track,
                version_code=args.version_code,
                name=args.name,
            )
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
