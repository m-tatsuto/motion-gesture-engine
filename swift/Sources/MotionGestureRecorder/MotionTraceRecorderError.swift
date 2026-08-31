import Foundation

public enum MotionTraceRecorderState: String, Sendable {
  case idle
  case recording
  case finalizing
  case finished
  case cancelled
  case failed
}

public enum MotionTraceRecorderStage: String, Sendable {
  case start
  case append
  case finalize
  case commit
}

public enum MotionTraceRecorderErrorCode: String, Sendable {
  case invalidConfiguration
  case invalidState
  case invalidSample
  case ioFailure
}

public struct MotionTraceRecorderError: Error, CustomStringConvertible, Sendable {
  public let code: MotionTraceRecorderErrorCode
  public let stage: MotionTraceRecorderStage
  public let diagnostic: String
  public let partialURL: URL?

  public init(
    code: MotionTraceRecorderErrorCode,
    stage: MotionTraceRecorderStage,
    diagnostic: String,
    partialURL: URL? = nil
  ) {
    self.code = code
    self.stage = stage
    self.diagnostic = diagnostic
    self.partialURL = partialURL
  }

  public var description: String {
    "\(code.rawValue) during \(stage.rawValue): \(diagnostic)"
  }
}
