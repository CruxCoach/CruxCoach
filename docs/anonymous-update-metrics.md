# Anonymous verified-update counter

CruxCoach 0.2.2 can attempt to contribute one coarse aggregate signal after its
in-app updater has downloaded and cryptographically verified an update APK.
This is a distribution-health counter, not user analytics: it cannot count
people, devices, installations, sessions, update checks, or successful Android
package installations.

## Build and user choice

- `ANONYMOUS_METRICS_ENDPOINT` is empty by default. An ordinary clone or fork
  therefore neither shows the setting nor sends a request.
- The upstream Forgejo release workflow injects
  `https://stats.cruxcoach.org/v1/app-event` only when the repository context is
  exactly `CruxCoach/CruxCoach`.
- In an endpoint-enabled build, **Settings → Updates** discloses the counter and
  provides a persistent toggle. It is enabled by default and can be disabled at
  any time. While disabled, no counter request is attempted.
- A fork can configure only an HTTPS endpoint it operates or trusts. Doing so
  also makes that fork responsible for an accurate disclosure, lawful operation,
  and a backend whose retention matches its claims.

## Closed client contract

A request is eligible only after `IntegrityVerifier.Result.Ok`, which means the
downloaded bytes matched the release SHA-256 and the APK signing certificate
matched the updater pin. Checking for an update, reading release metadata,
starting a download, a failed verification, and PackageInstaller outcomes do
not create an event.

The complete JSON body is:

```json
{"metric":"app_update_verified","version":"0.2.2","source":"codeberg"}
```

The target `version` must be a stable three-part version. `source` is restricted
to `codeberg` or `zapstore` and is derived only from the configured Codeberg
repository release path or Zapstore CDN origin. Unknown, malformed, or insecure
download URLs are not counted. The body has no current app version, user,
installation, device, advertising, account, Nostr, session, or event ID.

The counter owns a separate HTTP client; it does not inherit application
interceptors. It uses a constant `CruxCoach-Metrics/1` User-Agent, no cookies or
authenticator, no redirects, a short timeout, and no transport or application
retry. A failure is logged only locally in generic form and can never change
update checking, verification, readiness, or installation.

## Delivery semantics

Before dispatch, the app atomically stores only the public target version in its
local updater preferences. A second completion for that target version is then
suppressed, including after process restart. This is an **at-most-one dispatch
attempt per target version per app data store**, not an exactly-once guarantee.

Exactly-once delivery would require a linkable event/installation identifier or
a retry idempotency key. CruxCoach deliberately creates neither. Persisting the
attempt before the network call and never retrying prevents duplicate delivery
after an ambiguous failure, at the cost of occasionally undercounting. Endpoint
outages, blocking, offline devices, app-data resets, bots, and forged requests
make every total approximate.

The event means only that the in-app updater obtained a complete APK which
passed both verification gates. It does not prove Android installed it.
Zapstore-managed store updates bypass the in-app updater and are not included.

## Backend and retention contract

The upstream endpoint is implemented by the separate `cruxcoach-dlstats`
collector. This versioned document defines the client/backend release contract;
the public [website privacy notice](https://cruxcoach.org/privacy.html) describes
the controller, purpose, legal basis, hosting, recipients, choices, and rights.

For an accepted app event, the collector immediately increments one SQLite
bucket containing only UTC day, target version, source, and count. There is no
raw-event table. The collector and dedicated reverse proxy have request/access
logging disabled and do not retain IP addresses, request headers, User-Agent,
referrer, or exact timestamps. Network infrastructure necessarily processes an
IP address transiently to deliver the request, but the analytics stack does not
copy, hash, log, or retain it. Production is hosted at Hetzner's selected
Helsinki, Finland location without an analytics CDN or WAF.

Daily aggregates are exported to `metrics_anonymous.csv` in the dlstats
repository and retained without a fixed deletion period, including repository
history. These durable rows contain no identifier and cannot link two requests
or isolate a person's event.

The upstream endpoint must be removed from release injection before shipping if
any of these invariants no longer holds: closed payload schema, immediate daily
aggregation, no raw events or identifiers, disabled request/access logs, no IP
hashing or retention, and accurate public disclosure of aggregate retention.

## Regression coverage

The Android unit tests pin the following behavior:

- exact payload and absence of cookie/referrer headers;
- empty, malformed, credentialed, fragmented, and non-HTTPS endpoint rejection;
- strict source/version allowlists;
- no redirect following or server-error retry;
- persistent opt-out and at-most-one target-version guard;
- no duplicate dispatch for repeated completion callbacks;
- the actual Zapstore source after a failed Codeberg payload fallback; and
- metrics exceptions never blocking a verified update from becoming ready.
