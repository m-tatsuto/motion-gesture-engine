# Motion Gesture Engine core specification v1

| Field | Value |
| --- | --- |
| Identifier | `mge-core-1.0.0-draft.1` |
| Status | Normative v1 draft for the v0.1 Measurement Foundation |
| Scope | Specification, legacy baseline, recorder, replay, and evaluator |
| Wire schema | Defined separately by [issue #3](https://github.com/m-tatsuto/motion-gesture-engine/issues/3) |

## 1. Conformance language

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **NOT RECOMMENDED**, **MAY**, and **OPTIONAL** in this document are to be interpreted as described by [BCP 14](https://www.rfc-editor.org/info/bcp14) when, and only when, they appear in all capitals.

A Swift implementation, Kotlin implementation, trace producer, trace consumer, replay runner, or evaluator is conforming only when every applicable normative requirement in this document is satisfied.

## 2. Scope and non-goals

This specification defines the semantic contract needed to record the current behavior and measure later behavior reproducibly. It does not select a new detector.

The following are outside v1 Measurement Foundation scope:

- Filtering or smoothing
- Confidence scoring
- Calibration or adaptive thresholds
- Per-model device profiles
- Machine-learning classification
- Background collection or network upload
- Product-specific action names, scoring, and side effects

Those features may consume this contract later, but they MUST NOT change the meaning of v1 samples, frames, time, or events.

## 3. Terms

### 3.1 Source observation

A **source observation** is one callback or poll result produced by a platform sensor API. It uses the platform's native units, axes, timestamp, and sign conventions. It is not yet a `MotionSample`.

### 3.2 MotionSample

A **MotionSample** is one timestamped, canonicalized observation containing at least one supported signal. A sample may be partial. Multiple signals MAY appear in one sample only when the source API reports them as one coherent fused observation.

For example, one iOS `CMDeviceMotion` callback may produce a single sample containing gravity, user acceleration, rotation rate, and attitude. Independent Android gravity and gyroscope callbacks produce separate samples unless an explicit assembler creates a derived fused sample and records its alignment policy.

An omitted signal means **not present in this observation**. It MUST NOT be interpreted as a zero vector.

### 3.3 MotionTrace

A **MotionTrace** is one finalized source session containing its immutable session descriptor, ordered samples, annotations, diagnostics, and completion status.

### 3.4 Annotation

An **annotation** is an observation about a time or interval, such as intended gesture, onset, commit, end, or a declared negative window. Its provenance is part of the annotation.

`truePositive`, `falsePositive`, and `falseNegative` are evaluator results. They are not raw annotation kinds.

### 3.5 Predicted event

A **predicted event** is an event emitted by a detector under test. It identifies a generic gesture, detector version, event timestamp, and optional detector diagnostics.

### 3.6 Session

A **session** is one uninterrupted source/recorder timeline with one time origin, one sequence origin, and one fixed gesture frame. Restarting a source creates a new session.

## 4. Mathematical conventions

Vectors are column vectors. Frames are right-handed, orthonormal bases.

`R_B_from_A` denotes the 3-by-3 rotation matrix that transforms vector coordinates from frame `A` into frame `B`:

```text
v_B = R_B_from_A * v_A
```

Rotations use the right-hand rule. Matrix multiplication order MUST be explicit; implementations MUST NOT rely on platform naming such as "portrait", "landscapeRight", or `ROTATION_90` without mapping it to this contract.

Floating-point signals MUST be finite. `NaN`, positive infinity, and negative infinity are invalid sample values.

## 5. Coordinate frames

### 5.1 Device frame `D`

The device frame is fixed to the physical device body:

- `+x_D` points toward the physical screen-right edge in the platform's documented default device orientation.
- `+y_D` points toward the physical screen-top edge in that orientation.
- `+z_D` points out through the front display surface.

This is a right-handed frame. Its axes do not rotate when UI orientation changes.

On Android, "default" means the device's natural orientation, which may be landscape on a tablet. An adapter MUST NOT assume portrait. On supported handheld Apple devices, the adapter uses the axes documented by `CMMotionManager`.

V1 canonical `MotionSample` vectors MUST be stored in `D`. Platform sign and unit normalization happens before a source observation becomes a sample. Changing the stored frame requires a new core-spec major version; a wire schema MUST NOT redefine it.

### 5.2 Display frame `S`

The display frame represents the currently visible logical display:

- `+x_S` points to the current logical screen right.
- `+y_S` points to the current logical screen top.
- `+z_S` points out through the front display surface.

`displayRotationClockwise` is the clockwise rotation of drawn graphics from the device's default orientation, as viewed from the front. Its allowed values are `0`, `90`, `180`, and `270` degrees.

For `v_D = (x_D, y_D, z_D)`, the normative display transforms are:

| `displayRotationClockwise` | `v_S = R_S_from_D * v_D` |
| --- | --- |
| `0` | `( x_D,  y_D, z_D)` |
| `90` | `(-y_D,  x_D, z_D)` |
| `180` | `(-x_D, -y_D, z_D)` |
| `270` | `( y_D, -x_D, z_D)` |

Platform orientation values MUST be translated to this enum explicitly. The platform enum's integer value MUST NOT be copied without a tested mapping.

A display rotation change during a session MUST be recorded before the first sample observed under the new display rotation. It MUST NOT silently rotate stored device-frame samples.

### 5.3 Gesture frame `G`

The gesture frame is the fixed interaction frame for one session. By default, it is the display frame at session start:

```text
R_G_from_D = R_S_from_D(displayRotationClockwise at session start)
```

An application MAY provide a different orthonormal `R_G_from_D` before the first sample, for example for a known mounting orientation. The transform MUST be included in the session descriptor.

`R_G_from_D` MUST NOT change within a session. If the application wants gestures to follow a new display orientation or mounting pose, it MUST stop the current session and start a new one. A UI rotation may still be recorded as metadata while the existing gesture frame remains fixed.

Detectors consume gesture-frame signals:

```text
v_G = R_G_from_D * v_D
```

This separation prevents UI rotation from causing a discontinuous gesture input.

### 5.4 Reference frame `R`

An attitude reference frame is distinct from `D`, `S`, and `G`. It describes the external frame used by a fused attitude source.

When attitude is present, the session descriptor MUST identify one of these canonical reference kinds:

- `gravityAlignedSessionLocal`: `+z_R` is up; horizontal heading is established for this source session and is not globally comparable.
- `eastNorthUpMagnetic`: `+x_R` is east, `+y_R` is magnetic north, and `+z_R` is up.
- `eastNorthUpTrue`: `+x_R` is east, `+y_R` is true north, and `+z_R` is up.
- `platformDefined`: the producer supplies a non-empty, namespaced native reference identifier, a textual axis definition, and either `session` or `global` scope.

`gravityAlignedSessionLocal` and session-scoped `platformDefined` references MUST carry a reference-instance identifier unique within the trace. Two attitudes MAY be combined or compared as sharing `R` only when their reference kind, scope, and reference-instance identity are compatible. A matching generic name alone is insufficient.

## 6. Signal semantics and units

The standard gravity constant is:

```text
g0 = 9.80665 m/s^2
```

### 6.1 Gravity

`gravityG_D` is the physical gravitational-acceleration vector expressed in device frame `D`, divided by `g0`. It is therefore dimensionless. Its magnitude is normally near `1` when the platform estimate is reliable.

For a stationary device lying face-up, `gravityG_D` is approximately `(0, 0, -1)` because physical gravity points behind the front display.

An adapter MUST normalize platform force/sign conventions to this definition. In particular, Android `TYPE_GRAVITY` is negated and divided by `g0`; see section 14.2.

### 6.2 User acceleration

`userAccelerationG_D` is the platform's gravity-removed translational acceleration expressed in `D`, divided by `g0`. It is dimensionless and approximately zero while the device is stationary.

The source kind MUST distinguish a platform-fused value from a value derived by this library. V1 does not define a library filter for deriving user acceleration.

### 6.3 Rotation rate

`rotationRateRadPerSec_D` is angular velocity about each device axis in radians per second. Positive rotation follows the right-hand rule about the corresponding positive axis.

A source MUST identify whether the platform reports a raw or bias-corrected rotation rate.

### 6.4 Attitude quaternion

The canonical attitude quaternion is named `qReferenceFromDevice` and stored component-wise as `(x, y, z, w)`, scalar last. It is a finite unit quaternion using the Hamilton convention.

For a device-frame vector `v_D`, it has the exact meaning:

```text
(0, v_R) = qReferenceFromDevice
           * (0, v_D)
           * conjugate(qReferenceFromDevice)
```

The quaternion norm MUST be within the schema's declared tolerance of `1`. A producer MAY normalize a finite near-unit platform quaternion, but it MUST record that normalization in diagnostics. A zero-norm quaternion is invalid.

Platform component order or transform direction MUST be adapted to this definition. Direct field copying is conforming only when a platform-specific test proves that it has the same transform semantics.

## 7. Gesture vocabulary

### 7.1 Neutral

`neutral` describes a detector- or annotation-defined region around the session's intended rest pose. V1 does not assign a universal angle or threshold to neutral.

The neutral pose establishes the intuitive screen axes of `G`: `+x_G` right, `+y_G` up, and `+z_G` out through the display.

### 7.2 Tilt forward

`tiltForward` is positive rotation about `+x_G` from the neutral pose. By the right-hand rule, positive rotation moves the display normal `+z_G` toward `-y_G`, meaning the front of the device points downward.

### 7.3 Tilt backward

`tiltBackward` is negative rotation about `+x_G` from the neutral pose. It moves the display normal `+z_G` toward `+y_G`.

These are generic gesture names. An integrating application MAY map them to actions, but those action names and side effects are not part of this specification.

### 7.4 Gesture event cycle

The terms `candidate`, `confirmed`, `returning`, and `ready` are reserved for later detector state machines. V1 traces and evaluator contracts do not imply that a legacy detector implements those states.

## 8. Time and ordering

### 8.1 Canonical time

Every accepted sample has:

- `timestampNs`: a non-negative signed 64-bit integer number of nanoseconds relative to the session time origin
- `sequence`: a non-negative signed 64-bit integer assigned in source-observation order, beginning at `0` and strictly increasing; gaps are allowed when an observation is rejected after sequence reservation

Wall-clock time, time zone, account identity, and device identity are not part of canonical sample time and MUST NOT be required for replay.

### 8.2 Platform conversion

The source session captures one platform monotonic origin before or at its first accepted observation. The origin MUST use the same native time base as the observations and remain fixed for the session. Its absolute boot-relative value is adapter state, not required trace data.

For Core Motion:

```text
timestampNs = roundToNearestEven(
    (CMLogItem.timestamp - originSeconds) * 1_000_000_000
)
```

For Android Sensors:

```text
timestampNs = SensorEvent.timestamp - originElapsedRealtimeNs
```

An adapter MUST reject negative results and arithmetic overflow.

### 8.3 Ordering

Samples in a trace MUST be ordered lexicographically by `(timestampNs, sequence)`.

- Equal timestamps are allowed and retain source-observation order through `sequence`.
- A producer MAY serialize samples through an explicitly configured bounded reorder buffer. It MUST preserve original observation order in `sequence`, record the policy, and count reordered samples in diagnostics.
- A sample arriving behind the committed reorder watermark is late. It MUST be rejected with `nonMonotonicTimestamp`; it MUST NOT be inserted silently into already committed output.
- A producer MUST NOT synthesize samples to fill a time gap.
- A sample with a missing, non-finite, negative, or unconvertible timestamp is invalid and MUST NOT enter the ordered trace.
- Rejected samples MUST increment a diagnostic counter with a reason.

Replay uses `timestampNs` as virtual time and `sequence` as the stable tie-breaker. It MUST NOT use callback arrival time or wall-clock sleeping for correctness.

## 9. Capabilities and availability

A source MUST produce an immutable capability snapshot when a session starts. Runtime changes MAY be emitted as explicit capability-change records; they MUST NOT rewrite the initial snapshot.

Each signal capability includes:

- Signal kind: gravity, user acceleration, rotation rate, or attitude
- Availability: `available`, `unavailable`, `restricted`, or `unknown`
- Source kind: `hardware`, `software`, `fused`, or `unknown`
- Native source identifier
- Native unit and sign convention
- Requested interval or delay, if any
- Platform-reported minimum delay, maximum delay, resolution, or accuracy when available and allowed by the privacy tier
- Whether the adapter performs sign, unit, frame, quaternion-order, or quaternion-direction conversion

Availability MUST be detected at runtime. A device model name MUST NOT stand in for a capability check.

A requested sampling interval is a hint, not a guarantee. Producers MUST derive observed intervals from accepted monotonic timestamps. Capability presence does not imply that every sample contains that signal.

A source configuration declares required and optional signals:

- If a required signal is unavailable or restricted, `start` MUST fail with `requiredCapabilityUnavailable` before recording begins.
- If an optional signal is unavailable, recording MAY continue and the capability snapshot MUST state why the field is absent.

Exact device model, sensor vendor, and sensor version are not required for detector correctness. If an integration records them, it MUST apply the trace schema's privacy tier and publication review rules.

## 10. Lifecycle contracts

### 10.1 Motion source

| State | Allowed operations | Transition |
| --- | --- | --- |
| `idle` | `start` | `running` or `failed` |
| `running` | emit observation, `stop` | `stopped` or `failed` |
| `stopped` | `start` | a new `running` session with new origins |
| `failed` | none except disposal | terminal |

Calling `start` while running returns `invalidState`. Calling `stop` in `idle` or `stopped` is an idempotent no-op. A source emits at most one terminal failure per session and emits no observations after stop or failure.

### 10.2 Recorder

| State | Allowed operations | Transition |
| --- | --- | --- |
| `idle` | `start` | `recording` or `failed` |
| `recording` | append sample/annotation, `finish`, `cancel` | `finalizing`, `cancelled`, or `failed` |
| `finalizing` | internal writes only | `finished` or `failed` |
| `finished` | read result | terminal |
| `cancelled` | inspect diagnostics | terminal |
| `failed` | inspect error/diagnostics | terminal |

A recorder instance owns exactly one trace. It MUST reject samples outside `recording`. `finish` and `cancel` MUST be safe against duplicate calls by returning the existing terminal result or a documented `invalidState`; they MUST NOT create a second trace.

### 10.3 Detector under test

| State | Allowed operations | Transition |
| --- | --- | --- |
| `idle` | `start` | `running` or `failed` |
| `running` | consume sample, `stop` | `stopped` or `failed` |
| `stopped` | `start` | a new clean `running` session |
| `failed` | none except disposal | terminal |

`start` creates a clean detector session. Detector state from a previous session MUST NOT leak into a new one. Consumed samples MUST follow section 8 ordering.

### 10.4 Replay

| State | Allowed operations | Transition |
| --- | --- | --- |
| `idle` | `load` | `ready` or `failed` |
| `ready` | `step`, `run` | `replaying`, `finished`, or `failed` |
| `replaying` | internal delivery, `cancel` | `finished`, `cancelled`, or `failed` |
| `finished` / `cancelled` | `reset` | `ready` |
| `failed` | none except disposal | terminal |

`reset` returns to the beginning of the already validated trace and resets the detector under test. Replaying the same trace with the same detector version and configuration MUST produce the same ordered predictions.

### 10.5 Evaluator

| State | Allowed operations | Transition |
| --- | --- | --- |
| `idle` | `load` finalized trace, annotations, predictions, and configuration | `ready` or `failed` |
| `ready` | `evaluate` | `evaluating`, `finished`, or `failed` |
| `evaluating` | internal matching and aggregation only | `finished` or `failed` |
| `finished` | read report | terminal |
| `failed` | inspect error/diagnostics | terminal |

Evaluation is deterministic and side-effect free for the same validated inputs and evaluator version. It MUST NOT read a sensor, wall clock, network service, account identity, or device identity. Matching policy and metrics are defined by the [event evaluator specification](../../docs/evaluator-v1.md).

## 11. Error contract

Errors have a stable machine-readable code, lifecycle stage, human-readable diagnostic, and optional platform cause. Platform exception text is not a stable code and MUST NOT be used for cross-platform decisions.

| Code | Meaning | Expected recovery |
| --- | --- | --- |
| `invalidConfiguration` | Configuration violates this specification | Correct configuration before retry |
| `invalidState` | Operation is not valid in the current lifecycle state | Correct call order |
| `requiredCapabilityUnavailable` | Required signal is unavailable or restricted | Change requirements or device |
| `sourceUnavailable` | Platform motion service cannot start | Retry only if platform state changes |
| `sourceFailure` | Platform source failed after start | Start a new session after diagnosis |
| `invalidSample` | Sample contains missing, non-finite, or invalid values | Reject sample; fail only under strict policy |
| `nonMonotonicTimestamp` | New sample is behind the committed timeline or reorder watermark | Reject sample; fail only under strict policy |
| `limitExceeded` | Duration, sample, or byte bound was reached | Finalize as bounded or cancel per configuration |
| `ioFailure` | Trace write/read operation failed | Preserve partial diagnostics; do not mark complete |
| `unsupportedSpecVersion` | Core semantic major version is unsupported | Use a compatible reader |
| `unsupportedSchemaVersion` | Wire schema major version is unsupported | Use a compatible reader |
| `incompleteTrace` | Trace lacks a valid completion record | Treat as incomplete, never as a complete dataset item |
| `cancelled` | Caller intentionally cancelled | No automatic retry implied |

Recoverable sample rejection MUST be observable through diagnostics. A producer MUST NOT replace invalid values with zero or clamp them without an explicit, recorded normalization rule.

## 12. Version and compatibility policy

The core semantic specification, wire schema, library package, detector implementation, and trace container are independently versioned. A producer MUST NOT substitute one version for another.

Every finalized trace MUST identify at least:

- `coreSpecVersion`
- `schemaVersion`
- Producer library name and version
- Platform adapter name and version
- Detector identifier, version, and configuration identity for each predicted-event stream

Core and schema versions use semantic `major.minor.patch` numbering, optionally with a prerelease suffix while the v0.1 milestone is in draft.

- A **major** change may alter existing meaning or remove a previously valid contract.
- A **minor** change may add optional, backward-compatible meaning.
- A **patch** change clarifies wording or fixes a defect without changing conforming behavior.

A consumer:

- MUST reject an unsupported core or schema major version.
- MAY accept a newer minor version only when all unknown additions are explicitly optional and no required feature declaration is unknown.
- MUST NOT reinterpret an unknown required record, signal convention, frame, transform, or reference-frame identifier using a local default.
- SHOULD preserve unknown optional serialized fields when performing a lossless read-modify-write operation.
- MUST report the exact unsupported version or feature in a stable error.

A producer MUST declare every optional feature it uses. Absence of a declaration MUST NOT be treated as permission to guess.

The package release remains pre-1.0 during the Measurement Foundation milestone, but `LegacyGravityThresholdV1` behavior is immutable once released. A behavior change requires a new detector identifier rather than a silent patch-version change.

## 13. Swift and Kotlin concept mapping

The semantic model is language-neutral. The following mappings are illustrative and do not make either platform's naming normative.

| Concept | Swift direction | Kotlin direction |
| --- | --- | --- |
| `Vector3` | immutable `struct`, `Sendable` | immutable `data class` |
| `Quaternion` | immutable `struct`, `Sendable` | immutable `data class` |
| `TimestampNs` / `Sequence` | non-negative `Int64` wrapper | non-negative `Long` value class |
| `MotionSample` | immutable `struct`, optional signal fields | immutable `data class`, nullable signal fields |
| `MotionCapabilities` | immutable value types | immutable data/value types |
| `MotionGestureError` | stable-code `Error` value | stable-code sealed error value |
| Sample stream | `AsyncSequence`-compatible adapter | `Flow`-compatible adapter |

Public APIs SHOULD use value semantics for samples and configuration. Platform manager objects and callback queues remain adapter implementation details.

## 14. Platform mapping requirements

### 14.1 Apple Core Motion

The Apple adapter:

1. Uses the device axes documented by `CMMotionManager`: positive x toward screen right, positive y toward the top edge, and positive z out through the front display.
2. Converts `CMLogItem.timestamp`, which is seconds since boot, using section 8.2.
3. Maps `CMDeviceMotion.gravity` directly to `gravityG_D` because Core Motion reports the gravity acceleration vector in the device reference frame in multiples of gravity.
4. Maps `CMDeviceMotion.userAcceleration` directly to `userAccelerationG_D`.
5. Maps bias-corrected `CMDeviceMotion.rotationRate` to radians per second in `D` and marks it bias-corrected.
6. Converts `CMAttitude.quaternion` into canonical `(x, y, z, w)` and verifies the `qReferenceFromDevice` direction against the corresponding rotation matrix. It MUST invert the quaternion if the platform representation has the opposite transform direction.
7. Maps the selected `CMAttitudeReferenceFrame` explicitly:
   - `xArbitraryZVertical` and `xArbitraryCorrectedZVertical` use `gravityAlignedSessionLocal`, separate reference-instance identifiers, and distinct native source identifiers.
   - `xMagneticNorthZVertical` and `xTrueNorthZVertical` are normalized from Core Motion's `+x = north, +z = up` basis into canonical `eastNorthUpMagnetic` and `eastNorthUpTrue`, respectively. For native reference coordinates `(x, y, z)`, the canonical reference coordinates are `(-y, x, z)`.
8. Converts current interface orientation into `displayRotationClockwise` explicitly.

The requested `deviceMotionUpdateInterval` is recorded as requested timing, not delivered timing.

### 14.2 Android Sensors

The Android adapter:

1. Uses the standard sensor axes fixed to the device's natural orientation. It never assumes that a tablet's natural orientation is portrait.
2. Converts `SensorEvent.timestamp`, which is nanoseconds on the `elapsedRealtimeNanos` time base, using section 8.2.
3. Converts `TYPE_GRAVITY` from the Android acceleration-force convention to physical gravity:

   ```text
   gravityG_D = -typeGravityMetersPerSecondSquared_D / g0
   ```

   Android documents that a stationary gravity sensor matches the accelerometer, and that a face-up stationary accelerometer reports approximately `+g0` on `+z_D`. The canonical physical-gravity value is therefore approximately `-1` on `z_D`.
4. Converts `TYPE_LINEAR_ACCELERATION` to `userAccelerationG_D` by dividing by `g0` without the gravity sign inversion.
5. Maps `TYPE_GYROSCOPE` or a declared bias-corrected variant to radians per second in `D` and records which source was used.
6. Uses `SensorManager.getQuaternionFromVector` only as a platform conversion helper. Android returns `[w, x, y, z]`; the adapter reorders to `(x, y, z, w)` and verifies transform direction against `getRotationMatrixFromVector` before emitting `qReferenceFromDevice`.
7. Records the exact rotation-vector sensor kind and its reference semantics. `TYPE_ROTATION_VECTOR` and `TYPE_GEOMAGNETIC_ROTATION_VECTOR` use Android's east-north-up magnetic reference. `TYPE_GAME_ROTATION_VECTOR` uses `gravityAlignedSessionLocal` because its horizontal reference is not north-referenced and may drift.
8. Maps `Display.getRotation()` to `displayRotationClockwise` and tests every supported value.

`registerListener` delay values and requested microsecond periods are hints. Observed timing comes from sample timestamps.

## 15. Worked mappings

### 15.1 Equivalent iOS and Android gravity observations

Assume display rotation `0`, identity `R_G_from_D`, and this canonical physical state:

```text
gravityG_D = (0.0, -0.6, +0.8)
```

An iOS `CMDeviceMotion` observation may report:

```text
CMLogItem.timestamp       = 1234.250000000 seconds
session origin            = 1234.000000000 seconds
gravity                   = (0.0, -0.6, +0.8) g
userAcceleration          = (+0.1, 0.0, 0.0) g
rotationRate              = (+0.2, 0.0, 0.0) rad/s
```

The canonical result is:

```text
timestampNs               = 250_000_000
gravityG_D                = (0.0, -0.6, +0.8)
userAccelerationG_D       = (+0.1, 0.0, 0.0)
rotationRateRadPerSec_D   = (+0.2, 0.0, 0.0)
```

For the same physical state, Android sources may report:

```text
SensorEvent.timestamp     = 9_250_000_000 ns
session origin            = 9_000_000_000 ns
TYPE_GRAVITY              = (0.0, +5.883990, -7.845320) m/s^2
TYPE_LINEAR_ACCELERATION  = (+0.980665, 0.0, 0.0) m/s^2
TYPE_GYROSCOPE            = (+0.2, 0.0, 0.0) rad/s
```

Negating Android gravity and dividing accelerations by `g0` produces the same canonical values and `timestampNs = 250_000_000`. Because `R_G_from_D` is identity in this example, those device-frame values are also the canonical gesture-frame values consumed by the detector.

If these Android signals arrive in three independent callbacks, they are three ordered partial `MotionSample` values. They MUST NOT be represented as one atomic fused sample unless a declared assembler creates a derived sample.

### 15.2 Display rotation

For:

```text
v_D = (0.0, -0.6, +0.8)
displayRotationClockwise = 90
```

the display-frame value is:

```text
v_S = (+0.6, 0.0, +0.8)
```

If the session starts under that rotation with the default gesture transform, `v_G` is also `(+0.6, 0.0, +0.8)`. A later UI rotation does not change the session's frozen `R_G_from_D`.

### 15.3 Generic forward tilt

For a vertical neutral pose, gravity in the gesture frame is approximately:

```text
neutral gravityG_G = (0.0, -1.0, 0.0)
```

A positive 90-degree rotation about `+x_G` points the display normal downward and yields approximately:

```text
forward gravityG_G = (0.0, 0.0, +1.0)
```

That positive direction is `tiltForward`. The opposite rotation is `tiltBackward`. This definition preserves generic motion semantics without assigning either gesture to an application action.

### 15.4 Quaternion convention

Identity is `(0, 0, 0, 1)`. A positive 90-degree rotation about `+x` is:

```text
qReferenceFromDevice = (sqrt(0.5), 0, 0, sqrt(0.5))
```

Conformance is determined by the vector-transform equation in section 6.4, not by component order alone.

## 16. Constraints handed to the wire-schema issue

The following semantic questions are resolved and MUST NOT be reopened by field naming or container choices in issue #3:

- Canonical stored vectors use device frame `D` and explicitly recorded transforms.
- Gravity and user acceleration use multiples of `g0`; rotation rate uses radians per second.
- Canonical gravity is physical gravity, requiring Android `TYPE_GRAVITY` sign inversion.
- Canonical time is non-negative relative nanoseconds with a stable sequence tie-breaker.
- Wall-clock time and device identity are not required canonical fields.
- Samples may be partial; omitted signals are not zero.
- Gesture frame is fixed for one session.
- Display rotation changes are explicit records or metadata changes, never silent transforms.
- Quaternion order is `(x, y, z, w)` and direction is `reference from device`.
- Attitude reference kind, scope, native identifier, and session-local reference identity are explicit; north-referenced attitudes use canonical east-north-up axes.
- Requested sampling timing and observed timing are distinct.
- Required missing capabilities fail start; optional missing capabilities remain explicit.
- Invalid or incomplete traces cannot be treated as complete dataset items.

The schema issue still owns serialization details such as record discriminators, exact JSON property names, compression, completion records, numeric constraints, and validation fixtures.

## 17. References

Primary platform references:

- Apple, [CMMotionManager](https://developer.apple.com/documentation/coremotion/cmmotionmanager)
- Apple, [CMDeviceMotion gravity](https://developer.apple.com/documentation/coremotion/cmdevicemotion/gravity)
- Apple, [Getting raw accelerometer events](https://developer.apple.com/documentation/coremotion/getting-raw-accelerometer-events)
- Apple, [Getting raw gyroscope events](https://developer.apple.com/documentation/coremotion/getting-raw-gyroscope-events)
- Apple, [CMLogItem timestamp](https://developer.apple.com/documentation/coremotion/cmlogitem/timestamp)
- Apple, [CMAttitudeReferenceFrame](https://developer.apple.com/documentation/coremotion/cmattitudereferenceframe)
- Apple, [Getting processed device-motion data](https://developer.apple.com/documentation/coremotion/getting-processed-device-motion-data)
- Apple, [CMQuaternion](https://developer.apple.com/documentation/coremotion/cmquaternion)
- Android, [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- Android, [Motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
- Android, [Position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)
- Android, [SensorEvent](https://developer.android.com/reference/android/hardware/SensorEvent)
- Android, [Display.getRotation](https://developer.android.com/reference/android/view/Display)
- Android, [SensorManager quaternion and rotation helpers](https://developer.android.com/reference/android/hardware/SensorManager)
