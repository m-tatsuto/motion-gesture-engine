/// A detector event before trace-specific stream, UUID, and event-sequence fields are assigned.
public struct PredictedGestureEvent: Equatable, Sendable {
  public let timestampNs: Int64
  public let sourceSampleSequence: Int64
  public let gesture: Gesture

  public init(timestampNs: Int64, sourceSampleSequence: Int64, gesture: Gesture) {
    self.timestampNs = timestampNs
    self.sourceSampleSequence = sourceSampleSequence
    self.gesture = gesture
  }
}
