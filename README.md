# Motion Gesture Engine

Cross-platform foundations for recording, replaying, and evaluating mobile motion gestures on iOS and Android.

> [!IMPORTANT]
> This project is in its measurement-foundation phase. It does not yet provide a production-ready gesture detector.

## Goal

Build a reproducible foundation for mobile motion gesture recognition that can be evaluated across different devices without accumulating model-specific magic numbers.

The first milestone deliberately stops at:

```text
Specification -> Legacy baseline -> Recorder -> Replay -> Evaluator
```

## v0.1 scope

- Versioned motion sample, trace, and annotation specifications
- An immutable reference implementation of the current gravity-threshold behavior
- Bounded, streaming motion recorders for Core Motion and Android Sensors
- Deterministic replay driven by recorded monotonic timestamps
- Event-based evaluation with machine-readable and human-readable reports
- Synthetic and reviewed golden traces for cross-platform parity tests

## Explicit non-goals for v0.1

- A new production gesture algorithm
- Filtering, confidence scoring, or adaptive calibration
- Per-device threshold tables or device profiles
- Machine-learning classification
- Background or automatic telemetry upload
- App-specific meanings such as `correct` or `pass`

The library uses generic gestures such as `tiltForward` and `tiltBackward`. Applications own the mapping from those gestures to product actions.

## Design boundaries

The open-source core is transport-free. It can write and read motion traces, but it does not upload them.

```text
Platform sensor
    -> explicit coordinate/unit normalization
    -> MotionSample
    -> Recorder / detector under test
    -> MotionTrace / predicted events
    -> Replay
    -> Evaluator
```

Consent, authentication, upload endpoints, private storage, retention, and user feedback belong to the integrating application. Raw user-submitted traces must never be published automatically.

See the [Core specification v1](spec/v1/core.md), [Wire format v1](spec/v1/wire-format.md), [Design principles](docs/design-principles.md), [Legacy baseline](docs/legacy-gravity-threshold-v1.md), [Recorder contract](docs/recorder-v1.md), [Core Motion adapter](docs/core-motion-recorder-adapter.md), [Android Sensors adapter](docs/android-sensors-recorder-adapter.md), [Replay contract](docs/replay-v1.md), and the [Roadmap](ROADMAP.md). Work is tracked in the [v0.1 epic](https://github.com/m-tatsuto/motion-gesture-engine/issues/1) and [Measurement Foundation milestone](https://github.com/m-tatsuto/motion-gesture-engine/milestone/1).

## Module layout

```text
spec/                         JSON Schemas and normative documentation
swift/                        Swift Package
  MotionGestureCore
  MotionGestureRecorder
  MotionGestureCoreMotion
  MotionGestureReplay
android/                      Kotlin/Android libraries
  motion-gesture-core
  motion-gesture-recorder
  motion-gesture-android-sensors
  motion-gesture-replay
tools/motion-eval/            Platform-neutral evaluator CLI
fixtures/                     Synthetic and reviewed golden traces
```

The Swift and Kotlin core modules contain the immutable `LegacyGravityThresholdV1` baseline. Their recorder modules stream bounded Motion Trace v1 JSONL to atomic local outputs. The iOS `MotionGestureCoreMotion` module records fused device motion through an injectable, UI-independent adapter. The Android `motion-gesture-android-sensors` module records independent SensorManager callbacks through the same kind of injectable boundary. The replay modules load finalized-complete traces, advance only by virtual monotonic time, and emit deterministic predicted-event records. The evaluator remains a later v0.1 roadmap item.

## Library development

Run the Swift and Kotlin tests from the repository root:

```sh
swift test --package-path swift
./android/gradlew -p android test
```

Both core suites execute the same synthetic detector characterization fixture. Recorder tests use identical scenarios for normal completion, bounds, cancellation, malformed and unsupported input, backpressure, writer failure, and injectable sources. Core Motion and Android Sensors tests use fake drivers to verify availability, timestamp conversion, orientation ordering, requested-versus-observed timing, quaternion transforms, and source failure without platform hardware. Replay tests use the same finalized traces and expected prediction records on Swift and Kotlin, including equal timestamps, sequence gaps, long virtual-time gaps, reset, empty traces, and typed load failures.

## Privacy

Motion traces can reveal behavior and device characteristics even when they contain no obvious account identifier. Recordings must therefore be bounded, purpose-specific, and reviewed before publication.

Do not open a pull request containing raw user recordings. Use synthetic traces or explicitly reviewed and sanitized fixtures only.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security concerns should be reported as described in [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
