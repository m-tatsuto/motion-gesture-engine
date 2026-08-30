/// One canonical gravity observation expressed in the session's frozen gesture frame.
///
/// Platform sign and unit conversion must happen before this value reaches the core.
/// Inputs are required to satisfy the core specification: non-negative monotonic time,
/// non-negative sequence, and a finite gravity value in standard-gravity units.
public struct GestureFrameGravitySample: Equatable, Sendable {
  public let timestampNs: Int64
  public let sequence: Int64
  public let gravityZG: Double

  public init(timestampNs: Int64, sequence: Int64, gravityZG: Double) {
    self.timestampNs = timestampNs
    self.sequence = sequence
    self.gravityZG = gravityZG
  }
}
