# Development and production permissions

Hostinger is the owner’s development host. The existing production host keeps
runtime services, the private release runner, signing material and publisher
credentials. Feature builds execute on hosted CI without those credentials.

## Feature contributor approval

The trusted main checkout owns `.github/authorized-feature-identities.json`.
The explicit operator entry preserves the current owner-only feature workflow.
No other developer is authorized by default. To approve a developer, the owner
verifies their Nostr identity, binds that npub to the actual numeric GitHub
account ID and login, and reviews a PR adding an enabled developer entry:

```json
{"github_id": 123, "github_login": "example", "npub": "<verified canonical npub>", "enabled": true}
```

The placeholder above is deliberately invalid. A real NIP-19 checksum and
secp256k1 public key are required. The registry records the owner’s verified
binding; syntax validation does not prove Nostr key ownership. Account IDs
prevent a reassigned login from inheriting publication permission. Reusing an
npub or account binding fails closed. Disable the entry to revoke access.
Both the original push actor and any rerun actor must be authorized. Fork
workflows, non-push events, non-feature branches and failed runs are rejected.
Contributors need branch access in this repository; merely running a workflow
in their own fork does not grant access to the publisher.

The authorization step runs from trusted main, before version reservation or
publication credentials are used. The feature build remains a separate,
credential-free job. Keep registry, workflow and authorizer changes owner-reviewed.
The existing track/package derivation and Fips compatibility override are unchanged.

## Production

Only the owner personally merges CruxCoach main. Such a merge triggers the
private release workflow; its existing tests, release-version idempotency and
signer checks remain in place. The job uses the `release` environment and only
runs for main. Manual dispatch remains available for an exact approved retry.
Amber still needs the owner’s personal confirmation; no unattended signature
or migration of a signing key is introduced. Existing published artifacts stay
available while a new release is pending.

The `release` environment is configured with the owner as its required reviewer
and main as its only deployment branch. Keep these settings in place. Environment
approval complements main branch protection; it does not distinguish a human
from an agent if both can access the same owner API credential. Owner API
credentials and unrestricted production SSH access must therefore stay outside
the development security boundary. Existing Hostinger owner-account SSH keys
must be replaced after active sessions are handed over, not copied to contributors.

Pages and Blossom Sync retain their own deployment mechanisms. This PR changes
neither their live deployment nor their approval policy. Their agents may merge
only after an explicit instruction for that publication and all required checks.
