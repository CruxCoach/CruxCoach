# FEAT-058 — Cross-client conformance matrix

Every acceptance criterion in [`FEAT-058-competitions.md`](FEAT-058-competitions.md)
§10, mapped to the automation that proves it. A criterion with no automation is
listed as device-owed or manual, and says why.

**Repositories**
- **app** — `CruxCoach`, branch `feat/0.2.3-competitions`
- **web** — `cruxcoach-pages`, branch `feat/competitions`

**The gates**

```bash
# web — 334 tests
cd cruxcoach-pages && scripts/check

# app — the shared protocol, reducer, scoring, NIP-19 and canonical JSON
cd CruxCoach && ANDROID_HOME=/home/myuser/android-sdk ./gradlew :shared:testDebugUnitTest

# app — link parsing, string parity, and the whole existing suite
cd CruxCoach && ANDROID_HOME=/home/myuser/android-sdk ./gradlew :androidApp:testDebugUnitTest

# app — lint and a debug APK
cd CruxCoach && ANDROID_HOME=/home/myuser/android-sdk ./gradlew :androidApp:lintDebug :androidApp:assembleDebug
```

`ANDROID_HOME` is mandatory: the ambient `/opt/android-sdk` has no NDK and is
not writable, so `:androidApp` fails at configuration without it.

---

## The shared fixture set

The two clients are held to one contract by
`competitions/fixtures/` (web) and its byte-identical copy at
`shared/src/commonTest/resources/competition/` (app). Both pin the manifest
digest, so regenerating on one side without copying to the other fails on the
side that was not updated.

| Stream | Entries | What it pins |
|---|---|---|
| `happy-sync` | 40 | A clean run: publish → register → accept → check in → seed → start → two climbs → finish |
| `defer-and-timeout` | 27 | A granted deferral, a refused second one, and a timeout that costs an attempt |
| `paid-unique-async` | 18 | Participant-chosen climbs with enforced uniqueness, an expired payment, a no-show |
| `fork-and-correction` | 7 | Two entries at one position, plus an override and a correction with mandatory reasons |
| `chain-break` | 3 + 1 withheld | A gap: reduction must stop and say so |
| `rejections` | 32 | Seventeen distinct rejection paths |
| `rejections-paid` | 36 | The fifteen an earlier check masks in the stream above |

