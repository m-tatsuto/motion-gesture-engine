/// Immutable identity and thresholds for the legacy comparison baseline.
///
/// Changing any value requires a new detector identifier; these values are not tunable.
public enum LegacyGravityThresholdV1Configuration {
  public static let detectorId = "LegacyGravityThresholdV1"
  public static let detectorVersion = "1.0.0"
  public static let configurationIdentity = "legacy.default.v1"
  public static let triggerMagnitude = 0.65
  public static let rearmMagnitude = 0.35
}

/// The unfiltered, single-axis gravity detector used as the v1 comparison baseline.
///
/// This type deliberately performs no smoothing, calibration, confidence scoring,
/// debounce timing, or cooldown. It is not a recommended future production detector.
public struct LegacyGravityThresholdV1: Sendable {
  public private(set) var lifecycleState: DetectorLifecycleState = .idle
  public private(set) var isArmed = true

  public init() {}

  /// Starts a clean detector session.
  public mutating func start() throws {
    guard lifecycleState != .running else {
      throw DetectorInvalidStateError(operation: .start, state: lifecycleState)
    }

    isArmed = true
    lifecycleState = .running
  }

  /// Stops the current detector session and restores the armed state.
  public mutating func stop() throws {
    guard lifecycleState == .running else {
      throw DetectorInvalidStateError(operation: .stop, state: lifecycleState)
    }

    isArmed = true
    lifecycleState = .stopped
  }

  /// Returns the detector to its initial clean state for deterministic replay.
  public mutating func reset() {
    isArmed = true
    lifecycleState = .idle
  }

  /// Consumes one already-normalized gesture-frame gravity sample.
  public mutating func consume(
    _ sample: GestureFrameGravitySample
  ) throws -> PredictedGestureEvent? {
    guard lifecycleState == .running else {
      throw DetectorInvalidStateError(operation: .consume, state: lifecycleState)
    }

    if isArmed {
      if sample.gravityZG > LegacyGravityThresholdV1Configuration.triggerMagnitude {
        isArmed = false
        return event(for: .tiltForward, sample: sample)
      }

      if sample.gravityZG < -LegacyGravityThresholdV1Configuration.triggerMagnitude {
        isArmed = false
        return event(for: .tiltBackward, sample: sample)
      }
    } else if abs(sample.gravityZG) < LegacyGravityThresholdV1Configuration.rearmMagnitude {
      isArmed = true
    }

    return nil
  }

  private func event(
    for gesture: Gesture,
    sample: GestureFrameGravitySample
  ) -> PredictedGestureEvent {
    PredictedGestureEvent(
      timestampNs: sample.timestampNs,
      sourceSampleSequence: sample.sequence,
      gesture: gesture
    )
  }
}
