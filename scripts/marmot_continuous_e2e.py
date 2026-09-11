#!/usr/bin/env python3
"""Real MDK/JNI in three independent synthetic JVMs; two hostile local relays.

No backend. CruxCoach's canonical relay is fault-mapped to a closed loopback
port by the test-only native harness. Keys only cross stdin, never logs/argv.
The fixture delivers every event twice and orders by event ID, not send order.
"""
import datetime
import json
import secrets
import tempfile
import time
from marmot_network_e2e import ROOT, Process
import sys

PROFILE = 'PROFILE_AND_GOALS'
TRAINING = 'TRAINING_HISTORY'
NOTES = 'PRIVATE_NOTES'


def run(role):
    resources, directories = [], []
    try:
        relays = [Process([str(ROOT / 'native/marmot/target/debug/examples/local_relay'), '--loopback-test-relay']) for _ in range(2)]
        resources.extend(relays)
        endpoints = [r.read() for r in relays]
        assert all(e.startswith('ws://127.0.0.1:') for e in endpoints)
        def participant(kind, retained=None):
            if retained is None:
                directory = tempfile.TemporaryDirectory(prefix='cc-continuous-process-')
                directories.append(directory)
                retained = dict(directory=directory.name, database_key=secrets.token_hex(32))
            args = [sys.executable, str(ROOT / 'scripts/marmot_endpoint.py'), '--prepared', '--restart-harness', '--unreachable-cruxcoach', '--role', kind]
            for endpoint in endpoints:
                args += ['--relay', endpoint]
            p = Process(args); resources.append(p)
            p.line(json.dumps(retained))
            account = json.loads(p.read())['account']
            return p, account, retained
        a, owner, sa = participant(role)
        b, friend_b, sb = participant('USER')
        c, friend_c, sc = participant('USER')
        since = (datetime.date.today() - datetime.timedelta(days=30)).isoformat()
        today = datetime.date.today().isoformat()
        def wait(predicate, phase, seconds=90):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                if predicate():
                    print(json.dumps(dict(path=role+'-TWO-USERS', phase=phase, passed=True)), flush=True)
                    return
                time.sleep(.5)
            raise AssertionError('continuous phase timeout: ' + phase)
        def view(p, grant):
            return next((v for v in p.call('continuous_status') if v['id'] == grant), None)
        def matches(p, grant, categories):
            received = view(p, grant)
            return received and received['state'] == 'ACTIVE' and received['records'] == a.call('source_hashes', categories=categories, since=since)
        def confirmed(grant):
            v = view(a, grant)
            return v and v['state'] == 'ACTIVE' and v['confirmed'] and not v['pending']
        for p in (a,b,c): p.call('bootstrap')
        a.call('source_profile', name='Synthetic initial profile')
        a.call('source_note', id='n1', text='Synthetic initial beta')
        a.call('source_note', id='n2', text='Synthetic note to delete')
        a.call('source_training', id='today', attempts='3', date=today)
        a.call('source_training', id='excluded-history', attempts='8', date='2000-01-01')
        b.call('source_note', id='b-own', text='Synthetic B private original marker')
        c.call('source_profile', name='Synthetic C private original marker')
        for p in (a,b,c): p.call('automatic', enabled='true')
        ab = a.call('offer_continuous', peer=friend_b, role='USER', categories=[PROFILE,TRAINING], since=since)
        ac = a.call('offer_continuous', peer=friend_c, role='USER', categories=[NOTES], since=since)
        for p, grant in ((b,ab),(c,ac)):
            wait(lambda: view(p, grant) is not None, 'new invitation')
            assert view(p, grant)['state'] == 'INVITED' and view(p, grant)['records'] == []
            assert p.call('accept_continuous', id=grant, role=role, categories=[NOTES] if p is b else [PROFILE], since=since)
        wait(lambda: matches(b,ab,[PROFILE,TRAINING]) and matches(c,ac,[NOTES]) and confirmed(ab) and confirmed(ac), 'initial current state')
        assert view(b,ab)['count'] == 2 and view(c,ac)['count'] == 2
        # No manual sync/snapshot operation after this point: an independent
        # automatic runner drives the same exchanges while repositories mutate.
        a.call('source_profile', name='Synthetic changed profile')
        a.call('source_training', id='today', attempts='5', date=today)
        wait(lambda: matches(b,ab,[PROFILE,TRAINING]) and confirmed(ab), 'profile and training mutation')
        assert matches(c,ac,[NOTES])
        # Offline friend misses edits/deletions. Abrupt process death destroys
        # signer/vault/engine objects; private identity recovers from SQLCipher.
        c.child.kill(); c.child.wait(timeout=5)
        for i in range(5): a.call('source_note', id='n1', text='Synthetic latest beta ' + str(i))
        a.call('source_note', id='n2', text='')
        c, recovered, _ = participant('USER', sc); assert recovered == friend_c
        c.call('automatic', enabled='true')
        wait(lambda: matches(c,ac,[NOTES]) and confirmed(ac), 'offline recipient restart')
        assert view(c,ac)['count'] == 1
        # Owner also recovers the exact durable transfer/recipient-ack baseline.
        a.child.kill(); a.child.wait(timeout=5)
        a, recovered, _ = participant(role, sa); assert recovered == owner
        a.call('automatic', enabled='true')
        a.call('source_training', id='today', delete=True)
        wait(lambda: matches(b,ab,[PROFILE,TRAINING]) and confirmed(ab), 'owner restart and training tombstone')
        assert view(b,ab)['count'] == 1
        # Same authenticated friendship; only the owner changes outgoing scope.
        expanded = a.call('offer_continuous', peer=friend_b, role='USER', categories=[PROFILE,TRAINING,NOTES], since=since)
        assert expanded == ab
        wait(lambda: matches(b,ab,[PROFILE,TRAINING,NOTES]) and confirmed(ab), 'owner expansion without another acceptance')
        assert a.call('deny_category', peer=friend_b, category=NOTES)
        wait(lambda: matches(b,ab,[PROFILE,TRAINING]) and confirmed(ab), 'category withdrawal')
        wait(lambda: b.call('storage_matches',marker='Synthetic latest beta') == dict(app=0,native=0), 'actual withdrawn category deletion')
        assert a.call('baseline',circle='ACQUAINTANCES',category=PROFILE)
        assert a.call('downgrade',peer=friend_b)
        wait(lambda: matches(b,ab,[PROFILE]) and confirmed(ab), 'downgrade retains only allowed circle data')
        ba=next(v['id'] for v in b.call('continuous_status') if v['friendship']==ab and v['outgoing'])
        ca=next(v['id'] for v in c.call('continuous_status') if v['friendship']==ac and v['outgoing'])
        wait(lambda: view(a,ba)['records']==b.call('source_hashes',categories=[NOTES],since=since) and view(a,ca)['records']==c.call('source_hashes',categories=[PROFILE],since=since), 'reverse own selections')
        assert b.call('resync_continuous',id=ab)
        wait(lambda: matches(b,ab,[PROFILE]) and confirmed(ab), 'explicit resume current selection')
        # Both relays unavailable, plus permanently unavailable CruxCoach.
        for r in relays: r.line('offline')
        time.sleep(.2)
        a.call('source_note', id='n1', text='Synthetic pending then revoked')
        assert view(a,ac)['pending']
        wait(lambda: a.call('storage_matches',marker='Synthetic pending then revoked')['native'] > 0, 'native encrypted handoff really queued during outage')
        assert a.call('end_continuous', id=ac)
        for r in relays: r.line('online')
        wait(lambda: view(c,ac)['state'] == 'ENDED' and view(c,ac)['records'] == [], 'revoke queued update during outage')
        assert all(v['records'] == [] for v in c.call('continuous_status'))
        wait(lambda: view(a,ac)['cleanup_confirmed'], 'real peer cleanup acknowledgement')
        wait(lambda: a.call('storage_matches',marker='Synthetic C private original marker') == dict(app=0,native=0), 'reverse replica actual removal')
        wait(lambda: c.call('storage_matches',marker='Synthetic latest beta') == dict(app=0,native=0), 'native inbox/outbox actual removal')
        c.child.kill(); c.child.wait(timeout=5)
        c, recovered, _ = participant('USER', sc); assert recovered == friend_c
        c.call('automatic',enabled='true')
        assert c.call('storage_matches',marker='Synthetic latest beta') == dict(app=0,native=0)
        assert c.call('resync_continuous',id=ac) is False
        assert c.call('canonical_counts') == dict(notes=0,profiles=1)
        assert b.call('end_continuous',id=ab)
        wait(lambda: view(a,ab)['state']=='ENDED' and view(a,ba)['records']==[], 'recipient-originated end removes both directions')
        wait(lambda: a.call('storage_matches',marker='Synthetic B private original marker') == dict(app=0,native=0), 'recipient end actual sender deletion')
        assert b.call('canonical_counts') == dict(notes=1,profiles=0)
        fresh=a.call('offer_continuous',peer=friend_b,role='USER',categories=[PROFILE],since=since)
        assert fresh != ab
        wait(lambda: view(b,fresh) is not None, 'new generation requests new friendship')
        assert view(b,fresh)['records']==[]
        assert b.call('accept_continuous',id=fresh,role=role)
        wait(lambda: matches(b,fresh,[PROFILE]) and confirmed(fresh), 'new friendship generation')
        expanded=fresh
        assert 'wss://blossom.cruxcoach.org/nostr' in a.call('status')['relays']
        assert a.call('rotate_transport', peer=friend_b)
        wait(lambda: view(b,expanded)['state'] in ('RECONNECT','CONFLICT','ENDED') and not view(b,expanded)['records'], 'actual MLS epoch change')
        a.call('source_profile', name='Synthetic after rotation')
        assert not view(b,expanded)['records'], 'old ongoing consent cannot authorize a new epoch'
        print(json.dumps(dict(path=role+'-TWO-USERS', independent_processes=3, native_crypto=True,
            actual_profile_training_note_repositories=True, one_friendship_confirmation=True,
            automatic_mutations=True, scoped_fanout=True, initial_and_delta=True, deletion=True,
            duplicate_and_reordered_relays=True, offline_recipient_restart=True, owner_restart=True,
            expansion_without_recipient_action=True, new_friendship_generation=True, bidirectional_scopes=True, bidirectional_cleanup=True, actual_storage_deletion=True, downgrade=True, narrowing=True, native_epoch_retirement=True, pending_revoke=True, canonical_data_separate=True,
            cruxcoach_relay_unreachable=True, cruxcoach_backend=False)), flush=True)
    finally:
        for p in reversed(resources): p.close()
        for d in directories: d.cleanup()


