# Motion Trace v1 conformance fixtures

All fixtures in this directory are synthetic. They contain no recordings or identifiers from a person or physical device.

- `valid/minimal.mge.jsonl` is the smallest finalized-complete trace.
- `valid/full.mge.jsonl` exercises every record family, controlled user-report evidence, device-metadata classification, bounded reordering, and a bounded finalization.
- `valid/cancelled.mge.jsonl` is structurally valid but classified `finalizedIncomplete`.
- `invalid/*.mge.jsonl` each violate one named schema or semantic rule.
- `invalid/recorder-limit-mismatch.mge.jsonl` exceeds its declared accepted-sample bound.
- `invalid/forbidden-fields.cases.json` proves that identity, location, media, and unrelated log fields are rejected.
- `invalid/container.cases.json` covers UTF-8, LF, BOM, blank-line, and gzip-integrity failures.

`manifest.json` is the machine-readable expectation list used by the reference validator. It also includes the synthetic finalized traces under `fixtures/replay` so replay inputs and derived prediction streams remain part of schema conformance testing.
