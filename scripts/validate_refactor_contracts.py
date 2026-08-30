#!/usr/bin/env python3
"""Validate refactor matrices without Gradle, devices, or private material."""

from __future__ import annotations

import base64
import json
import struct
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_PARITY = {"covered", "partial", "planned", "hidden-preserved", "external-gate"}
EXPECTED_UI_DIMENSIONS = {
    "locale": ["en", "de"],
    "theme": ["light", "dark"],
    "width": ["compact", "expanded"],
    "fontScale": [1.0, 1.5],
}


def load(relative: str) -> dict:
    path = ROOT / relative
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise AssertionError(f"{relative} must contain a JSON object")
    return value


def require_file(relative: str) -> None:
    if not (ROOT / relative).is_file():
        raise AssertionError(f"missing referenced file: {relative}")


def validate_parity() -> None:
    matrix = load("docs/refactor/parity-matrix.json")
    ids = [entry["id"] for entry in matrix["capabilities"]]
    if len(ids) != len(set(ids)):
        raise AssertionError("duplicate capability id")
    if matrix["baseline"]["startRoute"] != "board_browser":
        raise AssertionError("Board Browser must remain the regular start route")
    for entry in matrix["capabilities"]:
        if entry["status"] not in ALLOWED_PARITY:
            raise AssertionError(f"invalid parity status for {entry['id']}")
        for evidence in entry["evidence"]:
            require_file(evidence)
    scenario_ids = {entry["id"] for entry in matrix["scenarioHarness"]}
    required = {
        "browser/content", "browser/empty", "browser/error",
        "detail/disconnected", "detail/connected", "session/active",
        "session/resting", "log/success", "log/error", "progress/history",
    }
    if scenario_ids != required:
        raise AssertionError(f"scenario contract drift: {scenario_ids ^ required}")


def validate_compatibility() -> None:
    matrix = load("docs/refactor/compatibility-matrix.json")
    require_file(matrix["databaseOriginFixture"])
    releases = [entry["release"] for entry in matrix["databaseOrigins"]]
    expected = ["v0.1.0", "v0.1.1", "v0.1.2", "v0.1.3", "v0.1.4", "v0.2.0", "v0.2.1", "v0.2.2"]
    if releases != expected:
        raise AssertionError(f"published release matrix drift: {releases}")
    origin_fixture = load(matrix["databaseOriginFixture"])["origins"]
    comparable = [
        {key: entry[key] for key in ("release", "commit", "boardSchema", "secureSchema")}
        for entry in matrix["databaseOrigins"]
    ]
    if comparable != origin_fixture:
        raise AssertionError("database origin fixture and compatibility matrix differ")
    for entry in matrix["databaseOrigins"]:
        if "fixture" in entry:
            require_file(entry["fixture"])
    for entry in matrix["formats"]:
        if "fixture" in entry:
            require_file(entry["fixture"])
        if entry["id"].startswith("backup-v") and entry["write"] != (entry["id"] == "backup-v3"):
            raise AssertionError("only backup v3 may be marked writable")
    for entry in matrix["bleGoldens"]:
        require_file(entry["fixture"])
    vectors = load("docs/refactor/fixtures/ble-golden-frames.json")["vectors"]
    if len({entry["id"] for entry in vectors}) != len(vectors):
        raise AssertionError("duplicate BLE golden id")


def validate_backup_fixtures() -> None:
    for version in (1, 2, 3):
        backup = load(f"docs/refactor/fixtures/backup-v{version}.json")
        if backup.get("version") != version or backup.get("app") != "CruxCoach":
            raise AssertionError(f"invalid backup v{version} fixture envelope")


def validate_ui_scenarios() -> None:
    matrix = load("docs/refactor/ui-scenario-matrix.json")
    parity = load("docs/refactor/parity-matrix.json")
    if matrix["coverageMode"] != "cartesian":
        raise AssertionError("core UI states require Cartesian configuration coverage")
    if matrix["dimensions"] != EXPECTED_UI_DIMENSIONS:
        raise AssertionError("UI rendering dimension contract drift")
    state_ids = [entry["id"] for entry in matrix["states"]]
    parity_ids = [entry["id"] for entry in parity["scenarioHarness"]]
    if state_ids != parity_ids:
        raise AssertionError("UI scenario matrix and parity harness differ")
    capability_ids = {entry["id"] for entry in parity["capabilities"]}
    if any(entry["capability"] not in capability_ids for entry in matrix["states"]):
        raise AssertionError("UI scenario references an unknown capability")
    if matrix["budgets"] != {
        "minimumTouchTargetDp": 48,
        "normalTextContrast": 4.5,
        "largeTextAndNonTextContrast": 3.0,
        "colorOnlyStateEncodingAllowed": False,
        "maximumAutonomousCorrectionRounds": 3,
        "unreviewedGoldenUpdatesAllowed": False,
    }:
        raise AssertionError("UI quality budgets changed without contract review")


def validate_playlist_fixtures() -> None:
    for version in (1, 2):
        fixture = load(f"docs/refactor/fixtures/playlist-link-v{version}.json")
        raw = base64.urlsafe_b64decode(fixture["payload"] + "===")
        if raw[0] != version:
            raise AssertionError(f"playlist v{version} payload version mismatch")
        name_size = raw[1]
        if raw[2:2 + name_size].decode("utf-8") != fixture["decoded"]["name"]:
            raise AssertionError(f"playlist v{version} name mismatch")
        cursor = 2 + name_size
        if version == 1:
            count = raw[cursor]
            cursor += 1
            if len(raw) != cursor + count * 17:
                raise AssertionError("playlist v1 frame length mismatch")
            angle = raw[cursor]
            climb_id = str(uuid.UUID(bytes=raw[cursor + 1:cursor + 17]))
            decoded = fixture["decoded"]["climbs"][0]
            if (count, angle, climb_id) != (1, decoded["angle"], decoded["climbUuid"]):
                raise AssertionError("playlist v1 decoded contract mismatch")
        else:
            order, advance, rest, count = struct.unpack(">BBHB", raw[cursor:cursor + 5])
            cursor += 5
            first_type, angle = raw[cursor:cursor + 2]
            climb_id = str(uuid.UUID(bytes=raw[cursor + 2:cursor + 18]))
            cursor += 18
            second_type, step_rest = struct.unpack(">BH", raw[cursor:cursor + 3])
            expected = fixture["decoded"]
            if (order, advance, rest, count, first_type, angle, climb_id, second_type, step_rest) != (
                0, 0, expected["defaultRestSeconds"], 2, 0,
                expected["steps"][0]["angle"], expected["steps"][0]["climbUuid"],
                1, expected["steps"][1]["seconds"],
            ):
                raise AssertionError("playlist v2 decoded contract mismatch")


if __name__ == "__main__":
    validate_parity()
    validate_compatibility()
    validate_backup_fixtures()
    validate_playlist_fixtures()
    validate_ui_scenarios()
    print("refactor contracts: OK")
