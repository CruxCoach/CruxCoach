import copy
import unittest
from feature_authorization import authorize, npub_key, CHARSET


def encode_npub(key):
    bits = f'{key:0256b}' + '0000'
    words = [int(bits[i:i+5], 2) for i in range(0, 260, 5)]
    prefix = [ord(c) >> 5 for c in 'npub'] + [0] + [ord(c) & 31 for c in 'npub']
    chk = 1
    for val in prefix + words + [0]*6:
        top = chk >> 25; chk = ((chk & 0x1ffffff) << 5) ^ val
        for i, gen in enumerate([0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]):
            if (top >> i) & 1: chk ^= gen
    chk ^= 1
    return 'npub1' + ''.join(CHARSET[w] for w in words + [(chk >> (5*(5-i))) & 31 for i in range(6)])


class AuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.key = int('79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798', 16)
        self.registry = {'schema': 1, 'repository': 'CruxCoach/CruxCoach',
                         'operator': {'github_id': 1, 'github_login': 'Owner'}, 'developers': []}
        self.event = {'repository': {'full_name': 'CruxCoach/CruxCoach'}, 'workflow_run': {
            'head_repository': {'full_name': 'CruxCoach/CruxCoach'}, 'event': 'push',
            'conclusion': 'success', 'head_branch': 'feat/example', 'head_sha': 'a'*40,
            'actor': {'id': 1, 'login': 'Owner'}, 'triggering_actor': {'id': 1, 'login': 'Owner'}}}

    def developer(self):
        self.registry['developers'] = [{'github_id': 2, 'github_login': 'Developer',
                                       'npub': encode_npub(self.key), 'enabled': True}]
        self.event['workflow_run']['actor'] = {'id': 2, 'login': 'Developer'}
        self.event['workflow_run']['triggering_actor'] = {'id': 2, 'login': 'Developer'}

    def test_owner_preserves_existing_publication_access(self):
        self.assertEqual(authorize(self.registry, self.event), 'feat/example')

    def test_explicit_npub_binding_allows_developer(self):
        self.developer(); self.assertEqual(npub_key(encode_npub(self.key)), self.key)
        self.assertEqual(authorize(self.registry, self.event), 'feat/example')

    def test_reused_login_cannot_replace_account_id(self):
        self.event['workflow_run']['actor']['id'] = 99
        with self.assertRaises(ValueError): authorize(self.registry, self.event)

    def test_owner_rerun_cannot_authorize_unknown_original_actor(self):
        self.event['workflow_run']['actor'] = {'id': 7, 'login': 'Unknown'}
        with self.assertRaises(ValueError): authorize(self.registry, self.event)

    def test_unknown_rerunner_is_rejected(self):
        self.event['workflow_run']['triggering_actor'] = {'id': 7, 'login': 'Unknown'}
        with self.assertRaises(ValueError): authorize(self.registry, self.event)

    def test_revoked_developer_is_rejected(self):
        self.developer(); self.registry['developers'][0]['enabled'] = False
        with self.assertRaises(ValueError): authorize(self.registry, self.event)

    def test_fork_main_failed_and_nonpush_events_are_rejected(self):
        for field, value in [('head_repository', {'full_name': 'Attacker/CruxCoach'}),
                             ('head_branch', 'main'), ('head_branch', 'feat/'),
                             ('conclusion', 'failure'), ('event', 'pull_request'), ('head_sha', 'bad')]:
            event = copy.deepcopy(self.event); event['workflow_run'][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                authorize(self.registry, event)

    def test_invalid_or_duplicate_npub_rejected(self):
        self.developer(); dev = self.registry['developers'][0]
        for value in ['nsec1'+'q'*58, encode_npub(self.key).upper(), encode_npub(self.key)[:-1]+'x']:
            registry = copy.deepcopy(self.registry); registry['developers'][0]['npub'] = value
            with self.subTest(value=value), self.assertRaises(ValueError): authorize(registry, self.event)
        self.registry['developers'].append({**dev, 'github_id': 3, 'github_login': 'Other'})
        with self.assertRaises(ValueError): authorize(self.registry, self.event)


if __name__ == '__main__': unittest.main()
