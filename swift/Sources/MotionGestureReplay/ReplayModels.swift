import Foundation
import MotionGestureCore
import MotionGestureRecorder

public enum ReplayLifecycleState: String, Sendable {
  case idle
  case ready
  case replaying
  case finished
  case cancelled
  case failed
}

public enum ReplayStage: String, Sendable {
  case load
  case step
  case run
  case reset
  case cancel
}

public enum ReplayErrorCode: String, Sendable {
  case invalidState
  case invalidContainer
  case malformedRecord
  case unsupportedSchemaVersion
  case unsupportedSpecVersion
  case unsupportedCompression
  case incompleteTrace
  case nonMonotonicSample
  case footerMismatch
  case limitExceeded
  case ioFailure
  case detectorFailure
}

public struct ReplayError: Error, Equatable, Sendable, CustomStringConvertible {
  public let code: ReplayErrorCode
  public let stage: ReplayStage
  public let diagnostic: String
  public let line: Int?

  public init(
    code: ReplayErrorCode,
    stage: ReplayStage,
    diagnostic: String,
    line: Int? = nil
  ) {
    self.code = code
    self.stage = stage
    self.diagnostic = diagnostic
    self.line = line
  }

  public var description: String {
    "\(code.rawValue) at \(stage.rawValue): \(diagnostic)"
  }
}

public struct ReplayLimits: Equatable, Sendable {
  public let maximumBytes: Int
  public let maximumLineBytes: Int
  public let maximumBodyRecords: Int

  public init(
    maximumBytes: Int = 64 * 1024 * 1024,
    maximumLineBytes: Int = 1024 * 1024,
    maximumBodyRecords: Int = 1_000_000
  ) {
    precondition(maximumBytes > 0, "maximumBytes must be positive")
    precondition(maximumLineBytes > 0, "maximumLineBytes must be positive")
    precondition(maximumBodyRecords > 0, "maximumBodyRecords must be positive")
    precondition(maximumBodyRecords <= Int.max - 2, "maximumBodyRecords is too large")
    self.maximumBytes = maximumBytes
    self.maximumLineBytes = maximumLineBytes
    self.maximumBodyRecords = maximumBodyRecords
  }
}

public struct ValidatedReplayTrace: Equatable, Sendable {
  public let header: MotionTraceHeader
  public let samples: [MotionSample]
  public let footer: MotionTraceFooter

  public init(
    header: MotionTraceHeader,
    samples: [MotionSample],
    footer: MotionTraceFooter
  ) {
    self.header = header
    self.samples = samples
    self.footer = footer
  }
}

public protocol MotionReplayDetector: AnyObject {
  var descriptor: MotionDetectorDescriptor { get }

  func start(session: MotionTraceSession) throws
  func consume(_ sample: MotionSample) throws -> [PredictedGestureEvent]
  func stop() throws
  func reset() throws
}

public final class LegacyGravityThresholdV1ReplayDetector: MotionReplayDetector {
  public let descriptor: MotionDetectorDescriptor

  private var detector = LegacyGravityThresholdV1()
  private var gestureFrameFromDevice: [Double] = []

  public init(detectorStreamId: String = "legacy.replay.v1") {
    descriptor = MotionDetectorDescriptor(
      detectorStreamId: detectorStreamId,
      detectorId: LegacyGravityThresholdV1Configuration.detectorId,
      detectorVersion: LegacyGravityThresholdV1Configuration.detectorVersion,
      configurationIdentity: LegacyGravityThresholdV1Configuration.configurationIdentity
    )
  }

  public func start(session: MotionTraceSession) throws {
    gestureFrameFromDevice = session.gestureFrameFromDeviceRowMajor
    try detector.start()
  }

  public func consume(_ sample: MotionSample) throws -> [PredictedGestureEvent] {
    guard let gravity = sample.signals.gravity else { return [] }
    let value = gravity.value
    let gravityZG =
      gestureFrameFromDevice[6] * value.x
      + gestureFrameFromDevice[7] * value.y
      + gestureFrameFromDevice[8] * value.z
    return try detector.consume(
      GestureFrameGravitySample(
        timestampNs: sample.timestampNs,
        sequence: sample.sequence,
        gravityZG: gravityZG
      )
    ).map { [$0] } ?? []
  }

  public func stop() throws {
    try detector.stop()
  }

  public func reset() {
    detector.reset()
    gestureFrameFromDevice = []
  }
}

public struct ReplayStep: Equatable, Sendable {
  public let virtualTimestampNs: Int64
  public let sample: MotionSample
  public let emittedEvents: [MotionPredictedEventRecord]
  public let isFinished: Bool
}

public struct ReplayRunResult: Equatable, Sendable {
  public let detector: MotionDetectorDescriptor
  public let events: [MotionPredictedEventRecord]
  public let finalVirtualTimestampNs: Int64?

  public func encodedPredictionsJSONLines() throws -> Data {
    try ReplayPredictionEncoding.encode(events)
  }
}

enum ReplayPredictionEncoding {
  static func encode(_ events: [MotionPredictedEventRecord]) throws -> Data {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    var result = Data()
    for event in events {
      result.append(try encoder.encode(event))
      result.append(0x0A)
    }
    return result
  }
}
