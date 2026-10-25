# Private-sharing protocol documents

> **SUPERSEDED (2026-08-16).** This annex describes the withdrawn bilateral
> one-group-per-relationship design. The shipped model — three visibility
> circles, five categories, group baselines plus person and object exceptions,
> device-bound consent and a recovery code — is specified in
> [FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md), which wins
> wherever the two disagree. Kept as evidence of what was investigated.

Tracked, normative protocol documents for the **private personal-information
sharing layer** specified by
[FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md).

> **No-Go.** Every document here is `design-review`. None of them authorises
> product code or a release. An isolated, throwaway, upstream-first adapter
> spike must pass all eight binary gates in FEAT-062 §5 and an independent
> reviewer must confirm the evidence first. No adapter and no product
> implementation of this layer exists in this repository. The checked-in
> harness below is a specification oracle and changes none of that.

| Document | Owns |
|----------|------|
| [PRIVATE-SHARING-WIRE-V1.md](PRIVATE-SHARING-WIRE-V1.md) | the normative carrier, closed JCS schemas, deterministic identifiers and semantic slots, group bootstrap and component profile, reducer, provenance and retirement rules |
| [PRIVATE-SHARING-THREAT-MODEL.md](PRIVATE-SHARING-THREAT-MODEL.md) | assets, actors, threats/controls/residual risk, leakage matrix, forbidden claims, verification obligations |
| [PRIVATE-SHARING-GOLDEN-VECTORS.md](PRIVATE-SHARING-GOLDEN-VECTORS.md) | byte-exact fixtures and the mandatory negative corpus |

This file is a map only. It defines nothing. Where it and a companion document
disagree, the companion document wins, and the wire contract is the single
normative source for the protocol identifier, the namespace, the carrier kind
and the object registry.

## Reference harness and fixtures

```text
PYTHONDONTWRITEBYTECODE=1 python3 docs/specs/social/scripts/verify_private_sharing.py
```

| Path | Role |
|------|------|
| [scripts/private_sharing_oracle.py](scripts/private_sharing_oracle.py) | the spec oracle: derivations, canonical parser, closed schemas, reducer, trusted clock, outbox, topology and rollback evaluators |
| [scripts/build_fixtures.py](scripts/build_fixtures.py) | rebuilds every fixture file deterministically from the closed inputs |
| [scripts/negative_corpus.py](scripts/negative_corpus.py) | generates the concrete negative cases and owns the mandatory area list |
| [scripts/reducer_scenarios.py](scripts/reducer_scenarios.py) | generates the ordering, phase, clock, outbox, send-handover, bound, restore and grant-retirement scenarios, the adapter runtime gates, and the R6 gate and open-condition reconciliation |
| [scripts/verify_private_sharing.py](scripts/verify_private_sharing.py) | the single entry point: re-derives, executes, cross-checks the documents, checks links/anchors, runs the mutation battery, asserts the R6 gate registry per gate — status, capability, evidence scope, each spike's own criterion and verdict, and blocking remainder kept apart — binds every cell of both published gate tables, both published outbox-label tables, the §11.2 exactly-once claim table, the threat model's per-gate obligation table and the INDEX registry row to that registry, requires every closed reason vocabulary to be named by the wire contract and decided by a fixture and every closed schema vocabulary to be named by it, pins the normative rules of every requirement area — including the product spec's and threat model's own statements of them — and the whole forbidden-claims list, binds FEAT-062's ownership boundary, restated bounds, build inputs, state decisions, spike-gate list and acceptance-criteria set, plus the non-gate open conditions and the R6 provenance, verifies the manifests and the digest rule this README prints |
| [fixtures/positive-vectors.json](fixtures/positive-vectors.json) | closed inputs plus every derived identifier, envelope, size, digest and full encoder vector |
| [fixtures/negative-corpus.json](fixtures/negative-corpus.json) | one concrete case per mandatory mutation area, each with one of the five closed dispositions and a closed reason code |
| [fixtures/reducer-scenarios.json](fixtures/reducer-scenarios.json) | arrival permutations, component-profile phases, the generation state machine, commit rollback, trusted time, outbox leases and their external-effect recovery phases, the write-ahead send-handover order with its crash windows and post-call results, the replay-admission truth table with the blocked reason each combination shows, storage bounds, restore, every closed retirement cause with its purge window, adapter runtime gates, the eight R6 gates with their capability and evidence scope, R6's non-gate findings and release blockers, and the published registry, obligation and reason-mapping bindings |
| [fixtures/MANIFEST.json](fixtures/MANIFEST.json) | the bundle and evidence manifests defined below |

**What a green run means.** The specification is internally consistent, the
document and the fixtures reproduce each other in both directions, every
negative case lands on the disposition the corpus claims, and — because of the
mutation battery below — every expectation the fixtures declare is really an
assertion.

