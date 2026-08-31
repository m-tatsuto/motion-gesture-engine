# Core Motion recorder adapter

`MotionGestureCoreMotion` is the iOS acquisition layer for Motion Trace v1. `CoreMotionTraceRecorder` owns one `CMMotionManager` device-motion session and one bounded `MotionTraceRecorder`; it does not depend on SwiftUI, UIKit views, networking, authentication, analytics, or application action mappings.

## Start contract

Construction snapshots device-motion availability and the available `CMAttitudeReferenceFrame` values. `start()` fails before creating a trace when device motion or the selected reference frame is unavailable. Failures use stable `CoreMotionRecorderAdapterErrorCode` values, while the native error domain and integer code remain optional diagnostics.

The production driver sets `deviceMotionUpdateInterval`, disables Core Motion's movement UI, and receives callbacks on a private serial `OperationQueue`. The configured interval is recorded on all four capabilities as requested timing. It is never treated as proof of delivered frequency; footer `observedTiming` is calculated from accepted Core Motion timestamps.

## Signal mapping

One coherent `CMDeviceMotion` callback becomes one `MotionSample` containing all four fused observations:

| Core Motion field | Canonical field | Conversion |
| --- | --- | --- |
| `gravity` | `gravityG_D` | Direct device-frame copy in standard-gravity units. |
| `userAcceleration` | `userAccelerationG_D` | Direct device-frame copy in standard-gravity units. |
| `rotationRate` | `rotationRateRadPerSec_D` | Direct device-frame copy, marked bias-corrected. |
| `attitude.quaternion` | `qReferenceFromDevice` | Normalize a near-unit value, verify it against `rotationMatrix`, apply any declared direction inversion, then apply the selected reference-basis transform. |

Non-finite vectors, invalid quaternion norms, and quaternion/matrix disagreement are reported as `malformed`; they are never replaced with zero values.

## Time and sequence

The first valid Core Motion timestamp fixes the session origin. Every observation then uses:

```text
roundToNearestEven((CMLogItem.timestamp - originSeconds) * 1_000_000_000)
```

Negative, overflowing, or backward results are rejected. Sequence numbers are reserved in callback order, so rejected observations leave visible gaps without changing later ordering.

## Attitude reference frames

- `xArbitraryZVertical` and `xArbitraryCorrectedZVertical` become separate `gravityAlignedSessionLocal` references with a trace-local UUID.
- `xMagneticNorthZVertical` becomes `eastNorthUpMagnetic`.
- `xTrueNorthZVertical` becomes `eastNorthUpTrue`.
- Magnetic and true-north frames apply the normative native `(north, west, up)` to canonical `(east, north, up)` basis mapping `(-y, x, z)`.

The capability metadata records `quaternionInvert` and `referenceBasisTransform` only when those conversions are configured.

## Orientation

`CoreMotionInterfaceOrientation` maps names explicitly rather than copying platform enum integers:

| Interface orientation | `displayRotationClockwise` |
| --- | --- |
| `portrait` | `0` |
| `landscapeLeft` | `90` |
| `portraitUpsideDown` | `180` |
| `landscapeRight` | `270` |

The initial value defines the header and default frozen gesture-frame matrix. A runtime update is held until the next valid Core Motion observation, then written as `displayRotationChange` with that sample's timestamp immediately before the sample. Stored vectors remain in physical device frame `D`.

## Test boundary

`CoreMotionDeviceMotionDriving` accepts an injected driver that reports immutable availability and emits raw observations onto the supplied queue. Unit tests therefore cover availability, timing, ordering, orientation, quaternion conversion, source errors, and finalized trace output without a simulator or physical device. The production `CMMotionManagerDeviceMotionDriver` is additionally compiled against the iOS Simulator SDK.

Consent UI, background collection policy, upload, retention, private storage, and feedback delivery remain responsibilities of the integrating application.
