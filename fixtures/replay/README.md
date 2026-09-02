# Replay conformance fixtures

These traces are synthetic and contain no person or physical-device recordings.

- `legacy-gravity-threshold-v1.mge.jsonl` covers equal timestamps, sequence gaps, a long virtual-time gap, both legacy gesture directions, and deterministic prediction IDs.
- `legacy-gravity-threshold-v1.expected.jsonl` is the shared ordered prediction result used by Swift and Kotlin.
- `legacy-gravity-threshold-v1.with-predictions.mge.jsonl` proves those generated records form a schema-valid detector stream when included in a derived trace with updated header/footer declarations.
- `empty.mge.jsonl` is a finalized-complete trace with no samples.

Every trace fixture is also validated by the platform-neutral Motion Trace v1 validator.
