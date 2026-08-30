import Foundation

enum SampleValidationResult {
  case valid
  case malformed(String)
  case unsupported(String)
}

enum MotionTraceValidation {
  private static let identifierPattern = "^[A-Za-z][A-Za-z0-9._:-]{0,127}$"
  private static let semanticVersionPattern =
    "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$"
  private static let uuidPattern =
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"

  static func validate(limits: MotionTraceRecorderLimits) throws {
    guard isPositiveSafeInteger(limits.maximumDurationNs) else {
      throw validationError("maximumDurationNs must be a positive wire-safe integer")
    }
    guard isPositiveSafeInteger(limits.maximumSamples) else {
      throw validationError("maximumSamples must be a positive wire-safe integer")
    }
    guard isPositiveSafeInteger(limits.maximumBytes) else {
      throw validationError("maximumBytes must be a positive wire-safe integer")
    }
  }

  static func validate(metadata: MotionTraceMetadata) throws {
    guard matches(metadata.traceId, pattern: uuidPattern) else {
      throw validationError("traceId is not a v1 UUID")
    }
    try validateIdentifier(metadata.producer.libraryName, field: "producer.libraryName")
    try validateSemanticVersion(metadata.producer.libraryVersion, field: "producer.libraryVersion")
    try validateIdentifier(
      metadata.producer.platformAdapterName, field: "producer.platformAdapterName")
    try validateSemanticVersion(
      metadata.producer.platformAdapterVersion,
      field: "producer.platformAdapterVersion"
    )

    let dataClasses = Set(metadata.privacy.dataClasses)
    guard dataClasses.count == metadata.privacy.dataClasses.count,
      (1...5).contains(dataClasses.count), dataClasses.contains(.motionSensorData)
    else {
      throw validationError("privacy.dataClasses must be unique and include motionSensorData")
    }
    switch metadata.privacy.tier {
    case .reviewedSanitized, .publicApproved:
      guard let version = metadata.privacy.reviewProtocolVersion else {
        throw validationError("reviewed privacy tiers require reviewProtocolVersion")
      }
      try validateIdentifier(version, field: "privacy.reviewProtocolVersion")
    case .synthetic, .privateSensitive:
      guard metadata.privacy.reviewProtocolVersion == nil else {
        throw validationError("reviewProtocolVersion is allowed only for reviewed privacy tiers")
      }
    }

    guard isOrthonormal(metadata.session.gestureFrameFromDeviceRowMajor) else {
      throw validationError("gestureFrameFromDeviceRowMajor must be right-handed and orthonormal")
    }
    if let reference = metadata.session.attitudeReference {
      try validate(reference: reference)
    }

    guard (1...64).contains(metadata.capabilities.count) else {
      throw validationError("capabilities must contain 1 through 64 entries")
    }
    var capabilityIds = Set<String>()
    for capability in metadata.capabilities {
      try validate(capability: capability)
      guard capabilityIds.insert(capability.capabilityId).inserted else {
        throw validationError("duplicate capabilityId \(capability.capabilityId)")
      }
    }

    if let detectors = metadata.detectors {
      guard (1...64).contains(detectors.count) else {
        throw validationError("detectors must contain 1 through 64 entries when present")
      }
      var streamIds = Set<String>()
      for detector in detectors {
        try validateIdentifier(detector.detectorStreamId, field: "detectorStreamId")
        try validateIdentifier(detector.detectorId, field: "detectorId")
        try validateSemanticVersion(detector.detectorVersion, field: "detectorVersion")
        try validateIdentifier(detector.configurationIdentity, field: "configurationIdentity")
        guard streamIds.insert(detector.detectorStreamId).inserted else {
          throw validationError("duplicate detectorStreamId \(detector.detectorStreamId)")
        }
      }
    }

    if let device = metadata.device {
      if let osMajorVersion = device.osMajorVersion {
        guard (1...999).contains(osMajorVersion), dataClasses.contains(.osMajorVersion) else {
          throw validationError("osMajorVersion must be declared in privacy.dataClasses")
        }
      }
      if let model = device.exactModel {
        guard (1...64).contains(model.value.count), dataClasses.contains(.exactDeviceModel) else {
          throw validationError("exactModel must be declared in privacy.dataClasses")
        }
      }
    }
  }

