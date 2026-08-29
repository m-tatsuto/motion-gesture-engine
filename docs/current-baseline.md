# Current baseline reference

This document records the behavior that `LegacyGravityThresholdV1` must reproduce. It is a reference target, not a recommended final detector.

## Generic behavior

The application-specific actions are normalized to generic gesture names:

- `tiltForward`: the direction currently mapped to a correct answer
- `tiltBackward`: the direction currently mapped to pass

After platform coordinate normalization, the detector uses one gravity-axis value and an armed flag:

```text
trigger threshold = 0.65
re-arm threshold  = 0.35

on sample z:
  if armed:
    if z > 0.65:
      armed = false
      emit tiltForward
    else if z < -0.65:
      armed = false
      emit tiltBackward
  else if abs(z) < 0.35:
    armed = true
```

The platform adapter owns any sign transformation required to produce the canonical value above.

## iOS reference

- Source: Core Motion device motion
- Signal used by the detector: `gravity.z`
- Requested update interval: 30 Hz
- Trigger: `gravity.z > 0.65` or `< -0.65`
- Re-arm: `abs(gravity.z) < 0.35`
- State: one `armed` Boolean

## Android reference

- Source: `Sensor.TYPE_GRAVITY`
- Requested delivery: `SENSOR_DELAY_GAME`
- Signal used by the detector: `event.values[2] / GRAVITY_EARTH`
- Trigger: normalized value `< -0.65` or `> 0.65`, mapped through the platform sign convention
- Re-arm: absolute normalized value `< 0.35`
- State: one `armed` Boolean

## Known limitations to characterize, not fix, in v0.1

- No calibration or noise-floor estimation
- No filtering, debounce duration, or confidence score
- No use of angular velocity, user acceleration, or trajectory
- Update-rate differences between platforms
- No explicit sample timestamp in detector logic
- Immediate trigger from a single threshold-crossing sample
- Re-arming based only on a single neutral-axis threshold
- No distinction between intentional tilt and shake or placement motion

Characterization tests must preserve these behaviors. Improvements belong to a later roadmap and must be measured against this baseline.
