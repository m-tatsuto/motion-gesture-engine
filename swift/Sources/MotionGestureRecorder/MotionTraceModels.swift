import Foundation
import MotionGestureCore

public enum MotionTraceV1 {
  public static let schemaVersion = "1.0.0-draft.1"
  public static let coreSpecVersion = "1.0.0-draft.1"
  public static let maximumSafeInteger: Int64 = 9_007_199_254_740_991
}

public struct MotionTraceProducer: Codable, Equatable, Sendable {
  public let libraryName: String
  public let libraryVersion: String
  public let platformAdapterName: String
  public let platformAdapterVersion: String

  public init(
    libraryName: String,
    libraryVersion: String,
    platformAdapterName: String,
    platformAdapterVersion: String
  ) {
    self.libraryName = libraryName
    self.libraryVersion = libraryVersion
    self.platformAdapterName = platformAdapterName
    self.platformAdapterVersion = platformAdapterVersion
  }
}

public enum MotionTracePrivacyTier: String, Codable, Sendable {
  case synthetic
  case privateSensitive
  case reviewedSanitized
  case publicApproved
}

public enum MotionTraceDataClass: String, Codable, CaseIterable, Sendable {
  case motionSensorData
  case gestureAnnotation
  case userReport
  case exactDeviceModel
  case osMajorVersion
}

public struct MotionTracePrivacy: Codable, Equatable, Sendable {
  public let tier: MotionTracePrivacyTier
  public let dataClasses: [MotionTraceDataClass]
  public let reviewProtocolVersion: String?

  public init(
    tier: MotionTracePrivacyTier,
    dataClasses: [MotionTraceDataClass],
    reviewProtocolVersion: String? = nil
  ) {
    self.tier = tier
    self.dataClasses = dataClasses
    self.reviewProtocolVersion = reviewProtocolVersion
  }
}

public enum DisplayRotationClockwise: Int, Codable, Sendable {
  case degrees0 = 0
  case degrees90 = 90
  case degrees180 = 180
  case degrees270 = 270
}

public enum AttitudeReferenceKind: String, Codable, Sendable {
  case gravityAlignedSessionLocal
  case eastNorthUpMagnetic
  case eastNorthUpTrue
  case platformDefined
}

public enum AttitudeReferenceScope: String, Codable, Sendable {
  case session
  case global
}

public struct MotionAttitudeReference: Codable, Equatable, Sendable {
  public let kind: AttitudeReferenceKind
  public let scope: AttitudeReferenceScope
  public let referenceInstanceId: String?
  public let nativeReferenceId: String
  public let axisDefinition: String?

  public init(
    kind: AttitudeReferenceKind,
    scope: AttitudeReferenceScope,
    referenceInstanceId: String? = nil,
    nativeReferenceId: String,
    axisDefinition: String? = nil
  ) {
    self.kind = kind
    self.scope = scope
    self.referenceInstanceId = referenceInstanceId
    self.nativeReferenceId = nativeReferenceId
    self.axisDefinition = axisDefinition
  }
}

public struct MotionTraceSession: Codable, Equatable, Sendable {
  public let displayRotationClockwiseAtStart: DisplayRotationClockwise
  public let gestureFrameFromDeviceRowMajor: [Double]
  public let gestureFrameFrozen: Bool
  public let attitudeReference: MotionAttitudeReference?

  public init(
    displayRotationClockwiseAtStart: DisplayRotationClockwise,
    gestureFrameFromDeviceRowMajor: [Double],
    attitudeReference: MotionAttitudeReference? = nil
  ) {
    self.displayRotationClockwiseAtStart = displayRotationClockwiseAtStart
    self.gestureFrameFromDeviceRowMajor = gestureFrameFromDeviceRowMajor
    gestureFrameFrozen = true
    self.attitudeReference = attitudeReference
  }
}

public enum MotionSignalKind: String, Codable, Sendable {
  case gravity
  case userAcceleration
  case rotationRate
  case attitude
}

public enum MotionCapabilityRequirement: String, Codable, Sendable {
  case required
  case optional
}

public enum MotionBiasCorrection: String, Codable, Sendable {
  case raw
  case biasCorrected
  case notApplicable
}

public enum MotionCapabilityAvailability: String, Codable, Sendable {
  case available
  case unavailable
  case restricted
  case unknown
}

public enum MotionSourceKind: String, Codable, Sendable {
  case hardware
  case software
  case fused
  case unknown
}

public enum MotionNativeUnit: String, Codable, Sendable {
  case standardGravity
  case meterPerSecondSquared
  case radianPerSecond
  case unitQuaternion
  case platformDefined
}

