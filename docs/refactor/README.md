# Cross-platform refactor evidence

This directory is the reviewable contract for the staged Android/KMP/iOS refactor.
It records behavior that must survive UI and architecture changes; it is not a
replacement for executable tests.

- `parity-matrix.json` inventories user-visible capabilities and state contracts.
- `compatibility-matrix.json` inventories every published database origin and
  every currently supported serialized protocol.
- `ui-scenario-matrix.json` defines deterministic rendering axes and evidence
  required for every redesigned core state.
- `ui-slice-review.md` is the mandatory, single-region UI changeset template.
- `ui-ux-tooling-decision.md` records the time-stamped tooling research and
  adopt/spike/defer/reject decisions that gate productive UI work.
- `external-gates.md` records checks that cannot run on this Linux host.

Validate all machine-readable contracts and their referenced fixtures with:

```sh
python3 scripts/validate_refactor_contracts.py
```

Status values are deliberately small and stable: `covered`, `partial`,
`planned`, `hidden-preserved`, and `external-gate`. A refactor may improve a
status, but must not silently remove an entry.
