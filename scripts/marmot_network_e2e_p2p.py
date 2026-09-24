#!/usr/bin/env python3
"""Spike M0: two JVM participants on the direct Olm channel, no relay at all.

Port of marmot_network_e2e.py to the peer-to-peer transport: each participant
runs the native Olm engine behind the production Kotlin chokepoint and serves
the direct channel over loopback HTTP; a hostile proxy (marmot_p2p_proxy.py)
sits in front of each one. The invitation is handed over as a QR payload.

Only synthetic identities/content. Requires the prepared endpoint classpath
(./gradlew :androidApp:writeMarmotEndpointClasspath). No network beyond
loopback, no relay, no backend, no device.
"""
import json
import os
import secrets
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from marmot_network_e2e import ROOT, Process  # noqa: E402
from marmot_p2p_proxy import HostileProxy  # noqa: E402

SEED = int(os.environ.get("CC_P2P_SEED", secrets.randbelow(1 << 30)))


def texts(p):
    return [m["text"] for m in p.call("received")]


def peer_state(p, account):
    return next((peer["state"] for peer in p.call("status")["peers"] if peer["account"] == account), None)


def wait(participants, predicate, phase, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        for p in participants:
            p.call("sync")
        if predicate():
            print(json.dumps(dict(path="P2P-TWO", phase=phase, passed=True)), flush=True)
            return
        time.sleep(0.2)
    raise AssertionError("p2p phase timeout: " + phase)


def roundtrip():
    resources, directories, proxies = [], [], []
    try:
        def participant(retained=None):
            if retained is None:
                directory = tempfile.TemporaryDirectory(prefix="cc-p2p-e2e-")
                directories.append(directory)
                retained = dict(directory=directory.name, database_key=secrets.token_hex(32))
            args = [sys.executable, str(ROOT / "scripts/marmot_endpoint.py"), "--prepared", "--restart-harness", "--p2p", "--role", "USER"]
            process = Process(args)
            resources.append(process)
            process.line(json.dumps(retained))  # Only stdin; never logs or argv.
            account = json.loads(process.read())["account"]
            process.call("bootstrap")
            return process, account, retained

        def connect(process, proxy, others):
            proxy.set_target(process.call("port"))
            process.call("discover", urls=[o.url for o in others])

        pa, pb = HostileProxy(SEED), HostileProxy(SEED + 1)
        proxies += [pa, pb]
        started = time.monotonic()
        a, owner, state_a = participant()
        b, friend, state_b = participant()
        cold_start = time.monotonic() - started
        connect(a, pa, [pb])
        connect(b, pb, [pa])

        # Hand-over by two-stage QR: the short code pins the package, which
        # B's device then fetches directly from A's (B does not accept
        # unsolicited invitations; the scan is the consent).
        a.call("invite", peer=friend)
        payload = a.call("invitation_hint", peer=friend)["payload"]
        assert payload.startswith("cruxcoach:i2:")
        b.call("discovery", enabled="false")
        b.call("import_invitation", payload=payload)
        wait([a, b], lambda: peer_state(b, owner) == "invited", "two-stage QR package fetched")
        assert b.call("received") == [], "an unaccepted invitation delivers nothing"
        b.call("accept_invitation", peer=owner)
        b.call("send", peer=owner, text="synthetic hello", token="hello")
        wait([a, b], lambda: "synthetic hello" in texts(a), "acceptance reaches the inviter")
        assert peer_state(a, friend) == "active"
        a.call("send", peer=friend, text="synthetic encrypted delivery", token="d1")
        wait([a, b], lambda: "synthetic encrypted delivery" in texts(b), "encrypted delivery")

        # One account signature per installation (the binding), none per
        # relationship or message.
        signatures = (a.call("signer_ops"), b.call("signer_ops"))
        assert signatures == (1, 1), signatures
        # Abruptly terminate both processes; they come back on new ports.
        for process in (a, b):
            process.child.kill()
            process.child.wait(timeout=5)
        a, recovered_owner, _ = participant(state_a)
        b, recovered_friend, _ = participant(state_b)
        assert recovered_owner == owner and recovered_friend == friend
        connect(a, pa, [pb])
        connect(b, pb, [pa])
        assert "synthetic encrypted delivery" in texts(b), "unacknowledged message survives restart"
        b.call("ack", seqs=[m["seq"] for m in b.call("received")])
        assert b.call("received") == []

        # Out of reach (both directions; either side pulls on its own
        # exchange): the message waits at the sender, then arrives.
        pa.offline = pb.offline = True
        a.call("send", peer=friend, text="synthetic while apart", token="d2")
        for _ in range(5):
            a.call("sync"); b.call("sync")
        assert "synthetic while apart" not in texts(b)
        assert a.call("status")["queued"] >= 1
        pa.offline = pb.offline = False
        wait([a, b], lambda: "synthetic while apart" in texts(b), "delivery once in reach again")

        # Unwedge 1: a gap beyond Olm's 2,000-message window.
        a.call("burn", peer=friend, count="2100")
        a.call("send", peer=friend, text="synthetic behind the gap", token="d3")
        wait([a, b], lambda: a.call("status")["queued"] == 0, "gap refused and session rebuilt")
        a.call("send", peer=friend, text="synthetic after rebuild", token="d4")
        wait([a, b], lambda: "synthetic after rebuild" in texts(b), "delivery after rebuild")
        # Unwedge 2: the receiver lost its session state.
        b.call("forget_sessions", peer=owner)
        a.call("send", peer=friend, text="synthetic into lost state", token="d5")
        wait([a, b], lambda: a.call("status")["queued"] == 0, "lost state refused and rebuilt")
        a.call("send", peer=friend, text="synthetic after second rebuild", token="d6")
        wait([a, b], lambda: "synthetic after second rebuild" in texts(b), "delivery after second rebuild")
        b.call("send", peer=owner, text="synthetic reply", token="r1")
        wait([a, b], lambda: "synthetic reply" in texts(a), "reverse direction after rebuilds")

        # After restarts and two rebuilds the stored binding is reused: no new signature.
        assert (a.call("signer_ops"), b.call("signer_ops")) == (0, 0)

        # Ending purges pending plaintext and admits nothing further.
        b.call("end", peer=owner)
        assert b.call("received") == []
        assert b.call("native_storage_matches", marker="synthetic after second rebuild") == 0
        a.call("send", peer=friend, text="synthetic after end", token="d7")
        for _ in range(5):
            a.call("sync"); b.call("sync")
        assert b.call("received") == [] and peer_state(b, owner) == "ended"
        faults = dict(pa.stats + pb.stats)
        print(json.dumps(dict(path="P2P-TWO", seed=SEED, independent_processes=True, native_crypto="olm/vodozemac",
                              relay=False, blossom=False, two_stage_qr_invitation=True, qr_payload_chars=len(payload),
                              invitation_acceptance=True, encrypted_delivery=True, process_kill_restart=True,
                              encrypted_identity_recovery=True, out_of_reach_then_delivered=True,
                              unwedge_gap=True, unwedge_state_loss=True, signer_ops_per_installation=1,
                              end_purges=True, cold_start_s=round(cold_start, 2), faults=faults)), flush=True)
        assert faults.get("drop_request", 0) + faults.get("drop_response", 0) > 0, "the hostile layer was exercised"
    finally:
        for process in reversed(resources):
            process.close()
        for proxy in proxies:
            proxy.close()
        for directory in directories:
            directory.cleanup()


if __name__ == "__main__":
    roundtrip()
