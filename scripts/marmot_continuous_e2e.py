#!/usr/bin/env python3
"""Real MDK/JNI in three independent synthetic JVMs; two hostile local relays.

v2 sync semantics end to end: presets, friendship request/acceptance,
manifest/full/delta/ack/resync over MLS, narrowing, the per-person switch,
ending and a new friendship, process kill/restart, total relay outage with a
withdrawn queued update, and an MLS epoch change that keeps the friendship.

No backend. CruxCoach's canonical relay is fault-mapped to a closed loopback
port by the test-only native harness. Keys only cross stdin, never logs/argv.
The fixture delivers every event twice and refuses NIP-77.
"""
import datetime
import json
import secrets
import sys
import tempfile
import time
from marmot_network_e2e import ROOT, Process

PROFILE = 'PROFILE_AND_GOALS'
TRAINING = 'TRAINING_HISTORY'
NOTES = 'PRIVATE_NOTES'


def run():
    resources, directories = [], []
    try:
        relays = [Process([str(ROOT / 'native/marmot/target/debug/examples/local_relay'), '--loopback-test-relay']) for _ in range(2)]
        resources.extend(relays)
        endpoints = [r.read() for r in relays]
        assert all(e.startswith('ws://127.0.0.1:') for e in endpoints)

        def participant(retained=None):
            if retained is None:
                directory = tempfile.TemporaryDirectory(prefix='cc-continuous-process-')
                directories.append(directory)
                retained = dict(directory=directory.name, database_key=secrets.token_hex(32))
            args = [sys.executable, str(ROOT / 'scripts/marmot_endpoint.py'), '--prepared', '--restart-harness', '--unreachable-cruxcoach', '--role', 'USER']
            for endpoint in endpoints:
                args += ['--relay', endpoint]
            p = Process(args)
            resources.append(p)
            p.line(json.dumps(retained))
            account = json.loads(p.read())['account']
            return p, account, retained

        a, owner, sa = participant()
        b, friend_b, sb = participant()
        c, friend_c, sc = participant()
        today = datetime.date.today()

        def wait(predicate, phase, seconds=120):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                if predicate():
                    print(json.dumps(dict(path='THREE-USERS', phase=phase, passed=True)), flush=True)
                    return
                time.sleep(0.5)
            raise AssertionError('continuous phase timeout: ' + phase)

        def friend(p, peer):
            return next((f for f in p.call('friends')['friends'] if f['peer'] == peer), None)

        def matches(receiver, owner_process, owner_account, receiver_account):
            f = friend(receiver, owner_account)
            return f is not None and f['records'] == owner_process.call('source_hashes', peer=receiver_account)

        for p in (a, b, c):
            p.call('bootstrap')
        a.call('source_profile', name='Synthetic initial profile')
        a.call('source_note', id='n1', text='Synthetic initial beta')
        a.call('source_note', id='n2', text='Synthetic note to delete')
        a.call('source_training', id='today', attempts='3', date=today.isoformat())
        a.call('source_training', id='excluded-history', attempts='8', date='2000-01-01')
        b.call('source_note', id='b-own', text='Synthetic B private original marker')
        c.call('source_profile', name='Synthetic C private original marker')
        # Presets start empty: nothing is shared until chosen.
        a.call('preset', circle='FRIENDS', categories=[PROFILE, TRAINING], days=30)
        a.call('preset', circle='ACQUAINTANCES', categories=[NOTES], days=30)
        b.call('preset', circle='ACQUAINTANCES', categories=[NOTES])
        c.call('preset', circle='ACQUAINTANCES', categories=[PROFILE])
        for p in (a, b, c):
            p.call('automatic', enabled='true')

        a.call('request', peer=friend_b, circle='FRIENDS')
        a.call('request', peer=friend_c, circle='ACQUAINTANCES')
        wait(lambda: owner in b.call('friends')['invitations'] and owner in c.call('friends')['invitations'], 'invitations received')
        assert friend(b, owner) is None, 'an invitation is not a friendship'
        b.call('accept', peer=owner, circle='ACQUAINTANCES', outgoing='true')
        c.call('accept', peer=owner, circle='ACQUAINTANCES', outgoing='true')
        wait(lambda: matches(b, a, owner, friend_b) and matches(c, a, owner, friend_c)
             and matches(a, b, friend_b, owner) and matches(a, c, friend_c, owner), 'initial state both ways')
        # Friends also get what acquaintances get (the resolver is monotonic).
        assert {r['category'] for r in friend(b, owner)['records']} == {PROFILE, TRAINING, NOTES}
        assert {r['category'] for r in friend(c, owner)['records']} == {NOTES}
        assert all(r['id'] != 'bid:excluded-history' for r in friend(b, owner)['records']), 'training cutoff'
        wait(lambda: friend(a, friend_b)['confirmed'] and friend(a, friend_c)['confirmed'], 'receivers acknowledged')

        # Ordinary edits travel as deltas; no new acceptance is involved.
        a.call('source_profile', name='Synthetic changed profile')
        a.call('source_training', id='today', attempts='5', date=today.isoformat())
        wait(lambda: matches(b, a, owner, friend_b), 'profile and training mutation')

        # An offline friend misses edits and deletions; its process dies.
        c.child.kill(); c.child.wait(timeout=5)
        for i in range(5):
            a.call('source_note', id='n1', text='Synthetic latest beta ' + str(i))
        a.call('source_note', id='n2', text='')
        c, recovered, _ = participant(sc)
        assert recovered == friend_c
        c.call('automatic', enabled='true')
        wait(lambda: matches(c, a, owner, friend_c), 'offline receiver restart')
        assert len(friend(c, owner)['records']) == 1

        # The owner's process dies and recovers its direction state.
        a.child.kill(); a.child.wait(timeout=5)
        a, recovered, _ = participant(sa)
        assert recovered == owner
        a.call('automatic', enabled='true')
        a.call('source_training', id='today', delete='true')
        wait(lambda: matches(b, a, owner, friend_b), 'owner restart and training deletion')
        assert all(r['category'] != TRAINING for r in friend(b, owner)['records'])

        # Narrowing: a person exception removes a category at the receiver.
        a.call('source_training', id='again', attempts='2', date=today.isoformat())
        wait(lambda: matches(b, a, owner, friend_b) and any(r['category'] == TRAINING for r in friend(b, owner)['records']), 'training back')
        a.call('person_rule', peer=friend_b, category=TRAINING, effect='DENY')
        wait(lambda: matches(b, a, owner, friend_b) and all(r['category'] != TRAINING for r in friend(b, owner)['records']), 'category withdrawal')

        # The one switch: C's own direction off, A's data to C unaffected.
        c.call('outgoing', peer=owner, enabled='false')
        wait(lambda: friend(a, friend_c)['records'] == [] and friend(c, owner)['state'] == 'STOPPED', 'switch off')
        wait(lambda: a.call('storage_matches', marker='Synthetic C private original marker') == dict(app=0, native=0), 'stopped data actually deleted')
        assert matches(c, a, owner, friend_c)

        # Total outage: a queued update is withdrawn by ending before replication.
        for r in relays:
            r.line('offline')
        time.sleep(0.5)
        a.call('source_profile', name='Synthetic pending then revoked')
        wait(lambda: a.call('status')['outbound_pending'] > 0, 'update queued natively during outage')
        assert a.call('storage_matches', marker='Synthetic pending then revoked')['native'] == 0, 'the native queue holds ciphertext only'
        a.call('end_friendship', peer=friend_b)
        for r in relays:
            r.line('online')
        wait(lambda: friend(b, owner)['state'] == 'ENDED' and friend(b, owner)['records'] == [], 'end during outage reaches the friend')
        wait(lambda: b.call('storage_matches', marker='Synthetic pending then revoked') == dict(app=0, native=0), 'withdrawn update never arrived')
        wait(lambda: a.call('storage_matches', marker='Synthetic B private original marker') == dict(app=0, native=0), 'reverse direction deleted')
        assert b.call('canonical_counts') == dict(notes=1, profiles=0), 'canonical data untouched'

        # A new friendship is a new request and a new acceptance.
        a.call('remove', peer=friend_b)
        a.call('request', peer=friend_b, circle='FRIENDS')
        wait(lambda: owner in b.call('friends')['invitations'], 'new request after end')
        b.call('remove', peer=owner)
        b.call('accept', peer=owner, circle='ACQUAINTANCES', outgoing='false')
        wait(lambda: matches(b, a, owner, friend_b) and friend(a, friend_b)['records'] == [], 'new friendship generation')

        # An MLS self-update keeps the friendship and its data flow.
        before = a.call('epoch', peer=friend_b)
        a.call('rotate', peer=friend_b)
        wait(lambda: a.call('epoch', peer=friend_b) > before, 'epoch change')
        a.call('source_profile', name='Synthetic after rotation')
        # B accepted with its own switch off: A is active, B sees itself stopped.
        wait(lambda: matches(b, a, owner, friend_b) and friend(a, friend_b)['state'] == 'ACTIVE'
             and friend(b, owner)['state'] == 'STOPPED', 'data after epoch change')
        print(json.dumps(dict(path='THREE-USERS', independent_processes=3, native_crypto=True, presets_start_empty=True,
            actual_profile_training_note_repositories=True, invitation_then_acceptance=True, manifest_full_delta=True,
            receiver_acks=True, training_cutoff=True, offline_receiver_restart=True, owner_restart=True,
            narrowing=True, per_person_switch=True, outage_withdrawal_by_end=True, both_directions_deleted=True,
            new_friendship_generation=True, epoch_change_keeps_friendship=True, canonical_data_separate=True,
            duplicate_delivery=True, nip77_refused_fallback=True, cruxcoach_relay_unreachable=True, cruxcoach_backend=False)), flush=True)
    finally:
        for p in reversed(resources):
            p.close()
        for d in directories:
            d.cleanup()


if __name__ == '__main__':
    run()
