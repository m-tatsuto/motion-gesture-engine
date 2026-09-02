import Foundation

public struct MotionTraceConventions: Codable, Equatable, Sendable {
  public let storedVectorFrame: String
  public let gravityUnit: String
  public let userAccelerationUnit: String
  public let rotationRateUnit: String
  public let attitudeQuaternion: String
  public let attitudeQuaternionNormTolerance: Double
  public let frameOrthonormalTolerance: Double
  public let standardGravityMps2: Double
  public let timestampUnit: String
  public let timestampOrigin: String
  public let sampleOrdering: String

  public init(
    storedVectorFrame: String = "deviceD",
    gravityUnit: String = "standardGravity",
    userAccelerationUnit: String = "standardGravity",
    rotationRateUnit: String = "radianPerSecond",
    attitudeQuaternion: String = "xyzwReferenceFromDevice",
    attitudeQuaternionNormTolerance: Double = 0.001,
    frameOrthonormalTolerance: Double = 0.000_001,
    standardGravityMps2: Double = 9.80665,
    timestampUnit: String = "nanosecond",
    timestampOrigin: String = "sessionMonotonicOrigin",
    sampleOrdering: String = "timestampThenSequence"
  ) {
    self.storedVectorFrame = storedVectorFrame
    self.gravityUnit = gravityUnit
    self.userAccelerationUnit = userAccelerationUnit
    self.rotationRateUnit = rotationRateUnit
    self.attitudeQuaternion = attitudeQuaternion
    self.attitudeQuaternionNormTolerance = attitudeQuaternionNormTolerance
    self.frameOrthonormalTolerance = frameOrthonormalTolerance
    self.standardGravityMps2 = standardGravityMps2
    self.timestampUnit = timestampUnit
    self.timestampOrigin = timestampOrigin
    self.sampleOrdering = sampleOrdering
  }
}

public struct MotionTraceSampleReordering: Codable, Equatable, Sendable {
  public let kind: String
  public let maximumLatenessNs: Int64?

  public init(kind: String = "none", maximumLatenessNs: Int64? = nil) {
    self.kind = kind
    self.maximumLatenessNs = maximumLatenessNs
  }
}

public struct MotionTraceOrderingPolicy: Codable, Equatable, Sendable {
  public let sampleReordering: MotionTraceSampleReordering

  public init(sampleReordering: MotionTraceSampleReordering = MotionTraceSampleReordering()) {
    self.sampleReordering = sampleReordering
  }
}

public struct MotionTraceHeader: Codable, Equatable, Sendable {
  public let recordType: String
  public let schemaVersion: String
  public let coreSpecVersion: String
  public let traceId: String
  public let producer: MotionTraceProducer
  public let privacy: MotionTracePrivacy
  public let conventions: MotionTraceConventions
  public let orderingPolicy: MotionTraceOrderingPolicy
  public let recorderLimits: MotionTraceRecorderLimits?
  public let session: MotionTraceSession
  public let capabilities: [MotionCapability]
  public let detectors: [MotionDetectorDescriptor]?
  public let device: MotionDeviceMetadata?

  public init(metadata: MotionTraceMetadata, limits: MotionTraceRecorderLimits) {
    recordType = "traceHeader"
    schemaVersion = MotionTraceV1.schemaVersion
    coreSpecVersion = MotionTraceV1.coreSpecVersion
    traceId = metadata.traceId
    producer = metadata.producer
    privacy = metadata.privacy
    conventions = MotionTraceConventions()
    orderingPolicy = MotionTraceOrderingPolicy()
    recorderLimits = limits
    session = metadata.session
    capabilities = metadata.capabilities
    detectors = metadata.detectors
    device = metadata.device
  }
}

final class MotionTraceRecordEncoder {
  private let encoder: JSONEncoder

  init() {
    encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
  }

  func line<T: Encodable>(for value: T) throws -> Data {
    var data = try encoder.encode(value)
    data.append(0x0A)
    return data
  }
}
