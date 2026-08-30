# Design principles

## Measurement before optimization

The current detector is preserved as an immutable baseline before any behavior changes. A replacement is accepted only after it can be replayed against the same traces and compared with the same evaluator.

## Explicit frames, units, and time

Every vector must declare its coordinate frame and unit. Platform adapters may transform platform-native values, but the transformation must be documented and testable.

Trace timestamps are monotonic and relative to trace start. Wall-clock timestamps are not required for replay and should not be recorded by default.

## Deterministic core

Detector logic consumes samples and time explicitly. It must not read a system clock, start a timer, sleep, or depend on callback-thread scheduling. Recorder and platform integration are separate from the detector under test.

## Streaming, bounded recording

Recorders must have explicit duration, sample-count, and byte limits. They report dropped or malformed samples rather than silently hiding incomplete data. Finalization is atomic so a partial file is never mistaken for a complete trace.

The initial interchange format is versioned JSON Lines with an optional reproducible gzip wrapper. The wire specification owns the exact container contract.

## Annotation is not evaluation

A recorder captures observations and annotations such as intended gesture, onset, commit, end, negative window, and annotation source.

`truePositive`, `falsePositive`, and `falseNegative` are evaluator outputs. They are not canonical raw labels. A user report is evidence, not automatically trusted ground truth.

## Event-based evaluation

Gesture detection is evaluated as event matching rather than sample classification.

Primary metrics are:

- True positives, false positives, and false negatives by gesture
- Precision, recall, and F1
- False positives per session and per unit of declared negative exposure
- Detection latency distribution, including p50 and p95

True negatives, specificity, and accuracy are available only when the dataset declares meaningful negative windows. Counting every non-event sensor sample as a true negative would produce misleadingly high accuracy.

## Privacy and transport separation

The OSS library records locally and has no upload endpoint, account identifier, analytics SDK, or background telemetry.

An integrating application may build an opt-in feedback transport around the recorder. That layer owns consent, authentication, abuse controls, private storage, retention, deletion, and user-visible responses. It must not automatically convert raw submissions into public fixtures.

## Cross-platform parity without forced code sharing

Swift and Kotlin implementations share normative schemas, fixtures, and expected results. Source code does not have to be shared across platforms when native implementations provide clearer APIs or more reliable sensor integration.