  static func validate(
    sample: MotionSample,
    capabilities: [String: MotionCapability],
    attitudeReferencePresent: Bool
  ) -> SampleValidationResult {
    guard isSafeInteger(sample.timestampNs), isSafeInteger(sample.sequence) else {
      return .malformed("sample time and sequence must be wire-safe integers")
    }
    let observations = sample.signals.observations
    guard !observations.isEmpty else {
      return .malformed("sample must contain at least one signal")
    }

    for (signalKind, capabilityId, values) in observations {
      guard values.allSatisfy(\.isFinite) else {
        return .malformed("sample contains a non-finite signal value")
      }
      guard let capability = capabilities[capabilityId] else {
        return .unsupported("sample references unknown capability \(capabilityId)")
      }
      guard capability.signalKind == signalKind, capability.availability == .available else {
        return .unsupported("capability \(capabilityId) cannot provide \(signalKind.rawValue)")
      }
    }

    if let attitude = sample.signals.attitude {
      guard attitudeReferencePresent else {
        return .unsupported("attitude sample requires a session attitude reference")
      }
      let norm = sqrt(attitude.value.values.reduce(0) { $0 + $1 * $1 })
      guard abs(norm - 1) <= 0.001 else {
        return .malformed("attitude quaternion is outside the v1 norm tolerance")
      }
      if let normalization = attitude.normalization,
        !(normalization.originalNorm.isFinite && normalization.originalNorm > 0)
      {
        return .malformed("quaternion normalization originalNorm must be finite and positive")
      }
    }

    return .valid
  }

  static func validate(annotation: MotionAnnotation, privacy: MotionTracePrivacy) throws {
    guard matches(annotation.annotationId, pattern: uuidPattern) else {
      throw sampleError("annotationId is not a v1 UUID")
    }
    guard isSafeInteger(annotation.timestampNs) else {
      throw sampleError("annotation timestamp must be a wire-safe integer")
    }
    if let end = annotation.endTimestampNs {
      guard isSafeInteger(end), end >= annotation.timestampNs else {
        throw sampleError("annotation interval end precedes its start")
      }
    }
    guard privacy.dataClasses.contains(.gestureAnnotation) else {
      throw sampleError("gestureAnnotation is not declared in privacy.dataClasses")
    }

    switch annotation.annotationKind {
    case .gestureIntent, .gestureOnset, .gestureCommit, .gestureEnd:
      guard annotation.gesture != nil, annotation.endTimestampNs == nil else {
        throw sampleError("gesture annotations require gesture and no interval end")
      }
    case .neutralInterval, .negativeWindow:
      guard annotation.endTimestampNs != nil, annotation.gesture == nil else {
        throw sampleError("interval annotations require endTimestampNs and no gesture")
      }
    case .userReportedProblem:
      guard annotation.report != nil,
        annotation.provenance.kind == .userReport,
        privacy.dataClasses.contains(.userReport)
      else {
        throw sampleError("user report annotation fields or privacy declaration are incomplete")
      }
    }
    if annotation.annotationKind != .userReportedProblem, annotation.report != nil {
      throw sampleError("report is allowed only for userReportedProblem")
    }
    try validate(provenance: annotation.provenance)
  }

  static func isSafeInteger(_ value: Int64) -> Bool {
    (0...MotionTraceV1.maximumSafeInteger).contains(value)
  }

  private static func isPositiveSafeInteger(_ value: Int64) -> Bool {
    (1...MotionTraceV1.maximumSafeInteger).contains(value)
  }

