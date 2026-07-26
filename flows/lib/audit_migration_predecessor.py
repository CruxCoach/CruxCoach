#!/usr/bin/env python3
"""Audit legacy migration selectors against the three public source tags.

The historical apps did not export Compose testTag values as Android resource
IDs. This audit therefore fails if the predecessor setup uses an id or point
selector and records source-backed text/content-description contracts for each
tag. It is a static provenance gate, not a substitute for the device run.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


TAGS = ("v0.1.4", "v0.2.0", "v0.2.1")
FLOW_REQUIRED_FRAGMENTS = (
    "(Weiter|Next)",
    "(Fertig|Done)",
    "[0-9]+ (Züge|moves)",
    "(Begehung loggen|Log ascent)",
    "(Zu Favoriten hinzufügen|Add to favorites)",
    "(Von Favoriten entfernen|Remove from favorites)",
    'tapOn: "Send"',
    "(4 Sterne|4 stars)",
    "Kommentar \\(optional\\)",
    "Comment \\(optional\\)",
    "(Speichern|Save)",
    'tapOn: "Board Logbook"',
)
SOURCE_PATHS = (
    "androidApp/src/main/res/values/strings.xml",
    "androidApp/src/main/res/values-de/strings.xml",
    "androidApp/src/main/java/com/cruxcoach/android/ui/onboarding/OnboardingScreen.kt",
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/ClimbCard.kt",
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardClimbDetailScreen.kt",
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/AscentLoggingDialog.kt",
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardBrowserScreen.kt",
)

EXPECTED_VALUES = {
    "values": {
        "action_next": "Next",
        "action_done": "Done",
        "action_save": "Save",
        "board_climb_moves": "%d moves",
        "board_ascent_log_title": "Log ascent",
        "board_ascent_send": "Send",
        "board_ascent_comment": "Comment (optional)",
        "board_logbook_title": "Board Logbook",
        "cd_add_favorite": "Add to favorites",
        "cd_remove_favorite": "Remove from favorites",
        "cd_log_ascent": "Log ascent",
        "cd_stars": "%d stars",
    },
    "values-de": {
        "action_next": "Weiter",
        "action_done": "Fertig",
        "action_save": "Speichern",
        "board_climb_moves": "%d Züge",
        "board_ascent_log_title": "Begehung loggen",
        "board_ascent_send": "Send",
        "board_ascent_comment": "Kommentar (optional)",
        "board_logbook_title": "Board Logbook",
        "cd_add_favorite": "Zu Favoriten hinzufügen",
        "cd_remove_favorite": "Von Favoriten entfernen",
        "cd_log_ascent": "Begehung loggen",
        "cd_stars": "%d Sterne",
    },
}

SOURCE_BINDINGS = {
    "androidApp/src/main/java/com/cruxcoach/android/ui/onboarding/OnboardingScreen.kt": (
        "R.string.action_next", "R.string.action_done",
    ),
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/ClimbCard.kt": (
        "R.string.board_climb_moves",
    ),
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardClimbDetailScreen.kt": (
        "R.string.cd_add_favorite", "R.string.cd_remove_favorite", "R.string.cd_log_ascent",
    ),
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/AscentLoggingDialog.kt": (
        "R.string.board_ascent_log_title", "R.string.board_ascent_send",
        "R.string.cd_stars", "R.string.board_ascent_comment", "R.string.action_save",
    ),
    "androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardBrowserScreen.kt": (
        "R.string.board_logbook_title",
    ),
}


def git_show(repo: Path, tag: str, path: str) -> str:
    result = subprocess.run(
        ("git", "-C", str(repo), "show", f"{tag}:{path}"),
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode:
        raise ValueError(f"cannot read public source {tag}:{path}")
    return result.stdout


def parse_string_values(xml_text: str) -> dict[str, str]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise ValueError(f"cannot parse Android string resources: {exc}") from exc
    values: dict[str, str] = {}
    for node in root.findall("string"):
        name = node.get("name")
        if name:
            values[name] = "".join(node.itertext())
    return values


def audit(repo: Path, flow: Path) -> dict[str, object]:
    text = flow.read_text(encoding="utf-8")
    errors: list[str] = []
    if re.search(r"(?m)^\s+id:\s*", text):
        errors.append("predecessor setup contains an id selector")
    if re.search(r"(?m)^\s+point:\s*", text):
        errors.append("predecessor setup contains a point selector")
    missing_from_flow = [value for value in FLOW_REQUIRED_FRAGMENTS if value not in text]
    if missing_from_flow:
        errors.append("expected bilingual selector fragments absent from flow: " + ", ".join(missing_from_flow))

    tag_records: list[dict[str, object]] = []
    for tag in TAGS:
        sources = {path: git_show(repo, tag, path) for path in SOURCE_PATHS}
        localized_values: dict[str, dict[str, str]] = {}
        for locale, expected in EXPECTED_VALUES.items():
            resource_path = f"androidApp/src/main/res/{locale}/strings.xml"
            actual = parse_string_values(sources[resource_path])
            localized_values[locale] = {name: actual.get(name, "<missing>") for name in expected}
            for name, expected_value in expected.items():
                actual_value = actual.get(name)
                if actual_value != expected_value:
                    errors.append(
                        f"{tag} {locale}/{name} is {actual_value!r}; expected {expected_value!r}"
                    )
        for source_path, bindings in SOURCE_BINDINGS.items():
            source = sources[source_path]
            for binding in bindings:
                if binding not in source:
                    errors.append(f"{tag} lacks UI binding {binding} in {source_path}")
        docs = git_show(repo, tag, "docs/testing.md")
        docs_warn = "testTagsAsResourceId" in docs and "resource-id" in docs
        if not docs_warn:
            errors.append(f"{tag} docs do not carry the historical resource-id warning")
        commit = subprocess.run(
            ("git", "-C", str(repo), "rev-parse", f"{tag}^{{commit}}"),
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        tag_records.append(
            {
                "tag": tag,
                "commit": commit,
                "fixture_source": "unmodified-public-tag",
                "selector_mode": "visible-text-and-content-description-only",
                "historical_resource_id_warning": docs_warn,
                "localized_values": localized_values,
            }
        )
    return {"valid": not errors, "tags": tag_records, "errors": errors}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--flow", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.repo.resolve(), args.flow.resolve())
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        result = {"valid": False, "errors": [str(exc)]}
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
