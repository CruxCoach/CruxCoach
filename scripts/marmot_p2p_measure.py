#!/usr/bin/env python3
"""Spike M0 measurement (not a test, not in CI): the unchanged sync layer with
N training records from acceptance to a converged friend, over the direct Olm
channel (--mode p2p, loopback HTTP) or over v2 MLS and one loopback relay
(--mode relay). N is capped by the sync layer (SharingRecords.MAX_RECORDS =
1,000); larger initial states need the M2 `snapshot` message.

Synthetic identities and records only; prints one JSON line.
"""
import argparse
import datetime
import json
import secrets
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from marmot_network_e2e import ROOT, Process  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["p2p", "relay"], default="p2p")
    parser.add_argument("--records", type=int, default=1000)
    args = parser.parse_args()
    resources, directories = [], []
    try:
        relay_urls = []
        if args.mode == "relay":
            relay = Process([str(ROOT / "native/marmot/target/debug/examples/local_relay"), "--loopback-test-relay"])
            resources.append(relay)
            relay_urls = [relay.read()]

        def participant():
            directory = tempfile.TemporaryDirectory(prefix="cc-p2p-measure-")
            directories.append(directory)
            cmd = [sys.executable, str(ROOT / "scripts/marmot_endpoint.py"), "--prepared", "--restart-harness", "--role", "USER"]
            cmd += ["--p2p"] if args.mode == "p2p" else sum((["--relay", u] for u in relay_urls), [])
            p = Process(cmd)
            resources.append(p)
            p.line(json.dumps(dict(directory=directory.name, database_key=secrets.token_hex(32))))
            account = json.loads(p.read())["account"]
            p.call("bootstrap")
            return p, account

        a, owner = participant()
        b, friend = participant()
        if args.mode == "p2p":
            a.call("discover", urls=[f"http://127.0.0.1:{b.call('port')}"])
            b.call("discover", urls=[f"http://127.0.0.1:{a.call('port')}"])
        today = datetime.date.today().isoformat()
        started = time.monotonic()
        for i in range(args.records):
            a.call("source_training", id=f"m{i:05d}", attempts=str(1 + i % 9), date=today)
        fill_s = time.monotonic() - started
        a.call("preset", circle="FRIENDS", categories=["TRAINING_HISTORY"], days=30)
        for p in (a, b):
            p.call("automatic", enabled="true")
        a.call("request", peer=friend, circle="FRIENDS")
        deadline = time.monotonic() + 600
        while owner not in b.call("friends")["invitations"]:
            assert time.monotonic() < deadline, "invitation"
            time.sleep(0.2)
        accepted = time.monotonic()
        b.call("accept", peer=owner, circle="ACQUAINTANCES", outgoing="false")
        expected = a.call("source_hashes", peer=friend)
        assert len(expected) == args.records, len(expected)
        while True:
            f = next((f for f in b.call("friends")["friends"] if f["peer"] == owner), None)
            if f is not None and f["records"] == expected:
                break
            assert time.monotonic() < deadline, "convergence"
            time.sleep(0.2)
        converged = time.monotonic() - accepted
        while not next(f for f in a.call("friends")["friends"] if f["peer"] == friend)["confirmed"]:
            assert time.monotonic() < deadline, "ack"
            time.sleep(0.2)
        print(json.dumps(dict(measure="initial_state_through_sync_layer", mode=args.mode, records=args.records,
                              source_fill_s=round(fill_s, 1), accept_to_converged_s=round(converged, 1),
                              accept_to_acknowledged_s=round(time.monotonic() - accepted, 1))), flush=True)
    finally:
        for p in reversed(resources):
            p.close()
        for d in directories:
            d.cleanup()


if __name__ == "__main__":
    main()
