#!/usr/bin/env python3
"""Two separate JVM participants and two real loopback WebSocket relays.

Only synthetic identities/content. Requires the prepared local endpoint
classpath and `cargo build --locked --example local_relay`. No public networking,
CruxCoach backend, account service, production credentials, or device install.
"""
import json
import secrets
import tempfile
from pathlib import Path
import select
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


class Process:
    def __init__(self, args):
        self.child = subprocess.Popen(args, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                      stderr=subprocess.DEVNULL, text=True, bufsize=1)

    def read(self):
        if not select.select([self.child.stdout], [], [], 60)[0]:
            raise RuntimeError("local_protocol_timeout")
        line = self.child.stdout.readline()
        if not line:
            raise RuntimeError("local_process_closed")
        return line.strip()

    def line(self, text):
        self.child.stdin.write(text + "\n")
        self.child.stdin.flush()

    def call(self, op, **fields):
        self.line(json.dumps(dict(op=op, **fields)))
        response = json.loads(self.read())
        if not response.get("ok"):
            raise RuntimeError("local_operation_refused:" + op)
        return response.get("value")

    def close(self):
        if self.child.poll() is None:
            self.child.stdin.close()
            try:
                self.child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.child.terminate()
                self.child.wait(timeout=5)


def roundtrip(role):
    resources = []
    directories = []
    try:
        relays = [Process([str(ROOT / "native/marmot/target/debug/examples/local_relay"), "--loopback-test-relay"]) for _ in range(2)]
        resources.extend(relays)
        endpoints = [relay.read() for relay in relays]
        if not all(url.startswith("ws://127.0.0.1:") for url in endpoints):
            raise RuntimeError("fixture_must_be_loopback")
        def participant(role, retained=None, canonical_slash=False):
            args = [sys.executable, str(ROOT / "scripts/marmot_endpoint.py"), "--prepared", "--restart-harness", "--role", role]
            for url in endpoints:
                args += ["--relay", url + ("/" if canonical_slash else "")]
            if retained is None:
                directory = tempfile.TemporaryDirectory(prefix="cc-process-e2e-")
                directories.append(directory)
                retained = dict(directory=directory.name, database_key=secrets.token_hex(32))
            result = Process(args); resources.append(result)
            result.line(json.dumps(retained)) # Only stdin; never logs or argv.
            return result, json.loads(result.read())["account"], retained
        a, owner, state_a = participant(role); b, recipient, state_b = participant("USER", canonical_slash=True)
        a.call("bootstrap"); b.call("bootstrap")
        assert a.call("pin", peer=recipient, role="USER")
        assert b.call("pin", peer=owner, role=role)
        a.call("invite", peer=recipient); b.call("sync")
        b.call("accept_invitation", peer=owner)
        def tick(first=None, second=None, rounds=2):
            first = first or a; second = second or b
            for _ in range(rounds):
                first.call("sync"); second.call("sync")
        tick()
        assert a.call("offer_synthetic", peer=recipient) is None
        assert a.call("offer_category", peer=recipient)
        tick(); assert b.call("accept_category", peer=owner); tick(b,a)
        snapshot = a.call("offer_synthetic", peer=recipient)
        assert isinstance(snapshot, str)
        tick(rounds=1); assert not b.call("readable", id=snapshot)
        assert b.call("accept_snapshot", id=snapshot); tick(b,a,3)
        assert b.call("readable", id=snapshot)
        assert any(row["id"] == snapshot and row["state"] == "DELIVERED" for row in a.call("status")["snapshots"])
        assert b.call("offer_synthetic", peer=owner) is None, "no reverse grant or server privilege"
        # Abruptly terminate both OS processes after durable delivery. Neither
        # can retain an in-memory signer, vault key or engine across this boundary.
        for process in (a, b):
            process.child.kill(); process.child.wait(timeout=5)
        a, recovered_owner, _ = participant(role, state_a)
        b, recovered_recipient, _ = participant("USER", state_b, canonical_slash=True)
        assert recovered_owner == owner and recovered_recipient == recipient
        tick()
        assert b.call("readable", id=snapshot)
        relays[0].line("offline")
        assert a.call("revoke", id=snapshot); tick(rounds=3)
        assert not b.call("readable", id=snapshot)
        assert any(row["id"] == snapshot and row["state"] == "REVOKED" for row in b.call("status")["snapshots"])
        print(json.dumps(dict(path=role + "-USER", independent_processes=True, native_crypto=True,
                             bootstrap=True, consent=True, encrypted_delivery=True, recipient_receipt=True,
                             process_kill_restart=True, encrypted_identity_recovery=True, equivalent_relay_urls=True, redundant_relay_outage=True, revoked_access_denied=True,
                             reverse_grant=False, cruxcoach_backend=False, cruxcoach_relay=False)), flush=True)
    finally:
        for process in reversed(resources):
            process.close()
        for directory in directories:
            directory.cleanup()


if __name__ == "__main__":
    roundtrip("USER")
    roundtrip("SERVER")