"Both directions" is meant literally on the value level: every value the golden
vectors print is bound to the fixture record it names — derivation digests and
rendered ids to the record its preimage identifies, event sizes and digests to
the event its inner id identifies, encoder bytes to that same event — and every
fixture event and encoder vector must still be printed. The manifest cannot do
that job: it is a whole-file digest, so it reports only *that* a file changed,
and `--write-manifest` is the documented answer to any bundle edit.

**What it does not mean.** The harness is a *spec oracle* written in Python. It
executes no Kotlin, no Rust, no MDK, no Marmot and nothing on a device. It is
not the adapter spike, it discharges none of the eight binary gates in FEAT-062
§5 and none of the eight R6 gates in
[wire §11.1](PRIVATE-SHARING-WIRE-V1.md#111-open-mdk-capability-gates-r6), and it
does not lift the No-Go. The same fixture files are meant to be consumed
unchanged by the later native and on-device conformance tests; that has not
happened.

### S9 is not R6

This bundle is the **S9** specification stage. **R5** and **R6** were executed
throwaway adapter spikes, and both returned NO-GO on every axis they judged.

R6 is the more recent and the more informative. Against the same MDK pin it
produced real runtime evidence — real MLS sessions, a real SQLCipher store, a
real MockRelay — for five of the six capabilities R5 left open, and it refuted
R5's finding that the pin cannot re-drive an identical transport message. It
still closed no gate: its own custom-reorg criterion is unmet because the
trigger is injected, it ran no instrumentation at all, and its finding that the
resume path emits no `PublishedApplicationMessage` removes the source-id
correlation this contract depends on.

The two stages are not interchangeable and the harness never pretends otherwise.
On every run it asserts, per gate, that the status is still `OPEN`, that no gate
claims a CruxCoach-suitable closure, that each names a non-empty blocking
remainder, and that no gate's evidence scope is native, on-device or release.
Capability and status are asserted separately on purpose: recording a proven
capability as absent would be as dishonest as treating it as a discharged gate.

Two of those checks are structural and one is not, which is worth stating
plainly. Each gate's own table row in FEAT-062 §5a and wire §11.1 is located and
read cell by cell — the capability verdict, the evidence scope, and the prose
columns carrying the R5 finding, the R6 finding, the blocking remainder and the
fail-closed rule — so no column can be blanked, no scope widened to a native,
on-device or release run, and no row can print R5's finding in R6's column.

That last one needs its own mechanism, because requiring the two evidence cells
merely to *differ* is satisfied perfectly by swapping them. So each gate records
the criterion and the verdict its spike actually reached — R5 criterion C
`FAIL` and R6 criterion 1 `PASS` for `R6-G2-TRANSPORT-IDENTICAL-RETRY`, R6
criterion 4 `NOT MET` for `R6-G5-CUSTOM-REORG`, nothing at all for
`R6-G1-DURABLE-PROJECTION`, which R6 never judged — and each published cell must
state its own pair and not the other's. The pair is what separates them where
the verdict alone does not: `R6-G8-INSTRUMENTATION-RUN` is `NOT TESTED` on both
sides and differs only by criterion. That gate was also the one row exempt from
the checks below it, because a gate with no locally decidable fail-closed
disposition used to skip the rest of its row entirely. §5a
states the scope in a column of its own, and a table that says "not tested" in
one cell while the next claims an on-device release run is the exact edit
reading only the capability cell would miss. The published outbox-state to
user-visible-label tables in FEAT-062 §8 and wire §8.7 are bound to the same
oracle map for the same reason: relabelling a state as delivered, softening
`DELIVERY_UNCLEAR` into a queued state, or giving audit-only `SUPERSEDED` an
active-send label would otherwise leave every fixture green while the
specification told a reader the opposite of what it forbids. The wire contract
must still print R6's binding record — the approved
`GO-NO-GO_1.md`, its patch digest and both pins — so the recorded evidence
cannot quietly come to describe a different spike. Each document's verdict
sentence is pinned by exact text for the same reason, as are the threat model's
forbidden claims about the exactly-once scope, about S9 not being R6, native or
on-device evidence, and about an uncorrelated transport acceptance not being a
delivery — inverting one of those is precisely the edit no content-level check
would otherwise notice. Beyond that the run
screens the bundle's prose for the common ways a document could announce a
closure; a phrase list is a backstop, not a proof, and it is the structural
checks that carry the weight.

The same rule applies to the closed vocabularies, and it is exhaustive over the
ones the oracle can decide: the §8.6 diagnostic codes, the §8.3 hold codes, the
§8.4 terminal fault codes and adapter invalidation reasons, the §8.7b–§8.7d
send-path codes, the §8.8 trusted-time distrust codes and §11.2's exactly-once
refusal code. Each must be **named by the wire contract**, so the harness never
enforces a closed set no specification states, and each must be **decided by a
fixture expectation**, so a code cannot be declared closed and then never
produced by anything. A code named only in a fixture `note` does not count.

Two of them had drifted one-sided in opposite ways. Five fixture expectations
decided `EXACTLY_ONCE_SCOPE_EXCEEDED` and the golden vectors printed it while
the normative contract never named it; §8.8's `BOOT_CHANGED`,
`MONOTONIC_RESET`, `WALL_ROLLBACK` and `WALL_FROZEN` were named and decided but
were not a closed set anywhere in the oracle, so nothing would have noticed a
fifth appearing. A reason code only a corpus knows is the mirror image of one
only a document states, and both are now checked the same way — which matters
most for §8.8, where the code is what decides whether a time-bounded grant stays
hidden and whether an unprovable purge is due.

Those are the codes the oracle *emits*. The sets it *accepts against* — the
enumerated value spaces of the wire bodies themselves: the §5 body types, the
§6.2 levels and offer values, the §6.3 scopes, the §6.4 revoke reasons, the
§7.3/§7.5 phases, session types and focus areas, the §4.5 ecosystems and the
§9.3 component ids — were unguarded in the direction that matters most for
them, which is widening. `BODY_TYPES` gaining a seventh body, `LEVELS` a fourth
relationship level, `REVOKE_REASONS` a fourth reason: each would have the oracle
silently start admitting a value no specification defines, and no fixture
expectation changes when a closed set merely grows. Every member must now be
named by the wire contract in a code span, which is how the contract names all
of them today. Only that direction is checked, deliberately: the reason-code
rule's second half — "some fixture expectation decides it" — does not transfer
to input value spaces, where a legitimate member such as the `PLAN_SUMMARY`
scope may never be an expected outcome anywhere. The §9.3 ids are additionally
required to be a partition, which is what makes the leaf-only set an assertion
rather than the comment it had become: nothing read it at all.

Naming a closed set is not the same as mapping it, and for §8.8 the difference
is the whole rule: with all four codes still named and still decided, swapping
the `WALL_ROLLBACK` and `WALL_FROZEN` rows left a contract that says a parked
wall clock is a rollback. So the condition-to-code table is bound row by row
below, and the two values that table rests on — the 900-second freeze tolerance
and the `effective_now` formula — are pinned against the clock the oracle
actually runs, because neither is a vocabulary token nor a bound cell and no
other check reaches them.

Published tables are bound the same way, because a printed mapping nothing
reads is decoration however normative it sounds:

- wire §11.2's exactly-once claim table, where flipping one row from
  `forbidden` to `permitted` is the whole of what this bundle forbids claiming;
- wire §8.7c's refusal mapping and wire §8.7b's unmet-condition mapping, which
  are the same rules the oracle decides in code — including the blocked reason
  no recovery can reach while `R6-G2-TRANSPORT-IDENTICAL-RETRY` is open, which
  is precisely the row a reader would use to conclude that closing that gate
  would be enough;
- the threat model's per-gate obligation table, so an emptied cell cannot
  silently retire what a gate still owes;
- every remaining normative table, row by row and in printed order, against rows
  the oracle computes from the same decision functions the scenarios drive:
  §8.7b's lease-recovery outcomes and their golden twin, §8.7c's and golden
  §8.9's crash windows, §8.7d's transport-call results, golden §8.4's
  commit-rollback outcomes, §8.7's forbidden supersession pairs, §9.3's and
  golden §2.2's phase-dependent profile, §6.3's scope levels and field tokens,
  §8.2's storage bounds, §3.2's collection limits, §8.6's dispositions,
  golden §6.5's prospective boundary, §8.8's distrust conditions with golden
  §8.9's trusted-time verdicts, §10's grant-retirement causes with their
  FEAT-062 §8 twin, and — added in this round — the two product-level tables a
  reader who never opens an annex actually meets: FEAT-062 §2.3's pinned build
  inputs, whose two SHAs are taken from the recorded R6 provenance rather than
  restated, and FEAT-062 §3.3's state decisions, every row of which is answered
  by the evaluator that already owns it, so the product view cannot report MDK
  quarantine or a post-activation authority rollback as a repairable hold. Each
  was decoration before: §8.7b could be
  edited to requeue an unclear row, §8.7d to call an uncorrelated acceptance an
  observed delivery, §8.7c to leave a pre-effect row after an authorised
  handover, §8.4a to hold instead of terminalise after activation, §6.3 to lower
  `LOGBOOK_DETAIL` to `ACQUAINTANCE` — each with every fixture, oracle and
  manifest check still green. Two of §8.7b's rows are counterfactual — *were the
  gate closed* — and the same recovery function decides them under that stated
  hypothesis, while the run separately asserts that the real world still blocks;
- the four tables whose cells are a **machine value** rather than a prose rule,
  added in this round and the last unbound ones of that shape: §2.1's carrier
  fields, §5's envelope members, §4.3's scope codes and §7.6's closed delta
  operations. Each was decoration. §2.1's `kind` cell could be
  edited to `1221`, or its id preimage to a shorter array, while every golden
  encoder vector went on printing 1220 — the table those vectors exist to
  illustrate was read by nothing. §5's `seq` row could be deleted outright, and
  its `previous_event_id` rule — *forbidden at 1; required above 1* — inverted,
  which is what makes a semantic slot a chain and what §8.2's whole ordering
  rule rests on. §4.3's codes could be swapped, and a scope
  code is not a label: it is the `u8` byte inside
  `SHA256(… || u8(scope_code) || resource_suffix)`, so two swapped rows are two
  different deterministic grant slots and a permanent interop break. §7.6 could
  widen a summary scope to an operation the reducer refuses. None of the four
  can now be satisfied by restating the document: the carrier rows come from the
  encoder's own field order, the envelope rows from the oracle's own closed
  member set with the predecessor cell decided by really running
  `validate_envelope` at both sequences, each scope code is read back out of the
  preimage the oracle really builds, and the operation sets are the map
  `_validate_ops` consults before any shape check, so narrowing that map rejects
  a golden delta rather than quietly agreeing with the table. Because three wire
  tables share the header `Field | Requirement`, a binding may name the heading
  whose section owns its table, and that heading is then pinned too. What stays
  prose-level is stated
  plainly: the value cells of §4.5's board identity, §6.1's acceptance
  requirements and §7.4/§7.5's grant-token tables restate rules the corpus areas
  `board_and_problem`, `cc.generation.accept.v1`, `logbook_detail_rows` and
  `plan_detail_rows` decide by execution, and they are not bound row by row;
- golden §8.11's lifecycle table, added in this round. The fixtures already drove
  the complete three-state/nine-event matrix, so the *machine* was covered and
  the printed table was not: it is where a reader who opens no fixture learns
  that `ACTIVE` never returns to `PROVISIONING` and that `TERMINAL` is
  absorbing. Each printed edge is now decided by really running the machine, and
  a refused edge must print no resulting state at all — that absence is what
  separates it from one that survives, so giving a refused edge a state, or
  printing a hold where an authority rollback after activation terminalises,
  fails instead of leaving every scenario check green;
- the three tables §8.7b and §8.7e use to state *why the replay route is shut*,
  which were the last unbound ones and the ones this round added. §8.7b's
  conjunction table carries a **State today** column — the persisted identity is
  row-dependent, the product gate is `open`, the source-correlated receipt is
  `unavailable` — and nothing read it, so the contract could announce a closed
  gate and an available correlation while the oracle went on refusing every
  replay. Both cells are now taken from the registries the rest of the bundle is
  decided from, the gate's status from the §11.1 registry and the correlation
  from R6 finding 1, so the table cannot claim a state the run does not hold and
  closing a gate in one place without the other fails. §8.7e's drain table
  prints the four conjuncts a transport-identical replay must satisfy, and
  dropping the closed gate or the source-correlated receipt from that cell is
  exactly the "closing `R6-G2-TRANSPORT-IDENTICAL-RETRY` is enough" reading both
  sections exist to refuse; each conjunct is now read from the same evaluator.
  And §8.7b's phase table defines what `PRE_EXTERNAL_EFFECT` *means* — the send
  was never authorised, so nothing can have left the device — which is the whole
  reason the one automatic requeue is safe; redefining it left every recovery
  row printing correctly above a definition that no longer carried them;
- each open gate's **fail-closed cell** in both gate tables, against the
  disposition that gate's own evaluator reaches. Only the cell's length was
  checked before, so softening `R6-G1-DURABLE-PROJECTION`'s rule from
  *terminalises* to *holds* contradicted §8.5a in the one cell a product reader
  consults and nothing noticed;
- the two file lists printed in this README, against the sets the manifest rule
  actually digests, **and** the digest formula printed beside them. An
  independent reviewer recomputes both values from this file without the
  repository's tooling, so a file dropped, added or reordered there — or a
  widened length prefix, or a renamed hash — is a different rule from the one
  the harness applies, and `--write-manifest` would have papered over it. The
  entries `MANIFEST.json` records are bound the same way and for the same
  reader, in order and including each byte length, because that reviewer hashes
  what the manifest *records*: only a path-to-digest mapping was compared
  before, so a zeroed or dropped `size`, or a reversed `files` list, left the
  recorded preimage disagreeing with the digest printed beside it;
- FEAT-062 §2.2's ownership boundary, in both directions: each side's exclusive
  phrases must appear in its own column and never in the other's, so the two
  cells of a row cannot be swapped into a contract that hands MLS, the group id
  and membership to CruxCoach;
- the bounds FEAT-062 restates in prose — the 65,536-byte envelope cap, the
  100-session detail bound, the 1–50 delta range, the 90-day grant maximum, the
  100-send and quarantine limits — against the values the oracle enforces, and
  the ABI, API level and NDK that §5 gate 1 and criterion 1 restate against the
  §2.3 pins, because a product document may not widen a cap the annex holds;
- the threat model's leakage matrix, whose three grant-decided rows are bound to
  wire §6.3's minimum levels: a scope requiring `FRIEND` may not be shown as
  visible to an acquaintance in the one table a reviewer reads for the summary;
- and the `FEAT-062` row of `docs/specs/INDEX.md` together with the spec's own
  front matter, because that row is what tells a reader — or an implementer —
  whether this feature may be worked on at all. Every status the registry prints
  must additionally be one its own legend defines, for every row and not just
  this one: a lifecycle word no legend explains is exactly as unreadable as a
  wrong one. The edit that registered FEAT-062 added a `reserved` row the legend
  did not define, and `planned` had gone undefined for longer; both are in the
  legend now, and the check is what keeps the next one from slipping through.

Golden §7's reducer-sequence table is bound as a **set of row ids**, which is a
different shape from the tables above and the last place an unexecuted claim
could sit. Every printed row must be a `sequences` scenario of the same id and
every scenario must be printed, with one closed exemption — 7.0c, which the
negative corpus decides, and whose named case is looked up and its disposition
and reason checked, so "decided by the corpus" cannot become a parking space.
That binding is what this round's other correction came out of: golden §7.0e
printed a row that nothing executed, and the document carried a paragraph saying
so — the acceptance slot had no byte-exact second-valid-event input, so wire
§8.2's slot-agnostic equal-sequence rule was driven on the offer slot only. The
missing event is now a golden §4.1 vector and a corpus case, both arrival orders
of it are scenarios, and 7.11's identical "under either arrival order" claim is
executed on the offer slot too rather than asserted once and driven once.

Two lists are bound as lists, for the same reason: FEAT-062 §5's eight binary
gates and §11's acceptance criteria. Both were free prose, so a gate could be
dropped from the list the No-Go is defined by, and a criterion could be deleted
outright, with nothing noticing. The gate list is pinned by number and title
together with the sentences requiring all eight to pass and refusing a partial
pass; the criteria are pinned as a closed, ordered set of ids — the order
matters because the documents cite them by number — and each one must still
state a sentence rather than a placeholder.

The closed-value rule extends past the vocabularies to the machines: every
combination of the three generation states and the nine lifecycle events is
exercised by some scenario, accepted or refused, and every commit-rollback seam
and every authority commit class is driven on **both** sides of activation —
per seam and per class, not merely somewhere in the set, because §8.4a gives the
same signal two different outcomes and the generation state is what picks one.
The replay-admission truth table is decided the same way: all eight combinations
of its three conditions, exactly once each, so a dropped row fails the run
instead of quietly shrinking the table that proves no single condition is
sufficient on its own.

### The normative rules themselves are pinned

Everything above binds a *shape*: a table row, a closed vocabulary, a gate cell,
a fixture expectation. The rules those shapes serve are stated in running prose,
and prose was bound by almost nothing — a dozen verdict sentences and no more.
So the bundle's central claims could be inverted outright with every fixture,
oracle, table and vocabulary check green:

- §8.5a's "MUST NOT re-read the MDK raw app store as authority" into "MAY",
  which is the one rule this whole specification exists to state, together with
  "live delivery is never a correctness input" and the terminality of an item
  lost before its host ACK;
- §8.7a's "a retry MUST be transport-identical" into "SHOULD", and "a re-queued
  payload is a new event, never a retry of the same inner id" into its opposite;
- §8.4's one-to-one `source_message_id` → `inner_event_id` mapping into a
  many-to-many one, its zero-/multi-match terminality into "ignored", and "no
  partial recomputation after invalidation" into a permission;
- §10's "never revive old data" and "never extends the purge deadline" into
  permissions, §6.4's immediate hide-and-purge into an eventual one, §2.4's
  isolation `MUST NOT` into `MAY`;
- §0's record of what R5 established and what R6 refuted, and its inventory of
  what the pin does **not** have — which is where "no missing MDK capability is
  claimed as present" is actually written down;
- and, in `docs/specs/INDEX.md`, the sentence that says this feature's queue is
  `blocked` and that it is not queued for the autonomous implementer. The
  registry row's status cell was bound to the spec's front matter, but the queue
  state was only required to appear *somewhere* in the file — and `blocked`
  appears twice, so rewriting "its queue is explicitly `blocked`" into `open`
  left the other occurrence satisfying the check. The same file's "both returned
  **NO-GO**" and "all eight named R6 gates therefore stay open" were unbound for
  a related reason: the prose blacklist matches fixed spellings, and neither
  sentence names a gate id for the per-gate screens to catch.

A further group joined them in this round, and they share one shape: the wire
contract is where a rule is *normative*, and for several of the load-bearing
ones only a restatement elsewhere was pinned. The annex could therefore be
inverted alone while the product document, the threat model and every table
went on reading correctly.

- **§8.5, the ACK and crash boundary, was pinned nowhere at all.** The host ACK
  could be moved ahead of the commit it exists to follow, the cross-store
  transaction §7.2 and the threat model both forbid could be permitted here,
  and retention or five epoch advances could be allowed to take an
  unacknowledged item. That section is the durable projection's whole
  mechanism, and FEAT-062 §7.2's restatement was carrying it by itself.
- **§8.4a's own rules 3–5** — a generation cannot un-become active, a
  pre-activation rollback returns to `PROVISIONING`, tombstones survive
  both — were bound only through golden §8.4's outcome table.
- **§8.7c's write-ahead order itself**, together with the implication the whole
  of §8.7b rests on. §8.7b's phase table is bound row by row and the
  send-handover scenarios drive every crash window, but the two numbered steps
  that *fix* the order — phase committed first, transport called only once that
  commit is proven — and the step-3 rule that a commit whose result the caller
  cannot establish authorises nothing were pinned nowhere. Inverting them
  reopens precisely the window the section exists to close: a crash after a
  possible external effect but before the phase commit, leaving a row that still
  reads `PRE_EXTERNAL_EFFECT` and is therefore automatically requeued and driven
  a second time. The quoted implication is what makes that one automatic requeue
  safe, and it could be negated outright with every scenario still green.
- **§8.7's boot rule for a foreign-boot lease** — expired outright, *without
  comparing `lease_until` at all*. A monotonic deadline minted in a previous
  boot means nothing in this one, so re-admitting that comparison would let a
  rebooted device either treat a still-"live" foreign lease as held, or take a
  row whose external-effect phase is the only thing entitled to decide it.
- **Four more §8.7 rules found the same way**, each a fail-closed default that
  no table or scenario reached: that no recovery path ever mints a second inner
  event id, which is what keeps §8.4's one-to-one correlation decidable at all;
  that an unreadable or undecodable phase is read as `EXTERNAL_EFFECT_UNCLEAR`
  rather than `PRE_EXTERNAL_EFFECT`, the under-approximation §8.7c exists to
  forbid; that a `FAILED` row is never pruned by age alone, because a silently
  vanished revoke is a safety regression; and that a row may be `SUPERSEDED`
  only when its replacement is already durably enqueued in the same committed
  transaction, so supersession cannot drop a send with nothing standing in
  for it.
- **§9.5's activation gate**: FEAT-062 §3.3's row is bound to the evaluator,
  but the wire's precondition, that send *and* apply stay blocked until one
  session-consistent read proves all of it, was not.
- **The 65,536-byte cap** was compared against the oracle only where FEAT-062
  restates it, so §3.2 and §2.3 could widen it alone — taking the
  no-overflow-carrier rule with them.
- **§11's required-adapter-contract rows** for the inbox and the ACK. Shorten
  those two and the spike's target becomes a contract MDK already satisfies,
  with `R6-G1-DURABLE-PROJECTION` still printing `OPEN` above it.
- **§11's four deep sub-conditions**, pinned before only through the threat
  model's restatement.
- **§8.7's outbox `state` column rule**, the declaration of the closed
  seven-state set. Both label tables are bound row by row and are total over
  those seven, but an eighth state printed here — `DELIVERED` being the obvious
  one — would carry no label, no oracle behaviour and no forbidden-claim screen.
- **§11.1's two non-gate R6 results** — five red `marmot-app` relay tests and
  the unreproduced consumer APKs. Both are in the oracle's open-condition
  registry and asserted there, but the sentence a release reader meets could be
  rewritten from "five" to "zero" and retire a red gate outright; neither
  sentence names a gate id, so no per-gate screen reached it.
- **FEAT-062 §12's "`R6-G8-INSTRUMENTATION-RUN` remains untested"**, which is
  the reason every other gate's evidence is recorded as host-scope. §12 could
  announce the on-device run that never happened while §5a and §11.1 still
  printed "not tested".
- **Golden §1.1's "the harness is a spec oracle"** — the half of criterion 50b
  that says what the run's authority *is*. Only the half saying what it does
  not execute was pinned.
- **Criterion 50c and this file's own statement of the mutation battery.**
  Nothing bound the word *every*: both could be softened to a sample, which is
  the single edit that would make the battery's guarantee, and the slot count
  printed on every run, mean nothing.
- **§8.7e's verdict on its own last row** — defined and unreachable, for two
  independent reasons — which is where a reader learns the replay route's
  current state, and is exactly what `evaluate_drain_step` computes for it.

Only `MANIFEST.json` moved for any of them, and `--write-manifest` is the
documented answer to any bundle edit, so the whole-file digest is precisely not
a semantic check.

Each of those rules is now pinned by exact text, grouped by the requirement area
it belongs to:
durable projection,
broadcast lag and replay,
transport-identical retry,
invalidation and reorg,
delivery states,
the exactly-once boundary,
grant retirement and revocation,
offline and restore,
honest UI,
inbound isolation,
the R5/R6 reconciliation,
MDK-capability honesty,
the registry's queue and status,
generation bootstrap,
security claims,
spike-gate discipline,
and data minimisation.
The area list is closed and asserted in both directions, so an area
cannot lose its last pin and quietly stop being covered, and no pin can claim an
area nobody declared — and this printed list is bound to it too, so the map a
reviewer reads cannot describe a smaller or larger guarantee than the run makes.

The last four areas, and every pin in them, come from this round. Every pin above
was in the wire contract, this README or the registry, and the two documents a
product reader actually opens — `FEAT-062` itself and the threat model — carried
almost none. That was not a gap in coverage of *some* rule: §7.2 could claim a
transaction spanning MDK storage and SQLCipher, §9 could make a restored row
sendable, §10.7 could let a sharing event into chat and raise a push, §4.1 could
make the computed `NOT_ESTABLISHED` level close every generation the moment it
opens, §3.1 could let either endpoint create the group, criterion 42a could
permit the exactly-once claim it exists to forbid, criterion 26h could requeue an
unclear row, and threat-model PT-26, PT-37, PT-38, PT-39 and PT-40 could each be
inverted into the risk they name — with the gate tables, the fixtures, the closed
vocabularies and the manifest all green. Threat model §6's four deep
sub-conditions, and golden §1.1's statement that a green run is evidence about
this specification only, are pinned for the same reason.

The threat model's forbidden claims are bound as a list rather than sentence by
sentence, because that is the shape they have: §8 must print exactly the closed
set, in order, numbered from 1 with no gaps. Three of the fourteen were pinned
before; deleting any of the other eleven — "the MDK raw application store is
durable CruxCoach state", "a re-encrypted or re-queued send is a retry of the
same inner event", "queued or relay-published means peer-delivered" — retired a
prohibition this bundle rests on with the run still green. The numbering is part
of the binding because the documents cite these claims by number.

What this does **not** do, stated plainly: a pin proves the rule is still
stated, not that no sentence elsewhere contradicts it. The structural bindings
carry that weight. Rewording a pinned rule is a reviewed decision, and having to
update the registry is the point rather than a cost.

### Fixtures are generated, not written

`build_fixtures.py` is the only author of the three fixture files, and the run
proves it: every section of `negative-corpus.json` and `reducer-scenarios.json`
is compared against a fresh generator run, exactly as `positive-vectors.json`
already was. Hand-editing a case into a JSON file, or deleting one from a
generator while the JSON keeps it, fails the run rather than reproducing
nothing. The comparison is meaningful because the generators are deterministic
by construction — closed inputs only, no set iteration, no clock, no randomness,
one `sort_keys=True` dump for all three — so a differing section is an edit and
never an ordering artefact.

### Mutation battery

A fixture expectation that nothing asserts is decoration, so the harness proves
the opposite on every run: it takes **every** expectation field in
`positive-vectors.json`, `negative-corpus.json` and `reducer-scenarios.json`,
mutates it one at a time, re-runs the checks that own that fixture location, and
requires them to fail. An expectation that survives its own mutation is reported
as *never asserted* and fails the run. Nothing is sampled, nothing is capped and
nothing is skipped; the run prints the number of mutation slots it covered.

The battery's own list of slots is hand-written, so the run also checks that
list for completeness: every expectation-shaped field in the three fixture files
must be one the battery actually reaches. Without that guard a newly added
expectation would simply not be enumerated — nothing would read it, nothing
would mutate it, and the run would stay green while the fixture claimed an
outcome no check owns. An expectation field outside the battery is reported with
its exact fixture path and fails the run. An empty expectation container fails
for the same reason from the other direction: it yields no leaf, so it would
declare no outcome, produce no mutation slot and pass by claiming nothing.

### Normative reproduction

`docs/specs/social/scripts/verify_private_sharing.py` is the **only** normative
reproduction of this bundle. Scripts outside the repository are not normative
and are superseded on sight, including the throwaway reproducers under `/tmp`
used during earlier review rounds: they predate the publisher-bound projection
slots of wire §4.3 and the time-consistent UUIDv7 grant identifiers of §6.3, so
they still compute the old projection slot ids, grant ids and every envelope
digest derived from them. Where any of them disagrees with the checked-in
harness, the checked-in harness wins.

The same applies to handoff and review reports that predate this bundle. A
report asking for a two-element `["l","v1"]` label tag is superseded by wire
§2.1, which requires the full NIP-32 form
`["l","v1","com.cruxcoach.private-sharing"]`; the corpus case
`carrier_l_tag_two_elements` pins that as `DROP_BEFORE_PARSE`.

### Bundle and evidence manifest rule

Two manifests are computed, both recorded in
[fixtures/MANIFEST.json](fixtures/MANIFEST.json) and both recomputed and
compared on every harness run.

The **bundle** set is these six files, in exactly this order:

```text
docs/specs/INDEX.md
docs/specs/0.2.3/FEAT-062-personal-information-sharing.md
docs/specs/social/README.md
docs/specs/social/PRIVATE-SHARING-WIRE-V1.md
docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md
docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md
```

The **evidence** set is these eight files, in exactly this order:

```text
docs/specs/social/fixtures/negative-corpus.json
docs/specs/social/fixtures/positive-vectors.json
docs/specs/social/fixtures/reducer-scenarios.json
docs/specs/social/scripts/build_fixtures.py
docs/specs/social/scripts/negative_corpus.py
docs/specs/social/scripts/private_sharing_oracle.py
docs/specs/social/scripts/reducer_scenarios.py
docs/specs/social/scripts/verify_private_sharing.py
```

For each set:

- each entry records the repository-relative path, the byte length and
  `SHA-256(file bytes)`;
- the set digest is `SHA-256` over the concatenation, in the listed order, of

```text
for each entry:  UTF8(path) || 0x00 || u32be(byte length) || file bytes
```

`u32be` is a four-byte big-endian unsigned length. Paths are hashed, so
renaming a file changes the digest. `MANIFEST.json` is excluded from both sets,
which is what lets it record both digests without a self-reference.

An independent reviewer can therefore recompute both values without this
repository's tooling. Regenerate them after any bundle edit with:

```text
PYTHONDONTWRITEBYTECODE=1 python3 docs/specs/social/scripts/verify_private_sharing.py --write-manifest
```

A stale `MANIFEST.json` is a harness failure, not a warning.

## Runtime path

Marmot/MLS through the Marmot Development Kit is the **sole** runtime,
transport and convergence path. One minimal bilateral Marmot group carries one
relationship generation. MDK owns MLS, membership, admin policy, signed group
components, relays, routing, cursor, epoch, fork selection, retained history
and invalidation. CruxCoach owns only application semantics inside the
canonical `content` bytes of an inner Marmot app event of kind 1220.

There is no second relay pool, no second transport cursor and no application
transport of any kind beside MDK.

MDK owning the transport does **not** make MDK's raw application store CruxCoach
state. The R5 spike proved a real retention sweep deletes expired raw rows, so
the raw store is never read as authority: CruxCoach keeps its own durable
projection — the acknowledged inbox plus reduced state — and an item lost before
its host ACK terminalises that generation instead of being reconstructed. Live
subscription delivery is an optimisation on top of that durable path, never a
correctness input. Both properties are missing at the pin and are tracked as
gates `R6-G1-DURABLE-PROJECTION` and `R6-G3-BROADCAST-LAG-REPLAY`.

## Scope boundary

These documents cover **only** the bilateral private layer: deterministic group
bootstrap, two mutual relationship levels, explicit per-peer/per-scope/
per-field grants, prospective detail projections, and a deterministic
fail-closed reducer.

They deliberately do **not** describe, reserve or authorise public hall
directories, public communities, operator verification, public leaderboards,
community send counts, competitions, multi-recipient groups, teams, or chat.
Those are separate, unimplemented concerns; nothing here may be read as a
partial specification of them.

The `scripts/` and `fixtures/` directories fall under the same boundary: they
are specification evidence, not product code, not an adapter, and not a build
input for any Gradle module.

## Replaced architecture

The earlier draft of this layer carried its own transport. Its NIP-17 control
messages, kind-30078 projection feeds, feed/index/reader keys, second social
relay pool and Blossom overflow blobs are **withdrawn in full**. They are not a
fallback, a migration target or a compatibility path, and no document in this
directory may reintroduce them. Two anchors this file previously published —
NIP-17 layer verification and kind-10050 inbox readiness — described that
withdrawn design and no longer exist.

Publication of the account's Marmot KeyPackage (kind 30443) and of a NIP-17
kind-10050 inbox relay list is MDK-owned account metadata, not a sharing
control message. Those publications remain withdrawn as *this layer's* wire
objects; where MDK needs them they require an explicit, informed user action
under the consent rule in the wire contract.
