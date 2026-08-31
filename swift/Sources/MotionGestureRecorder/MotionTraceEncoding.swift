import Foundation

struct MotionTraceConventions: Encodable {
  let storedVectorFrame = "deviceD"
  let gravityUnit = "standardGravity"
  let userAccelerationUnit = "standardGravity"
  let rotationRateUnit = "radianPerSecond"
  let attitudeQuaternion = "xyzwReferenceFromDevice"
  let attitudeQuaternionNormTolerance = 0.001
  let frameOrthonormalTolerance = 0.000_001
  let standardGravityMps2 = 9.80665
  let timestampUnit = "nanosecond"
  let timestampOrigin = "sessionMonotonicOrigin"
  let sampleOrdering = "timestampThenSequence"
}

struct MotionTraceSampleReordering: Encodable {
  let kind = "none"
}

struct MotionTraceOrderingPolicy: Encodable {
  let sampleReordering = MotionTraceSampleReordering()
}

struct MotionTraceHeader: Encodable {
  let recordType = "traceHeader"
  let schemaVersion = MotionTraceV1.schemaVersion
  let coreSpecVersion = MotionTraceV1.coreSpecVersion
  let traceId: String
  let producer: MotionTraceProducer
  let privacy: MotionTracePrivacy
  let conventions = MotionTraceConventions()
  let orderingPolicy = MotionTraceOrderingPolicy()
  let recorderLimits: MotionTraceRecorderLimits
  let session: MotionTraceSession
  let capabilities: [MotionCapability]
  let detectors: [MotionDetectorDescriptor]?
  let device: MotionDeviceMetadata?

  init(metadata: MotionTraceMetadata, limits: MotionTraceRecorderLimits) {
    traceId = metadata.traceId
    producer = metadata.producer
    privacy = metadata.privacy
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
