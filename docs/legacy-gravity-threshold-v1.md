# LegacyGravityThresholdV1

`LegacyGravityThresholdV1` is the immutable comparison baseline for the detector behavior that existed before this repository. It is intentionally simple and is not a recommendation for a future production detector.

## Immutable identity

| Field | Value |
| --- | --- |
| Detector ID | `LegacyGravityThresholdV1` |
| Detector version | `1.0.0` |
| Configuration identity | `legacy.default.v1` |
| Trigger magnitude | exactly `0.65` |
| Re-arm magnitude | exactly `0.35` |

The configuration has no public threshold initializer. Any threshold, comparison, state-transition, or event-mapping change requires a new detector identifier.

## Input and output

The pure Swift and Kotlin cores consume one `GestureFrameGravitySample` at a time. Its `gravityZG` value is physical gravity in standard-gravity units, already transformed into the session's frozen gesture frame. Platform sign conversion, unit conversion, sensor access, scheduling, and application action mapping stay outside this detector.

An emitted `PredictedGestureEvent` copies the source sample's monotonic `timestampNs` and `sequence`, and contains one generic gesture:

- `gravityZG > 0.65` emits `tiltForward` while armed.
- `gravityZG < -0.65` emits `tiltBackward` while armed.

Values exactly equal to either trigger threshold do not emit an event.

## State and lifecycle

The detector begins in `idle` and armed.

| Operation | Required state | Result |
| --- | --- | --- |
| `start` | `idle` or `stopped` | Enters `running` with a fresh armed state. |
| `consume` | `running` | May emit at most one event for that sample. |
| `stop` | `running` | Enters `stopped` and restores the armed state. |
| `reset` | any | Returns to the initial `idle`, armed state. |

An operation in another state fails with code `invalidState`. After an event, all samples are ignored until `abs(gravityZG) < 0.35`. A value exactly equal to `+0.35` or `-0.35` does not re-arm. A sample that re-arms does not emit an event.

## Characterization parity

Both implementations execute [`fixtures/characterization/legacy-gravity-threshold-v1.csv`](../fixtures/characterization/legacy-gravity-threshold-v1.csv). This synthetic detector script covers positive and negative trigger boundaries, both sides of the re-arm boundary, suppression while disarmed, stop/start, and reset/start. It is a compact characterization input, not a Motion Trace v1 interchange container.

## Deliberately preserved limitations

This implementation adds no smoothing, debounce duration, calibration, confidence, cooldown, trajectory analysis, or use of other motion signals. It reacts to one crossing sample and does not read a clock. These limitations are measured against this baseline rather than fixed inside it.
