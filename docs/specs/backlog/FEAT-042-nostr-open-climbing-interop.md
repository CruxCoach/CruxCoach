---
status: backlog
---
# Feature Spec: Open Climbing Data Interop via Nostr (backlog — vision-tier)

> **Status:** Backlog — captured 2026-06-24. Vision-tier. Records the
> "Nostr as protocol, not just as a feature" ambition: use Nostr as an
> **open exchange layer** so climbing apps interoperate in an open
> ecosystem rather than each guarding a silo. Concrete first instance:
> CruxCoach ↔ BoardSesh data exchange over an agreed wire format.
>
> **Relates to:** [[project_boardsesh_data_agreement]] (existing
> bilateral exchange — the pragmatic precursor), [[FEAT-014]] (defines
> several climbing event kinds), [[FEAT-003]] / [[FEAT-009]] (existing
> Kind-30078 climb + Kind-30079 vote wire formats).

---

## 1. Overview

CruxCoach already uses Nostr as a **communication protocol** between
its own components and for community-climb publish/sync. The larger
ambition is to make Nostr the **open interoperability layer for
climbing data across apps** — so a climb, an ascent, or a grade vote
created in one app is natively readable by another, with no central
broker and no proprietary API.

The first real instance is interop with **BoardSesh**: today the
two exchange data bilaterally via an agreed GraphQL pull (see
[[project_boardsesh_data_agreement]]). The vision is to lift that
from a private bilateral arrangement to a **published, open event
standard** any climbing app can implement.

### 1.1 Goals

- Define open Nostr event kinds/tags for the core climbing nouns:
  climb definition, ascent/send, grade vote, (optionally) location.
- Make CruxCoach both publish and consume that standard.
- Drive it as an actual spec (draft NIP), not a CruxCoach-only dialect.

### 1.2 Non-Goals

- Forcing other apps to adopt it. Open standard = invitation, not
  mandate. Bilateral pragmatic exchange stays the fallback.
- Re-litigating already-shipped kinds gratuitously; extend, don't churn.

---

## 2. What already exists vs. what's needed

**Exists:** Kind-30078 (climb), Kind-30079 (vote); [[FEAT-014]]
proposes 30090–30092 for live coordination. These are CruxCoach
conventions, not yet a published cross-app standard.

**Needed for true interop:**
- A documented, versioned schema for each shared event kind —
  field names, tag conventions, cross-board identity rules
  (the composite-key / board-local-id gotchas live here).
- An **ascent/send** event kind (currently CruxCoach keeps sends in
  the private encrypted DB; sharing them is opt-in and needs a wire
  format).
- A namespacing/origin convention so a BoardSesh-origin climb and a
  CruxCoach-origin climb coexist without id collisions (the
  `origin` field work already prototypes this).

---

## 3. Path

1. **Document the current dialect** — write down the de-facto
   Kind-30078/30079 formats as they ship today.
2. **Draft a NIP** — propose the climbing event kinds publicly so
   it's a standard, not a CruxCoach quirk.
3. **Bilateral pilot** — implement read+write of the agreed format
   with BoardSesh, replacing/augmenting the current GraphQL bridge.
4. **Open invitation** — publish + invite other clients.

---

## 4. Open Questions

1. Ascent-sharing privacy — sends are personal data. Any open
   ascent kind must be strictly opt-in with clear granularity.
2. Identity reconciliation across apps — same physical climb,
   different app-origin ids. Canonical-key strategy?
3. Governance — who stewards the NIP once others adopt it? Avoid
   CruxCoach becoming a de-facto central authority, which would
   defeat the open-ecosystem point.
4. Versioning — how do consumers handle schema evolution gracefully?

---

## 5. Caveats

- Driving an open standard is real, sustained work (spec writing,
  cross-vendor coordination) far beyond CruxCoach's own code.
- The pragmatic bilateral exchange already works; this spec is the
  *more ambitious* path, justified only if open interop is a genuine
  strategic goal and not just nice-to-have.
