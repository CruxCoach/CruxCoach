#!/usr/bin/env python3
"""Two separate JVM participants and two real loopback WebSocket relays.

Only synthetic identities/content. Requires the prepared local endpoint
classpath and `cargo build --locked --example local_relay`. No public networking,
CruxCoach backend, account service, production credentials, or device install.

v2 transport: each participant runs the native host (in-process relay, relay
pool, replicator) behind the production Kotlin chokepoint. The fixture relays
deliver every event twice, refuse NIP-77 and can go offline.
"""
import json
import secrets
import tempfile
import time
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
            raise RuntimeError("local_operation_refused:" + op + ":" + str(response.get("error")))
        return response.get("value")

    def attempt(self, op, **fields):
        self.line(json.dumps(dict(op=op, **fields)))
        return json.loads(self.read())

    def close(self):
        if self.child.poll() is None:
            self.child.stdin.close()
            try:
                self.child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.child.terminate()
                self.child.wait(timeout=5)


def wait(participants, predicate, phase, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        for p in participants:
            p.call("sync")
        if predicate():
            return
        time.sleep(0.3)
    raise AssertionError("transport phase timeout: " + phase)


def texts(p):
    return [m["text"] for m in p.call("received")]


def peer_state(p, account):
    return next((peer["state"] for peer in p.call("status")["peers"] if peer["account"] == account), None)


def roundtrip(label):
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
            result = Process(args)
            resources.append(result)
            result.line(json.dumps(retained))  # Only stdin; never logs or argv.
            return result, json.loads(result.read())["account"], retained

        a, owner, state_a = participant(label)
        b, friend, state_b = participant("USER", canonical_slash=True)
        a.call("bootstrap")
        b.call("bootstrap")
        wait([a, b], lambda: a.call("status")["outbound_pending"] == 0 and b.call("status")["outbound_pending"] == 0, "discovery published")
        a.call("invite", peer=friend)
        wait([a, b], lambda: peer_state(b, owner) == "invited", "invitation received")
        assert b.call("received") == [], "an unaccepted invitation delivers nothing"
        b.call("accept_invitation", peer=owner)
        b.call("send", peer=owner, text="synthetic hello", token="hello")
        wait([a, b], lambda: "synthetic hello" in texts(a), "acceptance reaches the inviter")
        assert peer_state(a, friend) == "active"
        a.call("send", peer=friend, text="synthetic encrypted delivery", token="d1")
        wait([a, b], lambda: "synthetic encrypted delivery" in texts(b), "encrypted delivery")

        # Abruptly terminate both OS processes. Neither can keep an in-memory
        # signer or engine; unacknowledged plaintext survives natively only.
        for process in (a, b):
            process.child.kill()
            process.child.wait(timeout=5)
        a, recovered_owner, _ = participant(label, state_a)
        b, recovered_friend, _ = participant("USER", state_b, canonical_slash=True)
        assert recovered_owner == owner and recovered_friend == friend
        for p in (a, b):
            p.call("online", online="true")
        assert "synthetic encrypted delivery" in texts(b), "unacknowledged message survives restart"
        b.call("ack", seqs=[m["seq"] for m in b.call("received")])
        assert b.call("received") == []

        # One redundant relay goes offline; the other still carries traffic.
        relays[0].line("offline")
        a.call("send", peer=friend, text="synthetic via redundant relay", token="d2")
        wait([a, b], lambda: "synthetic via redundant relay" in texts(b), "redundant relay outage")

        # An MLS self-update (post-compromise security) keeps the friendship.
        before = a.call("epoch", peer=friend)
        a.call("rotate", peer=friend)
        wait([a, b], lambda: a.call("epoch", peer=friend) > before and a.call("epoch", peer=friend) == b.call("epoch", peer=owner), "epoch change")
        deadline = time.monotonic() + 30
        while True:
            response = a.attempt("send", peer=friend, text="synthetic after rotation", token="d3")
            if response.get("ok"):
                break
            assert response.get("error") == "session_busy" and time.monotonic() < deadline, response
            a.call("sync"); b.call("sync"); time.sleep(0.3)
        wait([a, b], lambda: "synthetic after rotation" in texts(b), "delivery after rotation")
        assert peer_state(a, friend) == "active" and peer_state(b, owner) == "active"

        # Ending purges pending plaintext and admits nothing further.
        b.call("end", peer=owner)
        assert b.call("received") == []
        assert b.call("native_storage_matches", marker="synthetic after rotation") == 0
        a.call("send", peer=friend, text="synthetic after end", token="d4")
        for _ in range(3):
            a.call("sync"); b.call("sync")
        assert b.call("received") == [] and peer_state(b, owner) == "ended"
        print(json.dumps(dict(path=label + "-USER", independent_processes=True, native_crypto=True,
                              in_process_relay=True, bootstrap=True, invitation_acceptance=True, encrypted_delivery=True,
                              process_kill_restart=True, encrypted_identity_recovery=True, equivalent_relay_urls=True,
                              redundant_relay_outage=True, epoch_change_keeps_friendship=True, end_purges=True,
                              cruxcoach_backend=False, cruxcoach_relay=False)), flush=True)
    finally:
        for process in reversed(resources):
            process.close()
        for directory in directories:
            directory.cleanup()


if __name__ == "__main__":
    roundtrip("USER")
    roundtrip("SERVER")
