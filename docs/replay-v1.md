# Deterministic Replay v1

`MotionGestureReplay` and `motion-gesture-replay` implement the same synchronous, deterministic replay contract for finalized-complete Motion Trace v1 data. Replay has no sensor, UI, network, wall-clock, timer, or sleep dependency.

## Boundary

Replay accepts a complete JSONL trace, validates replay-critical container and semantic invariants, and retains the ordered samples under explicit memory limits. It does not collect motion, modify the source trace, evaluate correctness, upload data, or map generic gestures to application actions.

Existing annotations and prediction records are validated as part of the source container but are not delivered to the detector and are not copied into the new run result. Only ordered `sample` records drive replay.

The platform-neutral schema validator remains the conformance authority. The native loaders independently reject malformed containers, unsupported schema or core-spec versions, non-monotonic samples, footer mismatches, and incomplete traces so callers receive typed failures instead of partially replayed data.

Native replay currently accepts uncompressed UTF-8 JSONL. Gzip input returns `unsupportedCompression`; an integration must decompress it into a bounded byte buffer before loading.

## Minimal use

Kotlin:

```kotlin
val replay = ReplayMotionSource(LegacyGravityThresholdV1ReplayDetector())
replay.load(tracePath)
val result = replay.run()
```

Swift:

```swift
let replay = ReplayMotionSource(detector: LegacyGravityThresholdV1ReplayDetector())
try replay.load(url: traceURL)
let result = try replay.run()
```

Advanced detectors implement `MotionReplayDetector`; they receive the fixed session descriptor at start and every partial `MotionSample` in source order.

## Virtual time and ordering

- `timestampNs` is the only replay clock.
- `step()` consumes exactly one sample and returns that sample, its virtual timestamp, and every prediction emitted while consuming it.
- `run()` repeatedly performs the same operation without throttling or sleeping.
- Equal timestamps remain distinct steps and preserve `sequence` order.
- Sequence gaps are preserved.
- Long timestamp gaps do not create samples, callbacks, delays, or interpolation.
- An empty finalized trace starts and stops the detector cleanly and returns no predictions with no current virtual timestamp.

The source validates lexicographic `(timestampNs, sequence)` order before the detector starts. It never sorts malformed input into an apparently valid trace.

## Detector contract

`MotionReplayDetector` owns an immutable `MotionDetectorDescriptor` and implements `start`, `consume`, `stop`, and `reset`. `reset` must remove all state from the previous run.

`LegacyGravityThresholdV1ReplayDetector` is the reference adapter. For each sample containing gravity, it applies the trace's frozen row-major `gestureFrameFromDevice` matrix and passes the resulting gesture-frame gravity `z` value to the immutable baseline. Samples without gravity are still replayed as steps but do not invent a zero-gravity observation.

## Lifecycle

```text
idle --load--> ready --step/run--> replaying --> finished
                         |              |
                         +--cancel------> cancelled

finished/cancelled --reset--> ready
load or detector failure ------> failed
```

`reset()` reuses the already validated trace, resets the detector, clears predictions, and returns to the first sample. A failed source is terminal.

## Prediction records

Each detector event becomes a Motion Trace v1 `predictedEvent` record containing:

- the detector stream identifier;
- a zero-based event sequence;
- detector-provided virtual timestamp and source-sample sequence;
- a generic gesture;
- a deterministic UUIDv8 event identifier.

The UUID is derived from `traceId`, `detectorStreamId`, and `eventSequence` using two specified FNV-1a 64-bit passes with the salts `mge.replay.event.v1.a|` and `mge.replay.event.v1.b|`. Version and variant bits are then set to UUIDv8 and RFC 9562 variant values. It is a stable record identifier, not a security primitive.

`ReplayRunResult` exposes the versioned detector descriptor and ordered records and can encode the records as UTF-8 JSONL. Repeated encoding by one implementation is byte-stable; Swift and Kotlin results are semantically identical. Prediction-only JSONL is not itself a complete Motion Trace. A derived full trace must declare the descriptor in its header and update its footer counts before including those records.

If a derived trace already contains the same detector stream, the integration must replace that stream rather than append a second copy with duplicate deterministic IDs and sequences.

## Failure and resource policy

Errors expose a stable code, lifecycle stage, diagnostic, and optional line number. Important codes include `unsupportedSchemaVersion`, `unsupportedSpecVersion`, `incompleteTrace`, `nonMonotonicSample`, `footerMismatch`, and `detectorFailure`.

Default native limits are 64 MiB per input trace, 1 MiB per JSON line, and 1,000,000 body records. Callers may choose lower positive limits. Recorder-declared duration, sample, and byte limits are also rechecked when present.

## Shared parity fixtures

Swift and Kotlin use the same fixtures under `fixtures/replay` to cover equal timestamps, sequence gaps, a multi-hour virtual gap, both gesture directions, reset determinism, empty input, truncation, unknown versions, malformed records, and non-monotonic samples. The fixtures are synthetic and also pass the platform-neutral Motion Trace validator.
