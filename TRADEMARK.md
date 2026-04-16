# CruxCoach Trademark Policy

The CruxCoach **name** and **logo** (collectively, "the Marks") identify the
upstream project maintained at https://codeberg.org/CruxCoach/CruxCoach.

The source code is licensed under the GNU General Public License v3.0 — you
are free to fork, modify, and redistribute it under those terms. **The Marks
are not part of that grant.** This document explains what you can do with the
Marks without asking, and what requires permission first.

The policy is loosely modeled on Mozilla's Firefox / Iceweasel arrangement
and Mastodon's fork-friendly trademark approach.

---

## What you may do without asking

- **Refer to the project by name** in blog posts, documentation, comparisons,
  tutorials, social media, talks, podcasts. Discussion is unrestricted.
- **Distribute unmodified APKs** built from a tagged release in this
  repository. The Marks identify the same software, so the same name fits.
- **Build the app for personal or educational use**, including with local
  modifications, and share the result with friends, classmates, or training
  partners.
- **Display the logo when linking to the upstream project**, with a clear
  link back to the canonical repository or the Zapstore listing.
- **Take screenshots** of the app for any non-commercial purpose.

## What requires asking first

- **Distributing a modified APK under the CruxCoach name and icon** to a wide
  audience (Zapstore, third-party app stores, public download links, file
  shares posted to climbing communities). Modifications can change BLE
  behavior with climbing hardware in ways users cannot tell apart from
  upstream — the Marks must not become ambiguous about origin.
- **Using the name or logo in a derivative product name** (e.g.
  "CruxCoach Pro", "CruxCoach for X"), in commercial branding, or in any
  context that suggests endorsement or affiliation with the upstream project.
- **Selling merchandise** that uses the Marks (stickers, shirts, prints).
- **Using the Marks in advertising** for commercial products or services.

## Forking

Forks are welcome and explicitly encouraged — that is what GPLv3 is for. Two
practical rules cover the trademark side:

1. **If you ship modified binaries to a wide audience, rename your fork.**
   Pick a different name and a different launcher icon. You may (and we
   encourage you to) say "based on CruxCoach" in your README, About screen,
   or release notes — that kind of attribution is welcome.
2. **If you only fix bugs or add a feature you intend to upstream**, you may
   keep the name during development. Once you publish the modified build to
   others, rule 1 applies.

For convenience, the maintainer-bound constants that should be replaced when
you rebrand are documented in [`CONTRIBUTING.md`](CONTRIBUTING.md) under
"Customizing for forks" — including donation addresses, the Nostr
maintainer pubkey, and the Zapstore signing identity.

## Enforcement

This policy is enforced through requests, not lawsuits. If something seems
off, the maintainer will reach out politely and ask you to adjust before
escalating further. Personal and educational use will never be challenged.

## Questions

Open an issue on https://codeberg.org/CruxCoach/CruxCoach/issues or send a
Nostr DM to the maintainer pubkey published in `NostrConfig.kt` /
`zapstore.yaml`.
