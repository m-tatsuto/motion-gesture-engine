# Android Sensors recorder adapter

`motion-gesture-android-sensors` is the Android acquisition layer for Motion Trace v1. `AndroidSensorTraceRecorder` owns one `SensorManager` collection session and one bounded `MotionTraceRecorder`. It has no Activity, Fragment, Compose, networking, authentication, analytics, upload, or application-action dependency.

The module is an Android library compiled with API 37 and has `minSdk = 26`, matching the recorder's atomic `java.nio.file.Path` output contract. The production driver runs callbacks on its own `HandlerThread`. Tests inject `AndroidSensorDriver`, so canonical behavior does not require an emulator or physical device.

## Start and capability snapshot

Construction queries the default sensor for every configured candidate and freezes one capability per enabled signal. The default configuration enables all four canonical signals, requires gravity, and treats the other signals as optional.

| Canonical signal | Default source order | Missing-source behavior |
| --- | --- | --- |
| gravity | `TYPE_GRAVITY` | Required by default; `start()` fails before a trace is created. |
| user acceleration | `TYPE_LINEAR_ACCELERATION` | Header states `unavailable`; recording continues. |
| rotation rate | `TYPE_GYROSCOPE`, then `TYPE_GYROSCOPE_UNCALIBRATED` | First available source wins; otherwise header states `unavailable`. |
| attitude | `TYPE_GAME_ROTATION_VECTOR`, `TYPE_ROTATION_VECTOR`, then `TYPE_GEOMAGNETIC_ROTATION_VECTOR` | First available source wins; otherwise header states `unavailable`. |

Fallback is never silent. The selected sensor has its own fixed `nativeSourceIdentifier`; calibrated gyroscope data is marked `biasCorrected`, while the uncalibrated fallback is marked `raw`. Integrators may replace the preference order or the required-signal set explicitly.

The trace capability stores the requested sampling period, Android-reported minimum and maximum delay, resolution, initial unknown accuracy, units, sign convention, and conversions. Gravity and linear acceleration use `sourceKind = unknown` because the standard descriptor does not prove whether a particular implementation is hardware, software, or fused. Rotation-vector sensors are declared fused and gyroscopes hardware-based.

`AndroidSensorTraceRecorder.sensorSnapshot` also exposes all queried candidate descriptors for local diagnostics. Sensor vendor and version are present only for a `privateSensitive` trace context. Motion Trace v1 has no vendor field, so these values are never inserted into `nativeSourceIdentifier`, another trace string, or the JSONL output. Reviewed or public publication requires a separate application-level review.

## Requested and observed timing

The production driver calls the non-batching `registerListener` overload with the positive `requestedSamplingPeriodUs` value. Android treats this period as a request, not a delivery guarantee. The header converts it to `requestedTiming.intervalNs`; footer `observedTiming` is calculated independently for each capability from accepted `SensorEvent.timestamp` values.

The adapter captures one `SystemClock.elapsedRealtimeNanos()` origin before registration and converts every observation with:

```text
timestampNs = SensorEvent.timestamp - originElapsedRealtimeNs
```

Negative, overflowing, and wire-unsafe results are rejected. Wall-clock time is not recorded.

## Signal mapping

Each Android sensor callback becomes one partial `MotionSample`. Values from separate callbacks are not fused merely because timestamps are equal.

| Android source | Canonical field | Conversion |
| --- | --- | --- |
| `TYPE_GRAVITY` | `gravityG_D` | Negate all axes and divide by `g0 = 9.80665 m/s²`. A stationary face-up value is approximately `z_D = -1`. |
| `TYPE_LINEAR_ACCELERATION` | `userAccelerationG_D` | Divide by `g0` without sign inversion. |
| `TYPE_GYROSCOPE` | `rotationRateRadPerSec_D` | Copy radians per second; declare bias-corrected. |
| `TYPE_GYROSCOPE_UNCALIBRATED` | `rotationRateRadPerSec_D` | Copy the first three raw axes; declare raw. |
| selected rotation vector | `qReferenceFromDevice` | Use `SensorManager` to obtain `[w, x, y, z]` and a rotation matrix, reorder to `(x, y, z, w)`, normalize only a near-unit quaternion, and reject matrix-direction disagreement. |

`TYPE_GAME_ROTATION_VECTOR` creates a trace-local `gravityAlignedSessionLocal` attitude reference. `TYPE_ROTATION_VECTOR` and `TYPE_GEOMAGNETIC_ROTATION_VECTOR` use `eastNorthUpMagnetic`. Stored vectors remain in the device's natural-orientation frame; the adapter never assumes a tablet is naturally portrait.

## Accuracy and runtime availability

Android accuracy integers map explicitly to `unreliable`, `low`, `medium`, `high`, or `unknown`, retaining the native integer. Every observation carries its effective accuracy. A changed value also writes a `capabilityChange` immediately before the first observation to which it applies.

`onAccuracyChanged` has a callback timestamp but no sensor-data timestamp. The adapter therefore holds it until an observation at or after that callback's elapsed-realtime value. This prevents a newer accuracy callback from relabeling an older batched observation.

An optional source that fails registration or later reports unavailable writes an immediate availability `capabilityChange`; later callbacks for it are rejected as `unsupported`. Loss of a required source writes the change and finalizes the trace as a source failure. The immutable header is never rewritten.

## Ordering policy

The v1 adapter intentionally has no reorder buffer:

- Sequence numbers are reserved in callback-arrival order before validation. Rejected callbacks leave visible gaps.
- An observation older than the committed timeline is late/out of order and is rejected as `nonMonotonicTimestamp`.
- A callback with the same sensor type and timestamp as an already accepted callback is a duplicate and is rejected with the same reason.
- Equal timestamps from different sensor types are valid and remain ordered by sequence.
- A rejected observation never advances observed timing, and the adapter never fabricates values to fill a gap.

This policy matches the header's `sampleReordering.kind = none` declaration.

## Display rotation

`AndroidDisplayRotation.fromSurfaceRotation` maps `Surface.ROTATION_0`, `_90`, `_180`, and `_270` explicitly to clockwise degrees. The initial value freezes the default gesture-frame matrix. A runtime update captures elapsed-realtime and is held until the next valid sensor observation at or after that instant, then written as `displayRotationChange` before that sample. Older delayed observations are not relabeled. The change does not rewrite device-frame sensor values.

The application may obtain the current display rotation from any UI architecture and pass the enum to the adapter. The recorder itself neither owns nor observes an Activity or display lifecycle.

## Test and integration boundary

Fake-driver unit tests cover the normative gravity fixture, acceleration and gyroscope units, quaternion order and direction, all display rotations, requested-versus-observed timing, source fallback, privacy redaction, accuracy changes, unavailability, partial callbacks, duplicate events, and late/out-of-order events. The production `SensorManagerAndroidSensorDriver` is compiled into the Android library, but these tests do not claim real-device delivery, manufacturer behavior, batching, latency, or power results.

Consent UI, background collection policy, upload, private storage, retention, and user-facing feedback delivery remain responsibilities of the integrating application.
