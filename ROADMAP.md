# Roadmap

The v0.1 roadmap establishes measurement before changing the detector.

## Milestone: v0.1 Measurement Foundation

| ID | Work item | Depends on | Definition of done |
| --- | --- | --- | --- |
| [OSS-01](https://github.com/m-tatsuto/motion-gesture-engine/issues/2) | Freeze v1 terminology, coordinate systems, time, lifecycle, and non-goals | - | Normative definitions cover device and gesture frames, orientation, units, monotonic time, lifecycle, errors, and v0.1 exclusions. |
| [OSS-02](https://github.com/m-tatsuto/motion-gesture-engine/issues/3) | Define MotionSample, MotionTrace, and Annotation v1 schemas | OSS-01 | Versioned JSON Schemas, compatibility rules, validation fixtures, and privacy tiers are documented. |
| [OSS-03](https://github.com/m-tatsuto/motion-gesture-engine/issues/4) | Freeze the current detector as LegacyGravityThresholdV1 | OSS-01, OSS-02 | Pure Swift and Kotlin implementations reproduce the documented threshold and re-arm behavior on characterization traces. |
| [OSS-04](https://github.com/m-tatsuto/motion-gesture-engine/issues/5) | Implement MotionTraceRecorder and a safe streaming writer | OSS-02 | Recording is bounded, transport-free, crash-aware, atomic on finalize, and reports dropped samples. |
| [OSS-05](https://github.com/m-tatsuto/motion-gesture-engine/issues/6) | Add the Core Motion recorder adapter | OSS-04 | The adapter records supported fields, capabilities, orientation, and observed timing without UI dependencies. |
| [OSS-06](https://github.com/m-tatsuto/motion-gesture-engine/issues/7) | Add the Android Sensors recorder adapter | OSS-04 | The adapter records sensor source, accuracy, event timing, capabilities, and normalized coordinates without UI dependencies. |
| [OSS-07](https://github.com/m-tatsuto/motion-gesture-engine/issues/8) | Implement deterministic ReplayMotionSource | OSS-02, OSS-03 | Step and fast replay use virtual time, never require wall-clock sleeping, and emit repeatable predictions. |
| [OSS-08](https://github.com/m-tatsuto/motion-gesture-engine/issues/9) | Implement event matching and the motion-eval CLI | OSS-02, OSS-07 | Reports include TP, FP, FN, precision, recall, F1, false positives per exposure, and latency percentiles in JSON and Markdown. |
| [OSS-09](https://github.com/m-tatsuto/motion-gesture-engine/issues/10) | Add golden traces and Swift/Kotlin parity CI | OSS-03 through OSS-08 | Schema, replay, prediction, and metric results are regression-tested across both platforms. |

## Dependency flow

```text
OSS-01 -> OSS-02 -> OSS-04 -> OSS-05
                    |          OSS-06
                    +-> OSS-07 -> OSS-08 -> OSS-09
OSS-01 -> OSS-03 -------^--------------------^
```

## v0.1 release gate

- Specifications are versioned and validated.
- The legacy baseline is reproducible, not silently improved.
- Recorder output contains no wall-clock identity requirement and no network behavior.
- Replay is deterministic and does not sleep.
- Evaluation is event-based; sample-level accuracy is not used as the primary score.
- False positives are reported per session and per unit of negative exposure.
- True negatives and specificity are reported only when negative windows are explicitly defined.
- Raw user recordings are absent from the public repository.
- No new production detector, calibration system, or ML model is included.

## Later work

Filtering, calibration, confidence, adaptive thresholds, device profiles, new gesture algorithms, and ML classification require a separate post-v0.1 roadmap. They are intentionally not tracked as part of this milestone.
