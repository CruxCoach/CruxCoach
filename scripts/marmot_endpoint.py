#!/usr/bin/env python3
"""Run the optional synthetic local endpoint. No production credentials accepted.

Builds focused host classes/JNI only. Pass --prepared after the first invocation
when starting two separate participant processes in the same checkout.
"""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepared", action="store_true")
    parser.add_argument("--restart-harness", action="store_true", help="Loopback-only encrypted restart fixture; initial configuration on stdin")
    parser.add_argument("--role", choices=["USER", "SERVER"], default="SERVER")
    parser.add_argument("--relay", action="append", default=[])
    args = parser.parse_args()
    if not args.prepared:
        subprocess.run([str(ROOT / "gradlew"), ":androidApp:writeMarmotEndpointClasspath", "--console=plain"], cwd=ROOT, check=True)
    generated = ROOT / "androidApp/build/generated/marmot"
    classpath = (generated / "endpoint-classpath.txt").read_text()
    java = str(Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")) / "bin/java")
    os.execv(java, [java, f"-Djava.library.path={generated / 'testNative'}", "-cp", classpath,
                   "com.cruxcoach.android.sharing.MarmotEndpointMain", "--synthetic", args.role, *(["--restart-harness"] if args.restart_harness else []), *args.relay])


if __name__ == "__main__":
    main()