public enum MotionNativeSignConvention: String, Codable, Sendable {
  case physicalGravity
  case specificForce
  case gravityRemovedAcceleration
  case rightHandAngularVelocity
  case referenceFromDevice
  case deviceFromReference
  case platformDefined
}

public enum MotionConversion: String, Codable, Sendable {
  case none
  case negate
  case divideByStandardGravity
  case axisTransform
  case quaternionReorder
  case quaternionInvert
  case referenceBasisTransform
}

public enum MotionAccuracyLevel: String, Codable, Sendable {
  case unreliable
  case low
  case medium
  case high
  case unknown
}

public struct MotionAccuracy: Codable, Equatable, Sendable {
  public let level: MotionAccuracyLevel
  public let nativeValue: Int32?

  public init(level: MotionAccuracyLevel, nativeValue: Int32? = nil) {
    self.level = level
    self.nativeValue = nativeValue
  }
}

public struct MotionRequestedTiming: Codable, Equatable, Sendable {
  public let intervalNs: Int64?
  public let nativeModeIdentifier: String?

  public init(intervalNs: Int64? = nil, nativeModeIdentifier: String? = nil) {
    self.intervalNs = intervalNs
    self.nativeModeIdentifier = nativeModeIdentifier
  }
}

public struct MotionSensorProperties: Codable, Equatable, Sendable {
  public let minimumDelayUs: Int64?
  public let maximumDelayUs: Int64?
  public let resolution: Double?

  public init(
    minimumDelayUs: Int64? = nil,
    maximumDelayUs: Int64? = nil,
    resolution: Double? = nil
  ) {
    self.minimumDelayUs = minimumDelayUs
    self.maximumDelayUs = maximumDelayUs
    self.resolution = resolution
  }
}

public struct MotionCapability: Codable, Equatable, Sendable {
  public let capabilityId: String
  public let signalKind: MotionSignalKind
  public let requirement: MotionCapabilityRequirement
  public let biasCorrection: MotionBiasCorrection
  public let availability: MotionCapabilityAvailability
  public let sourceKind: MotionSourceKind
  public let nativeSourceIdentifier: String
  public let nativeUnit: MotionNativeUnit
  public let nativeUnitIdentifier: String?
  public let nativeSignConvention: MotionNativeSignConvention
  public let nativeSignConventionIdentifier: String?
  public let conversions: [MotionConversion]
  public let requestedTiming: MotionRequestedTiming?
  public let sensorProperties: MotionSensorProperties?
  public let initialAccuracy: MotionAccuracy?

  public init(
    capabilityId: String,
    signalKind: MotionSignalKind,
    requirement: MotionCapabilityRequirement,
    biasCorrection: MotionBiasCorrection,
    availability: MotionCapabilityAvailability,
    sourceKind: MotionSourceKind,
    nativeSourceIdentifier: String,
    nativeUnit: MotionNativeUnit,
    nativeUnitIdentifier: String? = nil,
    nativeSignConvention: MotionNativeSignConvention,
    nativeSignConventionIdentifier: String? = nil,
    conversions: [MotionConversion],
    requestedTiming: MotionRequestedTiming? = nil,
    sensorProperties: MotionSensorProperties? = nil,
    initialAccuracy: MotionAccuracy? = nil
  ) {
    self.capabilityId = capabilityId
    self.signalKind = signalKind
    self.requirement = requirement
    self.biasCorrection = biasCorrection
    self.availability = availability
    self.sourceKind = sourceKind
    self.nativeSourceIdentifier = nativeSourceIdentifier
    self.nativeUnit = nativeUnit
    self.nativeUnitIdentifier = nativeUnitIdentifier
    self.nativeSignConvention = nativeSignConvention
    self.nativeSignConventionIdentifier = nativeSignConventionIdentifier
    self.conversions = conversions
    self.requestedTiming = requestedTiming
    self.sensorProperties = sensorProperties
    self.initialAccuracy = initialAccuracy
  }
}

public struct MotionDetectorDescriptor: Codable, Equatable, Sendable {
  public let detectorStreamId: String
  public let detectorId: String
  public let detectorVersion: String
  public let configurationIdentity: String

  public init(
    detectorStreamId: String,
    detectorId: String,
    detectorVersion: String,
    configurationIdentity: String
  ) {
    self.detectorStreamId = detectorStreamId
    self.detectorId = detectorId
    self.detectorVersion = detectorVersion
    self.configurationIdentity = configurationIdentity
  }
}

public enum MotionPlatformFamily: String, Codable, Sendable {
  case ios
  case android
  case other
}

