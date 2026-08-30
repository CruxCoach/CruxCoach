#!/usr/bin/env python3
"""Fail on dependency directions that the staged refactor has made explicit."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def forbidden_imports(root: Path, prefixes: tuple[str, ...]) -> list[str]:
    failures: list[str] = []
    for source in sorted(root.rglob("*.kt")):
        for line_number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            target = line.removeprefix("import ").strip() if line.startswith("import ") else ""
            if target.startswith(prefixes):
                try:
                    display_path = source.relative_to(ROOT)
                except ValueError:
                    display_path = source.relative_to(root)
                failures.append(f"{display_path}:{line_number}: {line}")
    return failures


def main() -> None:
    failures: list[str] = []
    runtime_packages = (
        "aurora",
        "ble",
        "community",
        "data",
        "moonboard",
        "nostr",
        "notification",
        "updater",
        "util",
    )
    for package in runtime_packages:
        failures += forbidden_imports(
            ROOT / f"androidApp/src/main/java/com/cruxcoach/android/{package}",
            ("com.cruxcoach.android.ui.",),
        )
    failures += forbidden_imports(
        ROOT / "androidApp/src/main/java/com/cruxcoach/android/ui",
        (
            "android.bluetooth.BluetoothGatt",
            "app.cash.sqldelight.",
            "com.cruxcoach.db.",
        ),
    )
    failures += forbidden_imports(
        ROOT / "shared/src/commonMain/kotlin",
        ("android.", "androidx.", "com.cruxcoach.android."),
    )
    if failures:
        raise SystemExit("forbidden architecture imports:\n" + "\n".join(failures))
    print("architecture boundaries: OK")


if __name__ == "__main__":
    main()
