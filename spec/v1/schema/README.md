# JSON Schemas

These JSON Schema 2020-12 documents define Motion Trace v1 draft records and the evaluator's aggregate report.

`motion-trace-record.schema.json` is the union used for each JSON Lines value. `motion-trace.schema.json` describes the equivalent logical in-memory envelope; it is not the serialized container.

`motion-evaluation-report.schema.json` is the closed JSON contract emitted by `motion-eval`. It contains aggregate metrics and intentionally has no trace, event, annotation, path, exact-model, or OS-version field.

`golden-fixture-manifest.schema.json` defines the shared synthetic trace,
prediction, evaluator-report, provenance, review, and detector identity index
used by the v0.1 parity gate.

JSON Schema validates record shape. Use [`tools/validate-trace.mjs`](../../../tools/validate-trace.mjs) for container order, cross-record references, timing, counts, privacy declarations, finalization, and gzip validation.