  private static func validate(capability: MotionCapability) throws {
    try validateIdentifier(capability.capabilityId, field: "capabilityId")
    try validateIdentifier(capability.nativeSourceIdentifier, field: "nativeSourceIdentifier")
    guard capability.requirement != .required || capability.availability == .available else {
      throw validationError("required capability \(capability.capabilityId) is not available")
    }
    guard (capability.signalKind == .rotationRate) == (capability.biasCorrection != .notApplicable)
    else {
      throw validationError("biasCorrection does not match \(capability.signalKind.rawValue)")
    }
    guard (1...7).contains(capability.conversions.count),
      Set(capability.conversions).count == capability.conversions.count,
      !capability.conversions.contains(.none) || capability.conversions.count == 1
    else {
      throw validationError("capability conversions are empty, duplicated, or combine none")
    }
    if capability.nativeUnit == .platformDefined {
      guard let identifier = capability.nativeUnitIdentifier else {
        throw validationError("platformDefined nativeUnit requires an identifier")
      }
      try validateIdentifier(identifier, field: "nativeUnitIdentifier")
    } else if capability.nativeUnitIdentifier != nil {
      throw validationError("nativeUnitIdentifier requires platformDefined nativeUnit")
    }
    if capability.nativeSignConvention == .platformDefined {
      guard let identifier = capability.nativeSignConventionIdentifier else {
        throw validationError("platformDefined sign convention requires an identifier")
      }
      try validateIdentifier(identifier, field: "nativeSignConventionIdentifier")
    } else if capability.nativeSignConventionIdentifier != nil {
      throw validationError("nativeSignConventionIdentifier requires platformDefined convention")
    }
    if let timing = capability.requestedTiming {
      guard timing.intervalNs != nil || timing.nativeModeIdentifier != nil else {
        throw validationError("requestedTiming cannot be empty")
      }
      if let interval = timing.intervalNs, !isPositiveSafeInteger(interval) {
        throw validationError("requested interval must be a positive wire-safe integer")
      }
      if let mode = timing.nativeModeIdentifier {
        try validateIdentifier(mode, field: "nativeModeIdentifier")
      }
    }
    if let properties = capability.sensorProperties {
      guard
        properties.minimumDelayUs != nil || properties.maximumDelayUs != nil
          || properties.resolution != nil
      else {
        throw validationError("sensorProperties cannot be empty")
      }
      if let minimum = properties.minimumDelayUs, !isSafeInteger(minimum) {
        throw validationError("minimumDelayUs must be wire-safe")
      }
      if let maximum = properties.maximumDelayUs, !isSafeInteger(maximum) {
        throw validationError("maximumDelayUs must be wire-safe")
      }
      if let resolution = properties.resolution, !(resolution.isFinite && resolution >= 0) {
        throw validationError("sensor resolution must be finite and non-negative")
      }
    }
  }

  private static func validate(reference: MotionAttitudeReference) throws {
    try validateIdentifier(reference.nativeReferenceId, field: "nativeReferenceId")
    switch reference.kind {
    case .gravityAlignedSessionLocal:
      guard reference.scope == .session, let id = reference.referenceInstanceId,
        reference.axisDefinition == nil
      else {
        throw validationError("gravityAlignedSessionLocal reference fields are invalid")
      }
      try validateUUID(id, field: "referenceInstanceId")
    case .eastNorthUpMagnetic, .eastNorthUpTrue:
      guard reference.scope == .global, reference.referenceInstanceId == nil,
        reference.axisDefinition == nil
      else {
        throw validationError("global east/north/up reference fields are invalid")
      }
    case .platformDefined:
      guard let axisDefinition = reference.axisDefinition,
        (1...256).contains(axisDefinition.count)
      else {
        throw validationError("platformDefined reference requires axisDefinition")
      }
      if reference.scope == .session {
        guard let id = reference.referenceInstanceId else {
          throw validationError("session platformDefined reference requires referenceInstanceId")
        }
        try validateUUID(id, field: "referenceInstanceId")
      } else if reference.referenceInstanceId != nil {
        throw validationError("global platformDefined reference cannot have referenceInstanceId")
      }
    }
  }