Vectors: `vectors/protocol.json` (canonical JSON, d-tags, NIP-19 address,
event id and a tampered event, relay-URL rules), `vectors/zap.json` (locally
signed Lightning fixtures — no invoice, no network), `qr-reference.json`
(an independent encoder's QR function areas).

---

## The matrix

| AC | Where it is proved | Test |
|---|---|---|
| **AC-1** state hash agreement | web + app | `tools/competition-reduce.test.mjs` → *every fixture stream reduces to its recorded state hash*; `CompetitionConformanceTest` → same name |
| **AC-2** order and duplicates | web + app | *reduction does not depend on the order events arrive in*; *duplicate delivery of every event changes nothing* (both suites) |
| **AC-3** gap stops reduction | web + app | *a withheld entry stops reduction at the gap instead of skipping ahead*; *supplying the withheld entry completes the chain*; `competition-e2e.test.mjs` → *the authority refuses to write on top of a gap* |
| **AC-4** fork | web + app | *a fork is detected, and every client picks the same branch*; `AuthorityWriter.publishResults` refuses while forked (`authority.mjs`) |
| **AC-5** authority only | web + app | *a log entry signed by someone other than the authority is refused*; `competition-e2e.test.mjs` → *someone who is not the authority cannot write to the log* (asserts the relay stores it and the client refuses it) |
| **AC-6** a request is not a registration | web | `competition-e2e.test.mjs` → asserts the participant list is empty after two registration requests |
| **AC-7** capacity | web + app | fixture `rejections` produces `capacity_full`; both suites assert the closed code set |
| **AC-8** unique claim | web + app | *a second claim on an already-claimed climb is rejected by the reducer* (both suites) |
| **AC-9** deferral moves back exactly | web + app | *a granted deferral moves back exactly defer_slots and buys no attempts*; stream `defer-and-timeout` |
| **AC-10** deferral refused | web + app | *a second consecutive deferral is refused, with a stable code*; `defer_budget_exhausted` and `defer_consecutive_limit` both covered |
| **AC-11** timeout costs an attempt | web | *an expired turn consumes an attempt* |
| **AC-12** payment gates the turn | web | `competition-e2e.test.mjs` → *a paid competition keeps an unpaid entrant out of the running order*; stream `rejections-paid` produces `not_eligible` |
| **AC-13** every rejection code | web + app | *every rejection code in the closed set is exercised by a fixture* (both suites; 33 of 33) |
| **AC-14** link shapes | web + app | `competition-pages.test.mjs` → *a join reference is recognised in every shape we hand out*; `CompetitionShareLinkTest` → *recognises every shape a link arrives in* |
| **AC-15** wrong kind / wrong d-tag | web + app | `parseCompetitionRef` refuses; `CompetitionShareLinkTest` → *refuses an naddr for another kind*, *refuses an naddr whose d-tag is not a competition* |
| **AC-16** QR decodes back | web | `competition-qr.test.mjs` → *the symbol decodes back to exactly the text that went in*, *every Reed-Solomon block is a valid codeword*, *the function areas match an independent encoder exactly* |
| **AC-17** published crypto vectors | web | `nostr-crypto.test.mjs` (19 BIP-340 vectors incl. 10 negative, RFC 8439 ChaCha20); `nip44.test.mjs` (35 conversation keys, 24 padding, 10 encrypt/decrypt, 3 long, 12 must-fail) |
| **AC-18** key never in plaintext | web | `competition-signer.test.mjs` → *a sealed vault contains no plaintext key material*, *the session never writes plaintext to storage*, *a shared device never touches storage at all*, *a session expires on the absolute limit and on the idle limit* |
| **AC-19** real backup confirmation | web | *the backup challenge asks about real positions and is stable* |
| **AC-20** NIP-46 user pubkey + timeouts | web | *a NIP-46 session connects, learns the USER pubkey, and signs* (asserts it differs from the bunker pubkey); *a silent bunker times out with a message instead of hanging forever* |
| **AC-21** failed publish reported | web + app | `competition-e2e.test.mjs` → *a publish that no relay accepts is reported as a failure*; app: `CompetitionIntentPublisher` returns `Failed("no_relay")` on `accepted == 0` |
| **AC-22** string parity | web + app | `competition-pages.test.mjs` → *English and German define exactly the same keys*, *no translation is left as the English text by accident*, *every placeholder … exists in the German one*; `CompetitionStringsTest` → four equivalents |
| **AC-23** no inline script/style, no innerHTML | web | `competition-pages.test.mjs` → *no competition page carries an inline script or style*, *nothing in the app assigns innerHTML*, *every page ships a content security policy that forbids inline script* |
| **AC-24** protocol layer is DOM-free | web | *the protocol layer stays free of the DOM* |
| **AC-25** no public relay, no sats | web | `competition-fixtures.test.mjs` → *no fixture references a public relay*; `dev-relay.test.mjs` → *the relay refuses to bind anything but loopback*; `competition-e2e.test.mjs` asserts every relay URL is loopback |
| **AC-26** end to end | web | `competition-e2e.test.mjs` → *a competition runs end to end and every reader agrees on the state* (four independent readers, one state hash) |
| **AC-27** profile gate on all three signers | web | `competition-profile.test.mjs` → *a NIP-07 extension can publish a profile that satisfies the gate*, *a NIP-46 bunker can too*, *a local key can too*; *a name of only invisible characters is refused* |
| **AC-28** unreachable ≠ no profile | web | `competition-profile.test.mjs` → *an unreachable relay is not mistaken for "you have no profile"*; `relay-pool.mjs` reports `answered` and `failed` separately |
| **AC-29** real climbs only | web + app | `competition-climb-ref.test.mjs` → *a placeholder uuid is refused in every form it arrives in*, *a competition carrying a placeholder climb does not validate*; `CompetitionValidationTest` → the same rules in the app, plus *the uuid rules agree with the website's, character for character* |
| **AC-30** every mode is offered | web | `competition-pages.test.mjs` → *the organizer form offers every mode the protocol defines*, *every mode the form offers has a label in both languages*, *the participant pages can render every mode the form can set*; `competition-e2e.test.mjs` runs both climb sources end to end |
| **AC-31** claims decided in registration order | web | `competition-claims.test.mjs` → *registration order decides the race, not pubkey order*, *the answer does not depend on the order the requests arrived in*, *a decision already in the log is not published a second time*; `competition-e2e.test.mjs` → *two entrants race for one climb and the loser re-picks* |
| **AC-32** attempts only on your own climbs | web + app | fixture `paid-unique-async` produces `climb_not_selected`; both suites assert the closed code set (33 of 33) |
| **AC-33** the chooser exists only when it can be used | app | `CompetitionSelectionTest` → *may act only when every rule the reducer applies is satisfied*, *an unpaid entrant may not act*, *a resting climber may not act until the rest is over* |
| **AC-34** climbs resolve before they open | app | `CompetitionClimbResolverTest` → *a climb whose board is not downloaded says so, and is retryable*, *a climb no held board size can draw is not opened*, *placeholder and malformed uuids never reach the board screen* |
| **AC-35** https-only LNURL, checked amount | web | `competition-lightning.test.mjs` → *an endpoint that could be intercepted is refused, never downgraded*, *an invoice for a different amount is refused, not shown with a warning* |
| **AC-36** receipts are verified, not trusted | web | `competition-lightning.test.mjs` → *every way of being the wrong receipt is caught* (six cases), *the invoice has to be for at least the fee, and bound to this request*; `competition-e2e.test.mjs` → *a fee is settled by a receipt that verifies* |
| **AC-37** manual settlement is an audited override | web | `competition-e2e.test.mjs` → *…or by an override that is named*: asserts the write is refused without a reason and that the reason lands in the audit trail |
| **AC-38** the scanner accepts only our codes | app | `CompetitionQrDecoderTest` → *the code this app generates is the code this app reads*, *every other code someone might point the camera at is named*, *a naddr for something that is not a competition is refused* |
| **AC-39** cleartext stays out of release | app | `CompetitionDevRelayPolicyTest` → *the shipped policy permits no cleartext to loopback*, *the emulator host alias is not accepted, which is why adb reverse is the instruction* |

---

## Additional invariants with automation

| Invariant | Test |
|---|---|
| Canonical JSON is byte-identical across languages | `competition-protocol.test.mjs` + `CcjVectorTest`, against the same 13 vectors |
| The relay-URL rule is identical across clients | `relay_urls` vectors, asserted by `competition-protocol.test.mjs` and `RelayUrlTest` |
| NIP-19 encoding is identical across clients | `Nip19Test` → *encodes the same naddr the website recorded* |
| A tampered event fails verification although the signature is intact | `competition-protocol.test.mjs` → *a tampered body fails verification …* |
| An unknown operation demands an upgrade instead of being ignored | both suites → *an unknown operation stops the client instead of being ignored* |
| A correction without a reason is refused at the parser | `competition-reduce.test.mjs` |
| Fixtures are deterministic and complete | `competition-fixtures.test.mjs` → *the generator is deterministic across runs*, *the manifest digest covers every fixture file* |
| Every page's module graph resolves | `competition-pages.test.mjs` → *every module a page loads resolves, transitively* |
| The dev relay implements NIP-01 storage classes | `dev-relay.test.mjs` (12 tests) |
| `/comp/` routing exists in `404.html` | `competition-pages.test.mjs` |

---

## Device-owed and manual checks

Nothing below is skipped for convenience; each says exactly what it needs.

| Check | Why it is not automated here | How to do it |
|---|---|---|
| **Maestro UI flows** | No Android device is attached to this machine (`adb devices` is empty). The app's flows in `flows/` need one. | Attach a device and run `flows/run.sh`. |
| **Compose rendering of the new screens** | Same. The screens' logic is in view models, the resolver and the shared reducer, all covered; what is not covered is that Compose draws them. | Device, or add Compose UI tests with Robolectric. |
| **The camera preview and the permission dialog** | Needs a camera and a real permission grant. The decode path is covered without one: a QR is encoded, rendered to luminance the way a sensor would, and read back (`CompetitionQrDecoderTest`). Frame delivery, `adb reverse`, and Android's own permission UI are not. | Attach a device and follow the runbook's Android section. |
| **App Link verification** | `autoVerify` needs the real `assetlinks.json` at the real host and a real install. | Install a release build and open `https://cruxcoach.org/comp/<naddr>`. |
| **Scanning a printed QR with a real camera** | The encoder is proved by decoding and by Reed-Solomon syndromes, and the app's decoder is proved against the app's own encoder; a camera adds optics, not correctness. | Print the projector's QR and scan it with the in-app scanner. |
| **A real Lightning payment** | No test may spend a satoshi. Resolution, invoice checking and receipt verification are covered against locally signed fixtures whose invoice is deliberately unsigned and unpayable; what is not covered is a real provider's behaviour, and a zap receipt is that provider's attestation by construction. | An organizer with a real LNURL endpoint and a test payment. |
| **Behaviour against a real public relay** | No test may write to one. Relay limits are recorded in protocol §16.2 from live NIP-11 reads. | Run a competition on a relay you control. |
| **Screen-reader pass** | The markup is asserted (live regions, labels, focus, target sizes); how TalkBack and VoiceOver actually read it is not. | TalkBack on the app, VoiceOver on the participant page. |

---

## Load-sensitive tests, not caused by this branch

Two `:androidApp` tests fail when the machine is busy and pass when it is not.
Neither has code this branch touches — both drive board sync and the browser
view model, and this branch's only board-screen change is a Compose semantics
label on the drawer handle.

- `GymBoardPickerViewModelTest > selectGym on MoonBoard gym with resolved variant offers that variant`
- `BoardBrowserCatalogueRevisionTest > a chunk committed mid-sync reaches the browse filter before the run ends`

The second was characterised rather than re-run until green: it waits up to
`SETTLE_MS = 60_000` of **wall-clock** time for real `Dispatchers.IO` work, on a
four-core machine. It failed twice in a row while a lint run and an APK build
were in flight (load average 3.4) and passed on the first attempt once they
finished (load average 1.5). That is a real property of the test, not a verdict
on the code under it, and it is recorded here rather than left out — a suite
that needs a quiet machine is not the same as a green suite.
