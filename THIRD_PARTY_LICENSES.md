# Third-Party Licenses

CruxCoach source code is licensed under the GNU General Public License v3.0
(see [LICENSE](LICENSE)). The CruxCoach **name and logo** are *not* covered
by GPLv3 — they are subject to a separate trademark policy
(see [TRADEMARK.md](TRADEMARK.md)) and the brand-asset license at
[`logos/LICENSE`](logos/LICENSE).

This document lists third-party components distributed with CruxCoach and the
licenses under which they are used.

Runtime dependencies pulled at build time (Gradle/Maven) are not listed here —
their license metadata is captured in the build output. This file documents
**vendored** sources and **bundled assets** that ship inside the repository
or APK.

---

## Vendored Source Code

### zstd (1.5.6) — Meta Platforms, Inc.

- **Location:** [`androidApp/src/main/cpp/zstd/`](androidApp/src/main/cpp/zstd/)
- **Upstream:** https://github.com/facebook/zstd
- **License:** Dual BSD-3-Clause OR GPLv2
- **CruxCoach election:** **BSD-3-Clause** (required for GPLv3 compatibility)
- **Texts:** [`LICENSE`](androidApp/src/main/cpp/zstd/LICENSE), [`COPYING`](androidApp/src/main/cpp/zstd/COPYING)
- **Details:** [`README.md`](androidApp/src/main/cpp/zstd/README.md)

Used to decompress board manifests downloaded from Blossom relays.

---

## Bundled Assets

### Kilter Board layout images

- **Location:** [`androidApp/src/main/assets/board_images/`](androidApp/src/main/assets/board_images/)
- **Files:** `board_7.webp`, `board_8.webp`, `board_10.webp`, `board_14.webp`, `board_27.webp`, `board_28.webp`
- **Source:** extracted from the Kilter Board app's own data files
  (`product_sizes.image_filename`) and re-encoded as WebP.
- **Rights holder:** Kilter Grips, LLC (formerly Aurora Climbing)
- **Basis for bundling:** referential / interoperability use under
  § 23(1) No. 3 MarkenG (DE) and analogous nominative fair-use doctrines —
  see [`LEGAL.md`](LEGAL.md#bundled-kilter-layout-images).
- **Details:** [`board_images/README.md`](androidApp/src/main/assets/board_images/README.md)
- **Takedown:** Kilter Grips, LLC (or a prior rights holder) may request
  removal or replacement at any time via the contacts in
  [`SECURITY.md`](SECURITY.md). Requests will be handled promptly.

These images are **not** covered by the CruxCoach GPLv3 source license.

---

## Notes for Maintainers

When vendoring a new third-party source tree:

1. Place sources under a dedicated directory (e.g. `androidApp/src/main/cpp/<name>/`).
2. Copy the upstream `LICENSE` (and any other required notice files) verbatim
   into that directory.
3. Add a `README.md` documenting upstream URL, version, import date, and
   license election (if dual-licensed).
4. Add an entry to this file linking to the texts and stating the election.
5. Add an entry to [`NOTICE`](NOTICE) if attribution is required by the license.

When bundling a third-party asset (image, font, model, dataset):

1. Keep an attribution entry in this file with source, license, and any
   restrictions on redistribution.
2. If the license requires it, surface the attribution in the app's about /
   credits screen.
