public enum DetectorLifecycleState: String, Sendable {
  case idle
  case running
  case stopped
}

public enum DetectorOperation: String, Sendable {
  case start
  case consume
  case stop
}

/// Stable invalid-state failure for the detector lifecycle defined by core specification v1.
public struct DetectorInvalidStateError: Error, Equatable, Sendable, CustomStringConvertible {
  public let operation: DetectorOperation
  public let state: DetectorLifecycleState

  public init(operation: DetectorOperation, state: DetectorLifecycleState) {
    self.operation = operation
    self.state = state
  }

  public var code: String { "invalidState" }

  public var description: String {
    "\(code): cannot \(operation.rawValue) while detector is \(state.rawValue)"
  }
}
