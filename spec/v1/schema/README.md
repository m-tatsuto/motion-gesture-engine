# JSON Schemas

These JSON Schema 2020-12 documents define Motion Trace v1 draft records.

`motion-trace-record.schema.json` is the union used for each JSON Lines value. `motion-trace.schema.json` describes the equivalent logical in-memory envelope; it is not the serialized container.

JSON Schema validates record shape. Use [`tools/validate-trace.mjs`](../../../tools/validate-trace.mjs) for container order, cross-record references, timing, counts, privacy declarations, finalization, and gzip validation.
