# Motion Trace wire format v1

| Field | Value |
| --- | --- |
| Identifier | `mge-schema-1.0.0-draft.1` |
| JSON Schema dialect | [JSON Schema 2020-12](https://json-schema.org/draft/2020-12) |
| Uncompressed suffix | `.mge.jsonl` |
| Compressed suffix | `.mge.jsonl.gz` |
| Normative semantics | [Motion Gesture Engine core specification v1](core.md) |

## 1. Scope

This document defines the transport-free interchange format shared by recorders, replay, and evaluators. Upload endpoints, authentication, remote storage, retention, and application feedback text are outside this format.

The schemas are closed contracts. Account identifiers, advertising identifiers, device serial numbers, precise or coarse location, audio, video, arbitrary application logs, and unbounded free text have no v1 fields and MUST NOT be inserted into another string field.

## 2. Container

A Motion Trace is UTF-8 JSON Lines:

1. Each line contains exactly one JSON object.
2. Blank lines and a byte-order mark are forbidden.
3. Lines are separated by LF (`0x0a`). A final LF is REQUIRED.
4. The first record is exactly one `traceHeader`.
5. The last record is exactly one `traceFooter`.
6. No record follows the footer.

The uncompressed filename suffix is `.mge.jsonl`. A recorder MAY wrap the complete byte stream in one [RFC 1952](https://www.rfc-editor.org/info/rfc1952) gzip member and use `.mge.jsonl.gz`. A reproducible encoder sets gzip modification time to zero and omits original filename and comment fields. A decoder validates the gzip trailer and treats truncation or trailing non-gzip data as an I/O failure.

JSON numbers used for integer time, sequence, and count fields are limited to `0...9007199254740991`. This is the largest exactly interoperable integer in IEEE-754 binary64 and covers more than 104 days at nanosecond resolution. Native APIs MAY use signed 64-bit wrappers internally, but a producer MUST reject a value outside the wire range.

## 3. Record order and finalization

The allowed line records are:

- `traceHeader`
- `sample`
- `annotation`
- `predictedEvent`
- `displayRotationChange`
- `capabilityChange`
- `traceFooter`

Samples MUST be ordered lexicographically by `(timestampNs, sequence)`. Sample sequence values are unique within a trace. Predicted events MUST be ordered by `(timestampNs, eventSequence)` within each detector stream. Annotation records may be appended after collection and are not required to be serialized in annotation time order.

The footer is the commit marker:

- Valid footer with `complete` or `bounded`: `finalizedComplete`
- Valid footer with `cancelled` or `failed`: `finalizedIncomplete`
- Missing, truncated, invalid, or non-final footer: `unfinalized`

Only `finalizedComplete` traces are eligible for normal replay and evaluation. A tool MAY inspect a finalized-incomplete or unfinalized artifact for diagnostics, but MUST NOT silently treat it as a complete dataset item.

Footer record counts, reordered- and dropped-sample totals, observed timing summaries, references, and ordering are semantic constraints. JSON Schema validates individual shapes; the reference validator validates these cross-record constraints.

## 4. Header contract

The header freezes the session descriptor before samples:

- Exact core and wire schema versions
- Privacy tier and declared data classes
- Producer and platform-adapter versions
- Canonical units, frames, quaternion direction, and monotonic-time semantics
- Bounded sample-reordering policy, or an explicit declaration that no reordering occurs
- Recorder duration, accepted-sample, and uncompressed-byte limits when a recorder produced the trace
- Initial display rotation and frozen gesture-frame transform
- Initial sensor capabilities and requested timing
- Optional detector stream descriptors
- Optional minimized device metadata

The required `conventions` constants make a trace self-describing even when it is read outside Swift or Kotlin. A consumer MUST validate them; it MUST NOT assume local defaults.

`traceId` is a newly generated opaque UUID. It MUST NOT be derived from an account, installation, advertising identifier, hardware identifier, device name, or wall-clock timestamp.

Capabilities have stable `capabilityId` values. Every capability declares whether it was required or optional and whether rotation rate is raw or bias-corrected. Every signal observation references a compatible capability. Requested timing is configuration intent; observed timing in the footer is derived from accepted sample timestamps.

`recorderLimits`, when present, records positive `maximumDurationNs`, `maximumSamples`, and `maximumBytes` values. `maximumBytes` covers the complete uncompressed JSONL container, including header, final LF, and footer. A recorder MUST enforce the same values it serializes; readers do not infer missing limits for traces produced by another tool.

`nativeSourceIdentifier`, `nativeModeIdentifier`, and platform-defined unit or sign identifiers are namespaced adapter constants. They MUST NOT contain a runtime hardware name, vendor string, serial number, or other device-derived identifier.

## 5. Samples and source state

A `sample` contains one or more entries in `signals`. Omitted signals are absent, not zero. Independent Android sensor callbacks remain independent partial samples. One coherent Core Motion device-motion callback may contain multiple signals.

Each signal observation contains:

- `capabilityId`
- Canonical value
- Optional normalized and native accuracy information

`displayRotationChange` and `capabilityChange` records preserve runtime changes without mutating the header. Stored sample vectors remain in device frame `D`; a display change never rewrites earlier or later vectors silently.

Dropped-sample diagnostics distinguish malformed input, non-monotonic time, buffer overflow, writer backpressure, reached limits, source failure, unsupported capabilities, and an explicit fallback category. A producer MUST NOT silently map an unsupported capability to a supported signal.

## 6. Annotations and predictions

Annotation provenance is one of:

- `synthetic`
- `contributor`
- `userReport`
- `reviewedGroundTruth`

A `userReportedProblem` annotation carries a controlled problem code and optional generic expected or observed gesture. It is evidence, not a canonical `truePositive`, `falsePositive`, or `falseNegative` label. Those are evaluator results and have no annotation fields.

Free-form feedback text stays in the integrating application's private feedback system. It is not embedded in a Motion Trace.

A predicted event references an immutable detector stream declared in the header. Detector identifier, version, and configuration identity therefore remain stable for the whole stream.

## 7. Privacy tiers

| Tier | Meaning | Publication rule |
| --- | --- | --- |
| `synthetic` | Generated data with no human recording | May be published after fixture review |
| `privateSensitive` | Raw or minimally processed human recording | MUST remain private |
| `reviewedSanitized` | Human recording reviewed under a named protocol | Still private unless separately approved |
| `publicApproved` | Sanitized data explicitly approved for public release | May be proposed as a public fixture |

Every trace declares `motionSensorData`. It additionally declares `gestureAnnotation`, `userReport`, `exactDeviceModel`, or `osMajorVersion` when those fields occur. An exact device model is optional and is represented next to the fixed privacy class `quasiIdentifier.exactDeviceModel`; it is never a required detector input.

The schema intentionally permits only an OS major version. It has no account, device name, serial, advertising identifier, location, media, or general log field. `additionalProperties: false` is applied throughout v1 records.

## 8. Compatibility

Every line is validated against the schema version declared by the header. V1 readers:

- MUST reject an unsupported schema major version.
- MUST reject an unknown required record or convention.
- MUST NOT validate a newer document against an older schema and guess at unknown fields.
- MAY accept a newer minor version only after loading that version's schema and confirming that its added features are optional.
- SHOULD preserve unknown optional fields when a version-aware library performs a lossless read-modify-write operation.

The v1 draft schemas are deliberately closed. Extensibility comes from a versioned schema release, not arbitrary extension bags.

## 9. Schemas and validator

Published schemas:

- [`motion-sample.schema.json`](schema/motion-sample.schema.json)
- [`annotation.schema.json`](schema/annotation.schema.json)
- [`predicted-event.schema.json`](schema/predicted-event.schema.json)
- [`motion-trace-header.schema.json`](schema/motion-trace-header.schema.json)
- [`motion-trace-footer.schema.json`](schema/motion-trace-footer.schema.json)
- [`motion-trace-record.schema.json`](schema/motion-trace-record.schema.json)
- [`motion-trace.schema.json`](schema/motion-trace.schema.json), the logical materialized envelope

Validate the conformance fixture suite with:

```sh
npm ci
npm run validate:fixtures
```

Validate an uncompressed or gzip trace with:

```sh
npm run validate:trace -- path/to/trace.mge.jsonl
```

The validator emits a stable code and one of `finalizedComplete`, `finalizedIncomplete`, or `unfinalized`. Exit code `0` means structurally and semantically valid, including a valid footer. Exit code `1` means invalid. Exit code `2` identifies an otherwise readable artifact that is unfinalized.

The reference validator processes records incrementally and applies defensive defaults of 1 MiB per JSON line, 256 MiB after decompression, and 5,000,000 body records. Integrations SHOULD set lower bounds appropriate to their recorder configuration.

## 10. References

- [JSON Schema 2020-12](https://json-schema.org/draft/2020-12)
- [JSON Lines conventions](https://jsonlines.org/)
- [RFC 8259: The JavaScript Object Notation (JSON) Data Interchange Format](https://www.rfc-editor.org/info/rfc8259)
- [RFC 1952: GZIP file format specification](https://www.rfc-editor.org/info/rfc1952)