public struct ExactDeviceModel: Codable, Equatable, Sendable {
  public let value: String
  public let privacyClass: String

  public init(value: String) {
    self.value = value
    privacyClass = "quasiIdentifier.exactDeviceModel"
  }
}

public struct MotionDeviceMetadata: Codable, Equatable, Sendable {
  public let platformFamily: MotionPlatformFamily
  public let osMajorVersion: Int?
  public let exactModel: ExactDeviceModel?

  public init(
    platformFamily: MotionPlatformFamily,
    osMajorVersion: Int? = nil,
    exactModel: ExactDeviceModel? = nil
  ) {
    self.platformFamily = platformFamily
    self.osMajorVersion = osMajorVersion
    self.exactModel = exactModel
  }
}

public struct MotionTraceMetadata: Codable, Equatable, Sendable {
  public let traceId: String
  public let producer: MotionTraceProducer
  public let privacy: MotionTracePrivacy
  public let session: MotionTraceSession
  public let capabilities: [MotionCapability]
  public let detectors: [MotionDetectorDescriptor]?
  public let device: MotionDeviceMetadata?

  public init(
    traceId: String,
    producer: MotionTraceProducer,
    privacy: MotionTracePrivacy,
    session: MotionTraceSession,
    capabilities: [MotionCapability],
    detectors: [MotionDetectorDescriptor]? = nil,
    device: MotionDeviceMetadata? = nil
  ) {
    self.traceId = traceId
    self.producer = producer
    self.privacy = privacy
    self.session = session
    self.capabilities = capabilities
    self.detectors = detectors
    self.device = device
  }
}

public struct MotionVector3: Codable, Equatable, Sendable {
  public let x: Double
  public let y: Double
  public let z: Double

  public init(x: Double, y: Double, z: Double) {
    self.x = x
    self.y = y
    self.z = z
  }

  public init(from decoder: Decoder) throws {
    var container = try decoder.unkeyedContainer()
    x = try container.decode(Double.self)
    y = try container.decode(Double.self)
    z = try container.decode(Double.self)
    guard container.isAtEnd else {
      throw DecodingError.dataCorruptedError(in: container, debugDescription: "Expected 3 values")
    }
  }

  public func encode(to encoder: Encoder) throws {
    var container = encoder.unkeyedContainer()
    try container.encode(x)
    try container.encode(y)
    try container.encode(z)
  }

  var values: [Double] { [x, y, z] }
}

public struct MotionQuaternion: Codable, Equatable, Sendable {
  public let x: Double
  public let y: Double
  public let z: Double
  public let w: Double

  public init(x: Double, y: Double, z: Double, w: Double) {
    self.x = x
    self.y = y
    self.z = z
    self.w = w
  }

  public init(from decoder: Decoder) throws {
    var container = try decoder.unkeyedContainer()
    x = try container.decode(Double.self)
    y = try container.decode(Double.self)
    z = try container.decode(Double.self)
    w = try container.decode(Double.self)
    guard container.isAtEnd else {
      throw DecodingError.dataCorruptedError(in: container, debugDescription: "Expected 4 values")
    }
  }

  public func encode(to encoder: Encoder) throws {
    var container = encoder.unkeyedContainer()
    try container.encode(x)
    try container.encode(y)
    try container.encode(z)
    try container.encode(w)
  }

  var values: [Double] { [x, y, z, w] }
}

public struct MotionVectorObservation: Codable, Equatable, Sendable {
  public let capabilityId: String
  public let value: MotionVector3
  public let accuracy: MotionAccuracy?

  public init(capabilityId: String, value: MotionVector3, accuracy: MotionAccuracy? = nil) {
    self.capabilityId = capabilityId
    self.value = value
    self.accuracy = accuracy
  }
}

public struct QuaternionNormalization: Codable, Equatable, Sendable {
  public let method: String
  public let originalNorm: Double

  public init(originalNorm: Double) {
    method = "normalizedToUnit"
    self.originalNorm = originalNorm
  }
}

public struct MotionQuaternionObservation: Codable, Equatable, Sendable {
  public let capabilityId: String
  public let value: MotionQuaternion
  public let accuracy: MotionAccuracy?
  public let normalization: QuaternionNormalization?

  public init(
    capabilityId: String,
    value: MotionQuaternion,
    accuracy: MotionAccuracy? = nil,
    normalization: QuaternionNormalization? = nil
  ) {
    self.capabilityId = capabilityId
    self.value = value
    self.accuracy = accuracy
    self.normalization = normalization
  }
}