def server_receives():
    resources = []
    try:
        relay = Process([str(ROOT / 'native/marmot/target/debug/examples/local_relay'), '--loopback-test-relay'])
        resources.append(relay); url = relay.read(); assert url.startswith('ws://127.0.0.1:')
        def participant(role):
            p = Process([sys.executable, str(ROOT / 'scripts/marmot_endpoint.py'), '--prepared', '--unreachable-cruxcoach', '--role', role, '--relay', url])
            resources.append(p)
            return p, json.loads(p.read())['account']
        a, owner = participant('USER'); b, server = participant('SERVER')
        for p in (a,b): p.call('bootstrap')
        a.call('pin',peer=server,role='SERVER'); a.call('invite',peer=server)
        b.call('sync'); b.call('accept_invitation',peer=owner)
        for _ in range(2):
            for p in (a,b): p.call('sync')
        a.call('source_note',id='server-input',text='Synthetic explicitly selected endpoint input')
        for p in (a,b): p.call('automatic',enabled='true')
        since=datetime.date.today().isoformat()
        grant=a.call('offer_continuous',peer=server,role='SERVER',categories=[NOTES],since=since)
        def state(): return next((v for v in b.call('continuous_status') if v['id']==grant),None)
        def wait(condition):
            until=time.monotonic()+90
            while time.monotonic()<until:
                if condition(): return
                time.sleep(.5)
            raise AssertionError('explicit server recipient timeout')
        wait(lambda: state() is not None)
        assert state()['state']=='INVITED' and not state()['records']
        assert b.call('accept_continuous',id=grant,role='USER')
        wait(lambda: state()['state']=='ACTIVE' and state()['records']==a.call('source_hashes',categories=[NOTES],since=since))
        a.call('source_note',id='server-input',text='Synthetic automatically updated endpoint input')
        wait(lambda: state()['records']==a.call('source_hashes',categories=[NOTES],since=since))
        assert b.call('canonical_counts')==dict(notes=0,profiles=0)
        relay.line('offline')
        a.call('source_note',id='server-input',text='Synthetic queued server input to withdraw')
        wait(lambda: a.call('storage_matches',marker='Synthetic queued server input to withdraw')['native'] > 0)
        assert a.call('end_continuous',id=grant)
        relay.line('online')
        wait(lambda: state()['state']=='ENDED' and not state()['records'])
        print(json.dumps(dict(path='USER-SERVER',independent_processes=2,native_crypto=True,
            explicit_friendship=True,automatic_real_source_update=True,revoked_access_denied=True,native_queued_revoke=True,
            canonical_server_data_separate=True,cruxcoach_relay_unreachable=True,cruxcoach_backend=False)),flush=True)
    finally:
        for p in reversed(resources): p.close()


if __name__ == '__main__':
    if '--server-receiver-only' not in sys.argv:
        run('USER')
        run('SERVER')
    server_receives()
