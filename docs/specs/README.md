# Feature specifications

[Documentation index](../README.md) · [Canonical FEAT registry](INDEX.md)

Specs preserve design intent and decisions. The registry is the single
navigation/status table; this page deliberately does not duplicate it.

## How to read a spec

A version directory is its historical **target**, not evidence it shipped.
Frontmatter such as `skeleton`, `ready` or `implementation` often records the
state when the document was written. Compare with the registry, current code
and actual release artifacts before making a delivery claim.

For this audit (2026-09-10), 0.2.2 is published and 0.2.3 is in preparation.
Competition, BoardCell/FIPS, separate key-custody and personal-sharing work
were not integrated. Their retained contracts remain useful proposals and
experimental records, not acceptance criteria already passed by this build.

## Finding and changing designs

- Start at [INDEX.md](INDEX.md); reserve IDs there in the same commit as a new
  spec, after checking unmerged work. Known collisions require a full path
  in references, not just `FEAT-NNN`.
- Follow a feature's linked protocol, conformance and decision documents.
  Preserve unresolved decisions and provenance when a target changes.
- Keep established file paths where possible. Mark superseded/archived
  material explicitly; do not silently erase an unresolved design.
- Distinguish **implemented on a branch**, **published**, **proposed** and
  **historical** using the [documentation status vocabulary](../README.md).
- Update the registry when implementation or publication is confirmed;
  do not mark an entire specification shipped because a helper class exists.