public struct MotionSignals: Codable, Equatable, Sendable {
  public let gravity: MotionVectorObservation?
  public let userAcceleration: MotionVectorObservation?
  public let rotationRate: MotionVectorObservation?
  public let attitude: MotionQuaternionObservation?

  public init(
    gravity: MotionVectorObservation? = nil,
    userAcceleration: MotionVectorObservation? = nil,
    rotationRate: MotionVectorObservation? = nil,
    attitude: MotionQuaternionObservation? = nil
  ) {
    self.gravity = gravity
    self.userAcceleration = userAcceleration
    self.rotationRate = rotationRate
    self.attitude = attitude
  }

  var observations: [(MotionSignalKind, String, [Double])] {
    var result: [(MotionSignalKind, String, [Double])] = []
    if let gravity { result.append((.gravity, gravity.capabilityId, gravity.value.values)) }
    if let userAcceleration {
      result.append(
        (.userAcceleration, userAcceleration.capabilityId, userAcceleration.value.values))
    }
    if let rotationRate {
      result.append((.rotationRate, rotationRate.capabilityId, rotationRate.value.values))
    }
    if let attitude { result.append((.attitude, attitude.capabilityId, attitude.value.values)) }
    return result
  }
}

public struct MotionSample: Encodable, Equatable, Sendable {
  public let recordType = "sample"
  public let timestampNs: Int64
  public let sequence: Int64
  public let signals: MotionSignals

  public init(timestampNs: Int64, sequence: Int64, signals: MotionSignals) {
    self.timestampNs = timestampNs
    self.sequence = sequence
    self.signals = signals
  }
}

public struct MotionDisplayRotationChange: Encodable, Equatable, Sendable {
  public let recordType = "displayRotationChange"
  public let timestampNs: Int64
  public let changeSequence: Int64
  public let displayRotationClockwise: DisplayRotationClockwise

  public init(
    timestampNs: Int64,
    changeSequence: Int64,
    displayRotationClockwise: DisplayRotationClockwise
  ) {
    self.timestampNs = timestampNs
    self.changeSequence = changeSequence
    self.displayRotationClockwise = displayRotationClockwise
  }
}

public enum MotionAnnotationKind: String, Codable, Sendable {
  case gestureIntent
  case gestureOnset
  case gestureCommit
  case gestureEnd
  case neutralInterval
  case negativeWindow
  case userReportedProblem
}

public enum MotionAnnotationProvenanceKind: String, Codable, Sendable {
  case synthetic
  case contributor
  case userReport
  case reviewedGroundTruth
}

public struct MotionAnnotationProvenance: Codable, Equatable, Sendable {
  public let kind: MotionAnnotationProvenanceKind
  public let generatorId: String?
  public let generatorVersion: String?
  public let collectionProtocolVersion: String?
  public let reviewProtocolVersion: String?
  public let sourceAnnotationIds: [String]?

  public init(
    kind: MotionAnnotationProvenanceKind,
    generatorId: String? = nil,
    generatorVersion: String? = nil,
    collectionProtocolVersion: String? = nil,
    reviewProtocolVersion: String? = nil,
    sourceAnnotationIds: [String]? = nil
  ) {
    self.kind = kind
    self.generatorId = generatorId
    self.generatorVersion = generatorVersion
    self.collectionProtocolVersion = collectionProtocolVersion
    self.reviewProtocolVersion = reviewProtocolVersion
    self.sourceAnnotationIds = sourceAnnotationIds
  }
}

public enum MotionUserProblemCode: String, Codable, Sendable {
  case missedGesture
  case unexpectedGesture
  case wrongDirection
  case timing
  case other
}

public struct MotionUserReport: Codable, Equatable, Sendable {
  public let problemCode: MotionUserProblemCode
  public let expectedGesture: Gesture?
  public let observedGesture: Gesture?

  public init(
    problemCode: MotionUserProblemCode,
    expectedGesture: Gesture? = nil,
    observedGesture: Gesture? = nil
  ) {
    self.problemCode = problemCode
    self.expectedGesture = expectedGesture
    self.observedGesture = observedGesture
  }
}

public struct MotionAnnotation: Encodable, Equatable, Sendable {
  public let recordType = "annotation"
  public let annotationId: String
  public let annotationKind: MotionAnnotationKind
  public let timestampNs: Int64
  public let endTimestampNs: Int64?
  public let gesture: Gesture?
  public let provenance: MotionAnnotationProvenance
  public let report: MotionUserReport?

