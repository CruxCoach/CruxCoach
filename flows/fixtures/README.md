# Maestro fixtures

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

Do not open or stage fixtures through broad Downloads cleanup. The companion
runner only creates and removes its exact `cruxcoach-e2e-*` filename.
