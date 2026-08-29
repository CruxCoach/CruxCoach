# Wiederverwendbare Release-Test-Fixtures

Fixtures in this directory are public test data. They must never contain an
`nsec`, a private key, credentials, or data copied from a real user account.

`cruxcoach-legacy-014.json` is a sanitized derivative of a backup exported by
CruxCoach 0.1.4. The original legacy field set is retained (modern defaulted
fields are deliberately absent), while the public identity, row UUIDs, names,
timestamps, and comments are deterministic E2E-only values. Its fixed shape is
three sends, one attempt, and two empty custom lists. Two sends carry 5-star and
4-star ratings to exercise the historical import-range regression.

`cruxcoach-missing-catalogue.json` is a synthetic current-schema fixture with
one custom list and one deliberately nonexistent UUID. The device flow first
creates the same list around a real dynamic catalogue climb, then imports this
second membership. That makes the list contain one visible and one unavailable
entry and proves the UI reports the gap instead of silently changing the count.

`cruxcoach-release-upgrade-baseline-v3.json` is the reusable predecessor-
upgrade baseline. It targets the v3 wire shape understood by 0.2.1 and fills
every user-data category: profile, assessment, body stats, workout and climb
logs, training plan, multi-board sends/attempts, board sessions, Favorites,
Ignored, and cross-board/circuit lists. Every board log and list member points
to a real public catalogue climb: two Kilter layouts, MoonBoard 2016, Masters
2019 and Mini 2020, all three Tension layouts, Grasshopper, Decoy, So iLL and
Touchstone. Stable log UUIDs and probe comments make imports idempotent and
make every row easy to identify before and after an in-place release upgrade.
The four open projects use four additional real catalogue climbs without sends,
so a complete import has 16 unique climbs rather than 12.
The fixture has no Nostr identity so it can be reused with a freshly generated
test key.

Validate it after editing with:

```sh
python3 flows/lib/validate_release_upgrade_fixture.py
```

Do not open or stage fixtures through broad Downloads cleanup. The companion
runner only creates and removes its exact `cruxcoach-e2e-*` filename.