  public init(
    annotationId: String,
    annotationKind: MotionAnnotationKind,
    timestampNs: Int64,
    endTimestampNs: Int64? = nil,
    gesture: Gesture? = nil,
    provenance: MotionAnnotationProvenance,
    report: MotionUserReport? = nil
  ) {
    self.annotationId = annotationId
    self.annotationKind = annotationKind
    self.timestampNs = timestampNs
    self.endTimestampNs = endTimestampNs
    self.gesture = gesture
    self.provenance = provenance
    self.report = report
  }
}

public struct MotionTraceRecorderLimits: Codable, Equatable, Sendable {
  public let maximumDurationNs: Int64
  public let maximumSamples: Int64
  public let maximumBytes: Int64

  public init(maximumDurationNs: Int64, maximumSamples: Int64, maximumBytes: Int64) {
    self.maximumDurationNs = maximumDurationNs
    self.maximumSamples = maximumSamples
    self.maximumBytes = maximumBytes
  }
}

public enum MotionTraceFinalizationStatus: String, Codable, Sendable {
  case complete
  case bounded
  case cancelled
  case failed
}

public enum MotionTraceTerminationReason: String, Codable, Sendable {
  case requestedStop
  case durationLimit
  case sampleLimit
  case byteLimit
  case callerCancelled
  case sourceFailure
  case ioFailure
}

public enum DroppedSampleReason: String, Codable, CaseIterable, Sendable {
  case malformed
  case nonMonotonicTimestamp
  case bufferOverflow
  case writerBackpressure
  case limitReached
  case sourceFailure
  case unsupported
  case other
}

public struct MotionTraceRecordCounts: Codable, Equatable, Sendable {
  public internal(set) var samples: Int64
  public internal(set) var annotations: Int64
  public internal(set) var predictedEvents: Int64
  public internal(set) var displayRotationChanges: Int64
  public internal(set) var capabilityChanges: Int64

  public init(
    samples: Int64 = 0,
    annotations: Int64 = 0,
    predictedEvents: Int64 = 0,
    displayRotationChanges: Int64 = 0,
    capabilityChanges: Int64 = 0
  ) {
    self.samples = samples
    self.annotations = annotations
    self.predictedEvents = predictedEvents
    self.displayRotationChanges = displayRotationChanges
    self.capabilityChanges = capabilityChanges
  }
}

public struct DroppedSampleCount: Codable, Equatable, Sendable {
  public let reason: DroppedSampleReason
  public let count: Int64

  public init(reason: DroppedSampleReason, count: Int64) {
    self.reason = reason
    self.count = count
  }
}

public struct DroppedSampleSummary: Codable, Equatable, Sendable {
  public let total: Int64
  public let byReason: [DroppedSampleCount]

  public init(total: Int64, byReason: [DroppedSampleCount]) {
    self.total = total
    self.byReason = byReason
  }
}

public struct MotionObservedTiming: Codable, Equatable, Sendable {
  public let capabilityId: String
  public let acceptedObservationCount: Int64
  public let minimumIntervalNs: Int64?
  public let maximumIntervalNs: Int64?

  public init(
    capabilityId: String,
    acceptedObservationCount: Int64,
    minimumIntervalNs: Int64? = nil,
    maximumIntervalNs: Int64? = nil
  ) {
    self.capabilityId = capabilityId
    self.acceptedObservationCount = acceptedObservationCount
    self.minimumIntervalNs = minimumIntervalNs
    self.maximumIntervalNs = maximumIntervalNs
  }
}

public struct MotionTraceFooter: Encodable, Equatable, Sendable {
  public let recordType = "traceFooter"
  public let schemaVersion: String
  public let finalizationStatus: MotionTraceFinalizationStatus
  public let terminationReason: MotionTraceTerminationReason
  public let failureCode: String?
  public let durationNs: Int64
  public let recordCounts: MotionTraceRecordCounts
  public let reorderedSamples: Int64
  public let droppedSamples: DroppedSampleSummary
  public let observedTiming: [MotionObservedTiming]

  public init(
    finalizationStatus: MotionTraceFinalizationStatus,
    terminationReason: MotionTraceTerminationReason,
    failureCode: String? = nil,
    durationNs: Int64,
    recordCounts: MotionTraceRecordCounts,
    reorderedSamples: Int64 = 0,
    droppedSamples: DroppedSampleSummary,
    observedTiming: [MotionObservedTiming]
  ) {
    schemaVersion = MotionTraceV1.schemaVersion
    self.finalizationStatus = finalizationStatus
    self.terminationReason = terminationReason
    self.failureCode = failureCode
    self.durationNs = durationNs
    self.recordCounts = recordCounts
    self.reorderedSamples = reorderedSamples
    self.droppedSamples = droppedSamples
    self.observedTiming = observedTiming
  }
}
