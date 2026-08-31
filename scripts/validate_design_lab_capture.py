#!/usr/bin/env python3
"""Validate a complete, artifact-bound compact DesignLab capture."""

from __future__ import annotations

import argparse
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


SCENARIOS = (
    "log/new-send",
    "log/new-attempt",
    "log/edit-send",
    "log/saving",
    "log/success",
    "log/error",
    "browser/content",
    "browser/empty",
    "browser/error",
    "session/active",
    "session/resting",
    "session/paused",
    "session/active-no-climb",
    "detail/disconnected",
    "detail/connected",
    "progress/history",
    "progress/empty",
    "progress/error",
)
VARIANTS = tuple(
    f"{theme}-{locale}-{font_scale}"
    for theme in ("light", "dark")
    for locale in ("en", "de")
    for font_scale in ("1.0", "1.5")
)
REQUIRED_FILES = ("screenshot.png", "semantics.xml", "environment.txt")
REQUIRED_ENVIRONMENT_KEYS = (
    "package",
    "scenario",
    "theme",
    "locale",
    "font_scale",
    "apk_source_commit",
    "apk_release_sha256",
    "installed_version_code",
    "installed_version_name",
    "installed_update_time",
    "workspace_commit",
)
BOUNDS_PATTERN = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
DENSITY_PATTERN = re.compile(r"(?:Physical|Override) density: (\d+)")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Validate the 18-scenario EN/DE, light/dark, 1.0/1.5 compact "
            "DesignLab capture matrix."
        ),
    )
    parser.add_argument(
        "capture_root",
        type=Path,
        help="directory containing scenario paths, usually <output>/compact",
    )
    return parser.parse_args()


def parse_environment(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def descendant_label(node: ET.Element) -> str:
    labels: list[str] = []
    for descendant in node.iter("node"):
        for attribute in ("text", "content-desc"):
            value = descendant.attrib.get(attribute, "").strip()
            if value:
                labels.append(value)
    return " | ".join(labels)


def main() -> int:
    root = parse_args().capture_root.resolve()
    failures: list[str] = []
    capture_count = 0
    node_count = 0
    artifact_identities: set[tuple[str, ...]] = set()
    locale_payloads: dict[tuple[str, str], tuple[str, ...]] = {}

    for scenario in SCENARIOS:
        for variant in VARIANTS:
            capture = root / scenario / variant
            missing = [name for name in REQUIRED_FILES if not (capture / name).is_file()]
            if missing:
                failures.append(f"{scenario}/{variant}: missing {', '.join(missing)}")
                continue
            capture_count += 1

            environment_path = capture / "environment.txt"
            environment_text = environment_path.read_text(encoding="utf-8")
            environment = parse_environment(environment_path)
            missing_keys = [key for key in REQUIRED_ENVIRONMENT_KEYS if not environment.get(key)]
            if missing_keys:
                failures.append(
                    f"{scenario}/{variant}: missing environment keys {', '.join(missing_keys)}",
                )
                continue
            theme, locale, font_scale = variant.split("-")
            expected_environment = {
                "scenario": scenario,
                "theme": theme,
                "locale": locale,
                "font_scale": font_scale,
            }
            for key, expected in expected_environment.items():
                if environment[key] != expected:
                    failures.append(
                        f"{scenario}/{variant}: {key}={environment[key]!r}, expected {expected!r}",
                    )
            if not COMMIT_PATTERN.fullmatch(environment["apk_source_commit"]):
                failures.append(f"{scenario}/{variant}: invalid apk_source_commit")
            if not SHA256_PATTERN.fullmatch(environment["apk_release_sha256"]):
                failures.append(f"{scenario}/{variant}: invalid apk_release_sha256")
            artifact_identities.add(
                tuple(
                    environment[key]
                    for key in (
                        "package",
                        "apk_source_commit",
                        "apk_release_sha256",
                        "installed_version_code",
                        "installed_version_name",
                        "installed_update_time",
                    )
                ),
            )
            densities = [int(value) for value in DENSITY_PATTERN.findall(environment_text)]
            if not densities:
                failures.append(f"{scenario}/{variant}: missing display density")
                continue
            minimum_touch_target_px = math.ceil(48 * densities[-1] / 160)

            try:
                tree = ET.parse(capture / "semantics.xml")
            except ET.ParseError as error:
                failures.append(f"{scenario}/{variant}: invalid semantics XML: {error}")
                continue

            nodes = list(tree.iter("node"))
            node_count += len(nodes)
            root_bounds = next(
                (BOUNDS_PATTERN.fullmatch(node.attrib.get("bounds", "")) for node in nodes),
                None,
            )
            if root_bounds is None:
                failures.append(f"{scenario}/{variant}: no screen bounds")
                continue
            _, _, screen_width, screen_height = map(int, root_bounds.groups())
            labels: list[str] = []
            for node in nodes:
                attributes = node.attrib
                label = descendant_label(node)
                direct_label = " | ".join(
                    value
                    for value in (
                        attributes.get("text", "").strip(),
                        attributes.get("content-desc", "").strip(),
                    )
                    if value
                )
                if direct_label:
                    labels.append(direct_label)
                bounds = BOUNDS_PATTERN.fullmatch(attributes.get("bounds", ""))
                if bounds is None:
                    failures.append(
                        f"{scenario}/{variant}: malformed bounds on {attributes.get('class')}",
                    )
                    continue
                x1, y1, x2, y2 = map(int, bounds.groups())
                if x1 < 0 or y1 < 0 or x2 > screen_width or y2 > screen_height:
                    failures.append(
                        f"{scenario}/{variant}: out-of-bounds {attributes.get('class')} "
                        f"{attributes.get('bounds')}",
                    )
                if attributes.get("clickable") == "true" and (
                    x2 - x1 < minimum_touch_target_px or y2 - y1 < minimum_touch_target_px
                ):
                    failures.append(
                        f"{scenario}/{variant}: touch target below 48dp "
                        f"{attributes.get('class')} {attributes.get('bounds')} {label!r}",
                    )
                if attributes.get("NAF") == "true" or (
                    attributes.get("checkable") == "true" and not label
                ):
                    failures.append(
                        f"{scenario}/{variant}: unlabeled/NAF checkable {attributes.get('class')} "
                        f"{attributes.get('bounds')}",
                    )
            locale_payloads[(scenario, variant)] = tuple(labels)

    if len(artifact_identities) != 1:
        failures.append(
            f"capture matrix contains {len(artifact_identities)} artifact identities; expected 1",
        )

    for scenario in SCENARIOS:
        for theme in ("light", "dark"):
            for font_scale in ("1.0", "1.5"):
                english = locale_payloads.get((scenario, f"{theme}-en-{font_scale}"))
                german = locale_payloads.get((scenario, f"{theme}-de-{font_scale}"))
                if english is not None and english == german:
                    failures.append(
                        f"{scenario}/{theme}-{font_scale}: EN and DE semantic payloads are identical",
                    )

    expected_count = len(SCENARIOS) * len(VARIANTS)
    print(
        f"Validated {capture_count}/{expected_count} captures, {node_count} semantic nodes, "
        f"{len(artifact_identities)} artifact identity.",
    )
    if failures:
        print(f"DesignLab capture validation failed with {len(failures)} finding(s):", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("DesignLab capture validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
