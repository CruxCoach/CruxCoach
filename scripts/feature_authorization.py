"""Authorize feature publication using a reviewed npub/account registry on main.

This validates the owner's recorded identity binding; it does not establish
Nostr key ownership. The owner verifies that before approving a registry change.
No branch code, network request, private key or publication token is used here.
"""
import argparse
import json
import re
from pathlib import Path

CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l'


def npub_key(value):
    if not isinstance(value, str) or len(value) != 63 or not value.startswith('npub1'):
        raise ValueError('npub must be a canonical lowercase NIP-19 public key')
    try:
        words = [CHARSET.index(c) for c in value[5:]]
    except ValueError as exc:
        raise ValueError('invalid npub character') from exc
    checksum = 1
    expanded = [ord(c) >> 5 for c in 'npub'] + [0] + [ord(c) & 31 for c in 'npub']
    for word in expanded + words:
        top = checksum >> 25
        checksum = ((checksum & 0x1ffffff) << 5) ^ word
        for bit, generator in enumerate([0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]):
            if (top >> bit) & 1:
                checksum ^= generator
    if checksum != 1:
        raise ValueError('invalid npub checksum')
    bits = ''.join(f'{word:05b}' for word in words[:-6])
    if len(bits) != 260 or bits[256:] != '0000':
        raise ValueError('invalid npub padding')
    key = int(bits[:256], 2)
    prime = 2**256 - 2**32 - 977
    if key >= prime or pow((key**3 + 7) % prime, (prime - 1) // 2, prime) != 1:
        raise ValueError('npub is not a secp256k1 public key')
    return key


def account(entry):
    ident = entry.get('github_id')
    login = entry.get('github_login')
    if type(ident) is not int or ident <= 0 or not isinstance(login, str):
        raise ValueError('invalid GitHub account binding')
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?', login):
        raise ValueError('invalid GitHub login')
    return ident, login.casefold()


def authorized_accounts(registry):
    if registry.get('schema') != 1:
        raise ValueError('unsupported identity registry')
    allowed = {account(registry['operator'])}
    ids = {next(iter(allowed))[0]}
    logins = {next(iter(allowed))[1]}
    npubs = set()
    if not isinstance(registry.get('developers'), list):
        raise ValueError('developers must be a list')
    for developer in registry['developers']:
        binding = account(developer)
        key = npub_key(developer.get('npub'))
        if binding[0] in ids or binding[1] in logins or key in npubs:
            raise ValueError('duplicate account or npub binding')
        if type(developer.get('enabled')) is not bool:
            raise ValueError('enabled must be explicit')
        ids.add(binding[0]); logins.add(binding[1]); npubs.add(key)
        if developer['enabled']:
            allowed.add(binding)
    return allowed


def authorize(registry, event):
    allowed = authorized_accounts(registry)
    run = event['workflow_run']
    expected = registry['repository']
    if event['repository']['full_name'] != expected or run['head_repository']['full_name'] != expected:
        raise ValueError('feature must originate in the configured repository')
    if run['conclusion'] != 'success' or run['event'] != 'push':
        raise ValueError('only successful branch push workflows may publish')
    branch = run['head_branch']
    if not isinstance(branch, str) or not branch.startswith('feat/') or len(branch) <= 5:
        raise ValueError('only feature branches may publish')
    if not re.fullmatch(r'[0-9a-f]{40}', run['head_sha']):
        raise ValueError('invalid source commit')
    for role in ['actor', 'triggering_actor']:
        actor = run[role]
        binding = account({'github_id': actor['id'], 'github_login': actor['login']})
        if binding not in allowed:
            raise ValueError('both the original actor and rerun actor must be authorized')
    return branch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--registry', type=Path, required=True)
    parser.add_argument('--event', type=Path, required=True)
    args = parser.parse_args()
    try:
        authorize(json.loads(args.registry.read_text()), json.loads(args.event.read_text()))
    except (ValueError, KeyError, TypeError, OSError) as exc:
        raise SystemExit('Feature authorization rejected: ' + str(exc)) from exc
    print('Feature publication identity authorized.')


if __name__ == '__main__':
    main()
