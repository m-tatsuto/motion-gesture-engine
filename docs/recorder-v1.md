# MotionTraceRecorder v1

The Swift `MotionGestureRecorder` and Kotlin `motion-gesture-recorder` modules implement the same bounded, transport-free recorder contract. They serialize Motion Trace v1 JSONL incrementally and never retain the trace body as an in-memory collection.

## Boundary

The recorder accepts canonical device-frame samples and explicit annotations. Platform sensor access, coordinate conversion, consent UI, authentication, upload, retention, and user-facing feedback remain outside this module. Recorder production sources do not import an HTTP, analytics, authentication, or cloud-storage client.

The `MotionGestureCoreMotion` and `motion-gesture-android-sensors` modules provide the platform adapters described in Issues #6 and #7. Platform adapters append callbacks through injectable driver contracts used by deterministic tests.

## Lifecycle

| State | Operation | Result |
| --- | --- | --- |
| `idle` | `start` | Validates metadata and bounds, creates a partial output, writes the header, and enters `recording`. |
| `recording` | append sample, annotation, display change, or capability change | Streams one JSONL record or reports a rejection. |
| `recording` | `finish` | Writes a `complete` or reached-bound footer and commits. |
| `recording` | `cancel` | Writes a `cancelled` footer and commits a finalized-incomplete trace. |
| `recording` | source failure | Writes a `failed` footer and commits a finalized-incomplete trace when the writer still works. |
| `finalizing` | internal only | Flushes the footer and performs the commit. |
| `finished`, `cancelled`, or finalized `failed` | repeated `finish` or `cancel` | Returns the existing terminal result without writing another footer. |
| writer `failed` | any finalization | Returns the stored `ioFailure`; the destination is never committed. |

Each recorder instance owns exactly one trace.

## Bounds

All recorder-created headers include `recorderLimits`:

- `maximumDurationNs`
- `maximumSamples`
- `maximumBytes`

Every value is positive and wire-safe. The byte limit covers the complete uncompressed UTF-8 JSONL container, including the header, every LF byte, and the footer. Before recording begins, the implementation reserves enough space for worst-case bounded footer counters for the declared capabilities and drop reasons. A configuration too small for its header and footer fails before a destination appears.

A sample accepted exactly at the duration or sample-count boundary is recorded and then finalizes the trace as `bounded`. A sample beyond the duration, sample, or available byte budget is not written. Duration takes precedence when one accepted sample reaches both duration and sample-count bounds.

## Streaming and atomic finalization

The file output creates a uniquely named `.partial` file in the destination directory. It writes synchronously, flushes the complete footer, and then performs a same-directory atomic move to the requested `.mge.jsonl` destination. The destination does not exist before commit, and an existing destination is never overwritten.

If a body write fails, the recorder closes and preserves the partial file for diagnosis without adding a footer or moving it to the destination. Backpressure on a sample drops that sample and increments `writerBackpressure`; backpressure on a required header, annotation, or footer is an `ioFailure` because those records must not disappear silently.

## Sample acceptance and diagnostics

The recorder checks each sample before encoding:

- timestamp and sequence are non-negative wire-safe integers;
- accepted sequences strictly increase, source-assigned gaps (including rejected observations before the first accepted sample) are preserved, and timestamps never move backward;
- at least one signal is present and every number is finite;
- every observation references an available capability with the matching signal kind;
- attitude has a declared reference frame and a quaternion within the v1 norm tolerance.

Rejected samples do not advance ordering or observed-timing state. The footer aggregates bounded counters for `malformed`, `nonMonotonicTimestamp`, `bufferOverflow`, `writerBackpressure`, `limitReached`, `sourceFailure`, `unsupported`, and `other`. Source adapters can report their own dropped samples explicitly; the recorder never converts a missing sample into a zero vector.

Observed timing is derived only from accepted observations, independently for each declared capability. Runtime availability changes update subsequent sample validation without rewriting the immutable header. Display-rotation and capability-change timestamps cannot move behind an already written record, and each change record type has a strictly increasing sequence. Annotation shape, provenance, interval direction, UUID, and privacy declarations are validated before writing.

## Current exclusions

V1 currently writes uncompressed JSONL with `sampleReordering.kind = none`. Gzip wrapping, bounded reorder buffers, uploads, and feedback transport are separate layers and must not be inferred from a successful local recording.
