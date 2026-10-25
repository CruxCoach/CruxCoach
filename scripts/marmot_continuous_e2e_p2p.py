#!/usr/bin/env python3
"""Spike M0: the v2 sync semantics over the direct Olm channel, three JVMs.

Port of marmot_continuous_e2e.py: the same production SharingService,
SharingSync and SharingStore, the same phases, but the native Olm engine and
direct loopback HTTP between the participants instead of MLS over relays. A
hostile proxy in front of every participant drops, duplicates, delays,
corrupts and replays exchange containers; "total outage" means no participant
can reach another. Invitations travel over the LAN path (both apps open).

Relay-specific phases are replaced: the MLS epoch change becomes a session
rebuild after messages were lost beyond Olm's window (unwedge). Keys cross
stdin only; no payload is printed.
"""
import datetime
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

PROFILE = 'PROFILE_AND_GOALS'
TRAINING = 'TRAINING_HISTORY'
NOTES = 'PRIVATE_NOTES'
SEED = int(os.environ.get('CC_P2P_SEED', secrets.randbelow(1 << 30)))


def run():
    resources, directories, proxies = [], [], []
    timings = {}
    try:
        names = ['a', 'b', 'c']
        proxy = {n: HostileProxy(SEED + i) for i, n in enumerate(names)}
        proxies.extend(proxy.values())

        def participant(name, retained=None):
            if retained is None:
                directory = tempfile.TemporaryDirectory(prefix='cc-p2p-continuous-')
                directories.append(directory)
                retained = dict(directory=directory.name, database_key=secrets.token_hex(32))
            args = [sys.executable, str(ROOT / 'scripts/marmot_endpoint.py'), '--prepared', '--restart-harness', '--p2p', '--role', 'USER']
            p = Process(args)
            resources.append(p)
            p.line(json.dumps(retained))
            account = json.loads(p.read())['account']
            p.call('bootstrap')
            proxy[name].set_target(p.call('port'))
            p.call('discover', urls=[proxy[o].url for o in names if o != name])
            return p, account, retained

        a, owner, sa = participant('a')
        b, friend_b, sb = participant('b')
        c, friend_c, sc = participant('c')
        today = datetime.date.today()
        started = time.monotonic()

        def wait(predicate, phase, seconds=120, describe=None):
            begin = time.monotonic()
            deadline = begin + seconds
            while time.monotonic() < deadline:
                if predicate():
                    timings[phase] = round(time.monotonic() - begin, 1)
                    print(json.dumps(dict(path='P2P-THREE', phase=phase, passed=True, seconds=timings[phase])), flush=True)
                    return
                time.sleep(0.5)
            if describe is not None:
                print(json.dumps(dict(path='P2P-THREE', phase=phase, state=describe())), flush=True)
            raise AssertionError('p2p continuous phase timeout: ' + phase)

        def friend(p, peer):
            return next((f for f in p.call('friends')['friends'] if f['peer'] == peer), None)

        def summary(p, peer):
            f = friend(p, peer)
            return None if f is None else dict(state=f['state'], confirmed=f['confirmed'],
                                               ids=sorted(r['id'] + '@' + r['hash'][:8] for r in f['records']))

        def matches(receiver, owner_process, owner_account, receiver_account):
            f = friend(receiver, owner_account)
            return f is not None and f['records'] == owner_process.call('source_hashes', peer=receiver_account)

        a.call('source_profile', name='Synthetic initial profile')
        a.call('source_note', id='n1', text='Synthetic initial beta')
        a.call('source_note', id='n2', text='Synthetic note to delete')
        a.call('source_training', id='today', attempts='3', date=today.isoformat())
        a.call('source_training', id='excluded-history', attempts='8', date='2000-01-01')
        b.call('source_note', id='b-own', text='Synthetic B private original marker')
        c.call('source_profile', name='Synthetic C private original marker')
        defaults = {'FRIENDS': {'categories': [PROFILE, TRAINING], 'days': 30},
                    'ACQUAINTANCES': {'categories': [PROFILE], 'days': 30}}
        assert all(p.call('presets') == defaults for p in (a, b, c)), 'default presets on a fresh install'
        a.call('preset', circle='FRIENDS', categories=[PROFILE, TRAINING], days=30)
        a.call('preset', circle='ACQUAINTANCES', categories=[NOTES], days=30)
        b.call('preset', circle='ACQUAINTANCES', categories=[NOTES])
        c.call('preset', circle='ACQUAINTANCES', categories=[PROFILE])
        for p in (a, b, c):
            p.call('automatic', enabled='true')

        # B and C show a contact code, A scans both: each sealed invitation
        # travels over the LAN to exactly the device that showed the code.
        code_b, code_c = b.call('contact_code')['payload'], c.call('contact_code')['payload']
        a.call('request', peer=friend_b, circle='FRIENDS')
        a.call('import_contact', payload=code_b)
        a.call('request', peer=friend_c, circle='ACQUAINTANCES')
        a.call('import_contact', payload=code_c)
        wait(lambda: owner in b.call('friends')['invitations'] and owner in c.call('friends')['invitations'], 'invitations received')
        assert friend(b, owner) is None, 'an invitation is not a friendship'
        b.call('accept', peer=owner, circle='ACQUAINTANCES', outgoing='true')
        c.call('accept', peer=owner, circle='ACQUAINTANCES', outgoing='true')
        wait(lambda: matches(b, a, owner, friend_b) and matches(c, a, owner, friend_c)
             and matches(a, b, friend_b, owner) and matches(a, c, friend_c, owner), 'initial state both ways',
             describe=lambda: dict(b_sees_a=summary(b, owner), a_sees_b=summary(a, friend_b)))
        assert {r['category'] for r in friend(b, owner)['records']} == {PROFILE, TRAINING, NOTES}
        assert {r['category'] for r in friend(c, owner)['records']} == {NOTES}
        assert all(r['id'] != 'bid:excluded-history' for r in friend(b, owner)['records']), 'training cutoff'
        wait(lambda: friend(a, friend_b)['confirmed'] and friend(a, friend_c)['confirmed'], 'receivers acknowledged')

        a.call('source_profile', name='Synthetic changed profile')
        a.call('source_training', id='today', attempts='5', date=today.isoformat())
        wait(lambda: matches(b, a, owner, friend_b), 'profile and training mutation')

        # An absent friend misses edits and deletions; its process dies.
        c.child.kill(); c.child.wait(timeout=5)
        for i in range(5):
            a.call('source_note', id='n1', text='Synthetic latest beta ' + str(i))
        a.call('source_note', id='n2', text='')
        c, recovered, _ = participant('c', sc)
        assert recovered == friend_c
        c.call('automatic', enabled='true')
        wait(lambda: matches(c, a, owner, friend_c), 'offline receiver restart')
        assert len(friend(c, owner)['records']) == 1

        a.child.kill(); a.child.wait(timeout=5)
        a, recovered, _ = participant('a', sa)
        assert recovered == owner
        a.call('automatic', enabled='true')
        a.call('source_training', id='today', delete='true')
        wait(lambda: matches(b, a, owner, friend_b), 'owner restart and training deletion')
        assert all(r['category'] != TRAINING for r in friend(b, owner)['records'])

        a.call('source_training', id='again', attempts='2', date=today.isoformat())
        wait(lambda: matches(b, a, owner, friend_b) and any(r['category'] == TRAINING for r in friend(b, owner)['records']), 'training back')
        a.call('person_rule', peer=friend_b, category=TRAINING, effect='DENY')
        wait(lambda: matches(b, a, owner, friend_b) and all(r['category'] != TRAINING for r in friend(b, owner)['records']), 'category withdrawal')

        c.call('outgoing', peer=owner, enabled='false')
        wait(lambda: friend(a, friend_c)['records'] == [] and friend(c, owner)['state'] == 'STOPPED', 'switch off')
        wait(lambda: a.call('storage_matches', marker='Synthetic C private original marker') == dict(app=0, native=0), 'stopped data actually deleted')
        assert matches(c, a, owner, friend_c)

        # Total outage (nobody in reach): a queued update is withdrawn by ending.
        for p in proxy.values():
            p.offline = True
        time.sleep(0.5)
        a.call('source_profile', name='Synthetic pending then revoked')
        wait(lambda: a.call('status')['queued'] > 0, 'update queued natively during outage')
        assert a.call('storage_matches', marker='Synthetic pending then revoked')['native'] == 0, 'the native queue holds ciphertext only'
        a.call('end_friendship', peer=friend_b)
        for p in proxy.values():
            p.offline = False
        wait(lambda: friend(b, owner)['state'] == 'ENDED' and friend(b, owner)['records'] == [], 'end during outage reaches the friend')
        wait(lambda: b.call('storage_matches', marker='Synthetic pending then revoked') == dict(app=0, native=0), 'withdrawn update never arrived')
        wait(lambda: a.call('storage_matches', marker='Synthetic B private original marker') == dict(app=0, native=0), 'reverse direction deleted')
        assert b.call('canonical_counts') == dict(notes=1, profiles=0), 'canonical data untouched'

        a.call('remove', peer=friend_b)
        a.call('request', peer=friend_b, circle='FRIENDS')
        a.call('import_contact', payload=b.call('contact_code')['payload'])
        wait(lambda: owner in b.call('friends')['invitations'], 'new request after end')
        b.call('remove', peer=owner)
        b.call('accept', peer=owner, circle='ACQUAINTANCES', outgoing='false')
        wait(lambda: matches(b, a, owner, friend_b) and friend(a, friend_b)['records'] == [], 'new friendship generation')

        # Unwedge: messages lost beyond Olm's window; the session is rebuilt
        # and the sync layer converges on a new generation.
        a.call('burn', peer=friend_b, count='2100')
        a.call('source_profile', name='Synthetic after rebuild')
        wait(lambda: matches(b, a, owner, friend_b) and friend(a, friend_b)['state'] == 'ACTIVE'
             and friend(b, owner)['state'] == 'STOPPED', 'data after session rebuild',
             describe=lambda: dict(b_sees_a=summary(b, owner), a_sends=sorted(r['id'] + '@' + r['hash'][:8] for r in a.call('source_hashes', peer=friend_b))))
        ops = {n: p.call('signer_ops') for n, p in (('a', a), ('b', b), ('c', c))}
        assert all(v <= 1 for v in ops.values()), ops
        faults = {}
        for p in proxy.values():
            for k, v in p.stats.items():
                faults[k] = faults.get(k, 0) + v
        print(json.dumps(dict(path='P2P-THREE', seed=SEED, independent_processes=3, native_crypto='olm/vodozemac',
            relay=False, blossom=False, default_presets=True, lan_invitation_then_acceptance=True, manifest_full_delta=True,
            receiver_acks=True, training_cutoff=True, offline_receiver_restart=True, owner_restart=True,
            narrowing=True, per_person_switch=True, outage_withdrawal_by_end=True, both_directions_deleted=True,
            new_friendship_generation=True, unwedge_keeps_friendship=True, canonical_data_separate=True,
            signer_ops=ops, faults=faults, total_s=round(time.monotonic() - started, 1), phases=timings)), flush=True)
    finally:
        for p in reversed(resources):
            p.close()
        for p in proxies:
            p.close()
        for d in directories:
            d.cleanup()


if __name__ == '__main__':
    run()