  private static func validate(provenance: MotionAnnotationProvenance) throws {
    switch provenance.kind {
    case .synthetic:
      guard let generatorId = provenance.generatorId,
        let generatorVersion = provenance.generatorVersion,
        provenance.collectionProtocolVersion == nil,
        provenance.reviewProtocolVersion == nil,
        provenance.sourceAnnotationIds == nil
      else { throw sampleError("synthetic provenance fields are invalid") }
      try validateIdentifier(generatorId, field: "generatorId", sample: true)
      try validateSemanticVersion(generatorVersion, field: "generatorVersion", sample: true)
    case .contributor, .userReport:
      guard let version = provenance.collectionProtocolVersion,
        provenance.generatorId == nil,
        provenance.generatorVersion == nil,
        provenance.reviewProtocolVersion == nil,
        provenance.sourceAnnotationIds == nil
      else { throw sampleError("collection provenance fields are invalid") }
      try validateIdentifier(version, field: "collectionProtocolVersion", sample: true)
    case .reviewedGroundTruth:
      guard let version = provenance.reviewProtocolVersion,
        let sourceIds = provenance.sourceAnnotationIds,
        (1...64).contains(sourceIds.count),
        Set(sourceIds).count == sourceIds.count,
        provenance.generatorId == nil,
        provenance.generatorVersion == nil,
        provenance.collectionProtocolVersion == nil
      else { throw sampleError("reviewed provenance fields are invalid") }
      try validateIdentifier(version, field: "reviewProtocolVersion", sample: true)
      for id in sourceIds { try validateUUID(id, field: "sourceAnnotationIds", sample: true) }
    }
  }

  private static func validateIdentifier(_ value: String, field: String, sample: Bool = false)
    throws
  {
    guard matches(value, pattern: identifierPattern) else {
      throw sample
        ? sampleError("\(field) is not a v1 identifier")
        : validationError("\(field) is not a v1 identifier")
    }
  }

  private static func validateSemanticVersion(
    _ value: String,
    field: String,
    sample: Bool = false
  ) throws {
    guard value.count <= 128, matches(value, pattern: semanticVersionPattern) else {
      throw sample
        ? sampleError("\(field) is not semantic version")
        : validationError("\(field) is not semantic version")
    }
  }

  private static func validateUUID(_ value: String, field: String, sample: Bool = false) throws {
    guard matches(value, pattern: uuidPattern) else {
      throw sample
        ? sampleError("\(field) is not a v1 UUID") : validationError("\(field) is not a v1 UUID")
    }
  }

  private static func matches(_ value: String, pattern: String) -> Bool {
    value.range(of: pattern, options: .regularExpression) != nil
  }

  private static func isOrthonormal(_ matrix: [Double]) -> Bool {
    guard matrix.count == 9, matrix.allSatisfy(\.isFinite) else { return false }
    let rows = [Array(matrix[0..<3]), Array(matrix[3..<6]), Array(matrix[6..<9])]
    func dot(_ lhs: [Double], _ rhs: [Double]) -> Double {
      zip(lhs, rhs).reduce(0) { $0 + $1.0 * $1.1 }
    }
    for row in rows where abs(dot(row, row) - 1) > 0.000_001 { return false }
    guard abs(dot(rows[0], rows[1])) <= 0.000_001,
      abs(dot(rows[0], rows[2])) <= 0.000_001,
      abs(dot(rows[1], rows[2])) <= 0.000_001
    else { return false }
    let cross = [
      rows[1][1] * rows[2][2] - rows[1][2] * rows[2][1],
      rows[1][2] * rows[2][0] - rows[1][0] * rows[2][2],
      rows[1][0] * rows[2][1] - rows[1][1] * rows[2][0],
    ]
    return abs(dot(rows[0], cross) - 1) <= 0.000_001
  }

  private static func validationError(_ diagnostic: String) -> MotionTraceRecorderError {
    MotionTraceRecorderError(
      code: .invalidConfiguration,
      stage: .start,
      diagnostic: diagnostic
    )
  }

  private static func sampleError(_ diagnostic: String) -> MotionTraceRecorderError {
    MotionTraceRecorderError(code: .invalidSample, stage: .append, diagnostic: diagnostic)
  }
}
