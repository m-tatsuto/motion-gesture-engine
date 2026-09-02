import Foundation
import MotionGestureRecorder

public enum MotionTraceReplayLoader {
  public static func load(
    data: Data,
    limits: ReplayLimits = ReplayLimits()
  ) throws -> ValidatedReplayTrace {
    try validateContainer(data, limits: limits)
    guard let text = String(data: data, encoding: .utf8) else {
      throw loadError(.invalidContainer, "trace is not valid UTF-8")
    }

    let lines = String(text.dropLast()).split(separator: "\n", omittingEmptySubsequences: false)
    if lines.contains(where: { $0.isEmpty }) {
      throw loadError(.invalidContainer, "blank JSON Lines are forbidden")
    }
    if lines.count > limits.maximumBodyRecords + 2 {
      throw loadError(.limitExceeded, "trace exceeds \(limits.maximumBodyRecords) body records")
    }

    let decoder = JSONDecoder()
    var header: MotionTraceHeader?
    var footer: MotionTraceFooter?
    var samples: [MotionSample] = []
    var validator: ReplayTraceValidator?

    for (index, line) in lines.enumerated() {
      let lineNumber = index + 1
      let lineData = Data(line.utf8)
      if lineData.count > limits.maximumLineBytes {
        throw loadError(
          .limitExceeded,
          "JSON line exceeds \(limits.maximumLineBytes) bytes",
          line: lineNumber
        )
      }
      if footer != nil {
        throw loadError(.malformedRecord, "record follows traceFooter", line: lineNumber)
      }

      let object = try parseObject(lineData, line: lineNumber)
      guard let recordType = object["recordType"] as? String else {
        throw loadError(
          .malformedRecord,
          "recordType is missing or is not a string",
          line: lineNumber
        )
      }
      try validateTopLevelKeys(object, recordType: recordType, line: lineNumber)

      if lineNumber == 1, recordType != "traceHeader" {
        throw loadError(.malformedRecord, "line 1 must be traceHeader", line: lineNumber)
      }
      if recordType == "traceHeader", lineNumber != 1 {
        throw loadError(
          .malformedRecord,
          "traceHeader is allowed only on line 1",
          line: lineNumber
        )
      }

      switch recordType {
      case "traceHeader":
        try checkVersion(
          object,
          field: "schemaVersion",
          supported: MotionTraceV1.schemaVersion,
          line: lineNumber
        )
        try checkVersion(
          object,
          field: "coreSpecVersion",
          supported: MotionTraceV1.coreSpecVersion,
          line: lineNumber,
          spec: true
        )
        let decoded: MotionTraceHeader = try decode(decoder, data: lineData, line: lineNumber)
        let traceValidator = ReplayTraceValidator(header: decoded, containerBytes: data.count)
        try traceValidator.validateHeader()
        header = decoded
        validator = traceValidator

      case "sample":
        let decoded: MotionSample = try decode(decoder, data: lineData, line: lineNumber)
        try requireValidator(validator, line: lineNumber).sample(decoded, line: lineNumber)
        samples.append(decoded)

      case "annotation":
        let decoded: MotionAnnotation = try decode(decoder, data: lineData, line: lineNumber)
        try requireValidator(validator, line: lineNumber).annotation(decoded, line: lineNumber)

      case "predictedEvent":
        let decoded: MotionPredictedEventRecord = try decode(
          decoder,
          data: lineData,
          line: lineNumber
        )
        try requireValidator(validator, line: lineNumber).prediction(decoded, line: lineNumber)

      case "displayRotationChange":
        let decoded: MotionDisplayRotationChange = try decode(
          decoder,
          data: lineData,
          line: lineNumber
        )
        try requireValidator(validator, line: lineNumber).displayChange(decoded, line: lineNumber)

      case "capabilityChange":
        let decoded: MotionCapabilityChange = try decode(
          decoder,
          data: lineData,
          line: lineNumber
        )
        try requireValidator(validator, line: lineNumber).capabilityChange(decoded, line: lineNumber)

      case "traceFooter":
        try checkVersion(
          object,
          field: "schemaVersion",
          supported: MotionTraceV1.schemaVersion,
          line: lineNumber
        )
        let decoded: MotionTraceFooter = try decode(decoder, data: lineData, line: lineNumber)
        try requireValidator(validator, line: lineNumber).footer(decoded, line: lineNumber)
        footer = decoded

      default:
        throw loadError(
          .malformedRecord,
          "unknown recordType \(recordType)",
          line: lineNumber
        )
      }
    }

    guard let header else {
      throw loadError(.incompleteTrace, "traceHeader is missing")
    }
    guard let footer else {
      throw loadError(.incompleteTrace, "valid terminal traceFooter is missing")
    }
    return ValidatedReplayTrace(header: header, samples: samples, footer: footer)
  }

  private static func validateContainer(_ data: Data, limits: ReplayLimits) throws {
    if data.count > limits.maximumBytes {
      throw loadError(.limitExceeded, "trace exceeds \(limits.maximumBytes) bytes")
    }
    if data.count >= 2, data[data.startIndex] == 0x1F, data[data.startIndex + 1] == 0x8B {
      throw loadError(
        .unsupportedCompression,
        "gzip input must be decompressed before native replay"
      )
    }
    if data.count >= 3, data.prefix(3) == Data([0xEF, 0xBB, 0xBF]) {
      throw loadError(.invalidContainer, "UTF-8 BOM is forbidden")
    }
    if data.contains(0x0D) {
      throw loadError(.invalidContainer, "JSON Lines must use LF, not CRLF")
    }
    if data.isEmpty || data.last != 0x0A {
      throw loadError(.incompleteTrace, "trace must end with LF and a complete footer")
    }
  }

  private static func parseObject(_ data: Data, line: Int) throws -> [String: Any] {
    do {
      guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw loadError(.malformedRecord, "line is not a JSON object", line: line)
      }
      return object
    } catch let error as ReplayError {
      throw error
    } catch {
      throw loadError(
        .malformedRecord,
        "line is not valid JSON: \(error.localizedDescription)",
        line: line
      )
    }
  }

  private static func decode<T: Decodable>(
    _ decoder: JSONDecoder,
    data: Data,
    line: Int
  ) throws -> T {
    do {
      return try decoder.decode(T.self, from: data)
    } catch {
      throw loadError(
        .malformedRecord,
        "record does not match Motion Trace v1: \(error.localizedDescription)",
        line: line
      )
    }
  }

  private static func checkVersion(
    _ object: [String: Any],
    field: String,
    supported: String,
    line: Int,
    spec: Bool = false
  ) throws {
    guard let actual = object[field] as? String else {
      throw loadError(.malformedRecord, "\(field) is missing", line: line)
    }
    if actual != supported {
      throw loadError(
        spec ? .unsupportedSpecVersion : .unsupportedSchemaVersion,
        "unsupported \(field) \(actual)",
        line: line
      )
    }
  }

  private static func requireValidator(
    _ validator: ReplayTraceValidator?,
    line: Int
  ) throws -> ReplayTraceValidator {
    guard let validator else {
      throw loadError(.malformedRecord, "traceHeader must precede body records", line: line)
    }
    return validator
  }

  private static func validateTopLevelKeys(
    _ object: [String: Any],
    recordType: String,
    line: Int
  ) throws {
    let allowed: Set<String>
    switch recordType {
    case "traceHeader":
      allowed = [
        "recordType", "schemaVersion", "coreSpecVersion", "traceId", "producer", "privacy",
        "conventions", "orderingPolicy", "recorderLimits", "session", "capabilities",
        "detectors", "device",
      ]
    case "sample":
      allowed = ["recordType", "timestampNs", "sequence", "signals"]
    case "annotation":
      allowed = [
        "recordType", "annotationId", "annotationKind", "timestampNs", "endTimestampNs",
        "gesture", "provenance", "report",
      ]
    case "predictedEvent":
      allowed = [
        "recordType", "eventId", "detectorStreamId", "eventSequence", "timestampNs", "gesture",
        "sourceSampleSequence",
      ]
    case "displayRotationChange":
      allowed = ["recordType", "timestampNs", "changeSequence", "displayRotationClockwise"]
    case "capabilityChange":
      allowed = [
        "recordType", "timestampNs", "changeSequence", "capabilityId", "availability", "accuracy",
      ]
    case "traceFooter":
      allowed = [
        "recordType", "schemaVersion", "finalizationStatus", "terminationReason", "failureCode",
        "durationNs", "recordCounts", "reorderedSamples", "droppedSamples", "observedTiming",
      ]
    default:
      return
    }
    let unknown = Set(object.keys).subtracting(allowed)
    if !unknown.isEmpty {
      throw loadError(
        .malformedRecord,
        "unknown top-level fields: \(unknown.sorted().joined(separator: ", "))",
        line: line
      )
    }
  }
}

private final class ReplayTraceValidator {
  private let header: MotionTraceHeader
  private let containerBytes: Int
  private var capabilities: [String: MotionCapability] = [:]
  private var availability: [String: MotionCapabilityAvailability] = [:]
  private var timing: [String: ReplayTiming] = [:]
  private var capabilityOrder: [String] = []
  private var sampleSequences: Set<Int64> = []
  private var eventIds: Set<String> = []
  private var eventSequences: [String: Set<Int64>] = [:]
  private var lastPredictions: [String: (Int64, Int64)] = [:]
  private var predictionSampleReferences: [(Int64, Int)] = []
  private var displayChangeSequences: Set<Int64> = []
  private var capabilityChangeSequences: Set<Int64> = []
  private var lastSample: (Int64, Int64)?
  private var maximumTimestampNs: Int64 = 0
  private var counts = ReplayCounts()
  private var detectors: [String: MotionDetectorDescriptor] = [:]

  init(header: MotionTraceHeader, containerBytes: Int) {
    self.header = header
    self.containerBytes = containerBytes
  }

  func validateHeader() throws {
    if header.recordType != "traceHeader" {
      throw malformed("invalid traceHeader recordType", line: 1)
    }
    let conventions = header.conventions
    if conventions.storedVectorFrame != "deviceD"
      || conventions.gravityUnit != "standardGravity"
      || conventions.userAccelerationUnit != "standardGravity"
      || conventions.rotationRateUnit != "radianPerSecond"
      || conventions.attitudeQuaternion != "xyzwReferenceFromDevice"
      || conventions.timestampUnit != "nanosecond"
      || conventions.timestampOrigin != "sessionMonotonicOrigin"
      || conventions.sampleOrdering != "timestampThenSequence"
      || conventions.standardGravityMps2 != 9.80665
    {
      throw malformed("unsupported Motion Trace convention", line: 1)
    }
    if !conventions.attitudeQuaternionNormTolerance.isFinite
      || conventions.attitudeQuaternionNormTolerance < 0
    {
      throw malformed("invalid quaternion norm tolerance", line: 1)
    }
    if !isOrthonormal(
      header.session.gestureFrameFromDeviceRowMajor,
      tolerance: conventions.frameOrthonormalTolerance
    ) {
      throw malformed("gesture frame must be right-handed and orthonormal", line: 1)
    }
    if !header.session.gestureFrameFrozen {
      throw malformed("gesture frame must be frozen", line: 1)
    }
    if header.capabilities.isEmpty {
      throw malformed("at least one capability is required", line: 1)
    }
    for capability in header.capabilities {
      if capabilities.updateValue(capability, forKey: capability.capabilityId) != nil {
        throw malformed("duplicate capability \(capability.capabilityId)", line: 1)
      }
      if capability.requirement == .required, capability.availability != .available {
        throw malformed("required capability \(capability.capabilityId) is unavailable", line: 1)
      }
      if capability.conversions.isEmpty
        || (capability.conversions.contains(.none) && capability.conversions.count != 1)
      {
        throw malformed("invalid conversions for \(capability.capabilityId)", line: 1)
      }
      availability[capability.capabilityId] = capability.availability
      timing[capability.capabilityId] = ReplayTiming()
      capabilityOrder.append(capability.capabilityId)
    }
    for detector in header.detectors ?? [] {
      if detectors.updateValue(detector, forKey: detector.detectorStreamId) != nil {
        throw malformed("duplicate detectorStreamId", line: 1)
      }
    }
    let reordering = header.orderingPolicy.sampleReordering
    if reordering.kind == "none", reordering.maximumLatenessNs != nil {
      throw malformed("none reordering cannot declare maximumLatenessNs", line: 1)
    }
    if reordering.kind == "bounded", !isSafe(reordering.maximumLatenessNs) {
      throw malformed("bounded reordering requires safe maximumLatenessNs", line: 1)
    }
    if reordering.kind != "none", reordering.kind != "bounded" {
      throw malformed("unsupported sample reordering \(reordering.kind)", line: 1)
    }
  }

  func sample(_ sample: MotionSample, line: Int) throws {
    if sample.recordType != "sample" || !isSafe(sample.timestampNs) || !isSafe(sample.sequence) {
      throw malformed("sample timestamp and sequence must be wire-safe", line: line)
    }
    if !sampleSequences.insert(sample.sequence).inserted {
      throw malformed("duplicate sample sequence", line: line)
    }
    let pair = (sample.timestampNs, sample.sequence)
    if let previous = lastSample,
      pair.0 < previous.0 || (pair.0 == previous.0 && pair.1 <= previous.1)
    {
      throw loadError(
        .nonMonotonicSample,
        "samples are not ordered by (timestampNs, sequence)",
        line: line
      )
    }
    lastSample = pair
    maximumTimestampNs = max(maximumTimestampNs, sample.timestampNs)

    var observationCount = 0
    if let observation = sample.signals.gravity {
      try validateObservation(
        .gravity,
        capabilityId: observation.capabilityId,
        values: [observation.value.x, observation.value.y, observation.value.z],
        line: line
      )
      observe(observation.capabilityId, timestampNs: sample.timestampNs)
      observationCount += 1
    }
    if let observation = sample.signals.userAcceleration {
      try validateObservation(
        .userAcceleration,
        capabilityId: observation.capabilityId,
        values: [observation.value.x, observation.value.y, observation.value.z],
        line: line
      )
      observe(observation.capabilityId, timestampNs: sample.timestampNs)
      observationCount += 1
    }
    if let observation = sample.signals.rotationRate {
      try validateObservation(
        .rotationRate,
        capabilityId: observation.capabilityId,
        values: [observation.value.x, observation.value.y, observation.value.z],
        line: line
      )
      observe(observation.capabilityId, timestampNs: sample.timestampNs)
      observationCount += 1
    }
    if let observation = sample.signals.attitude {
      let values = [
        observation.value.x, observation.value.y, observation.value.z, observation.value.w,
      ]
      try validateObservation(
        .attitude,
        capabilityId: observation.capabilityId,
        values: values,
        line: line
      )
      if header.session.attitudeReference == nil {
        throw malformed("attitude requires a session reference", line: line)
      }
      let norm = sqrt(values.reduce(0) { $0 + $1 * $1 })
      if abs(norm - 1) > header.conventions.attitudeQuaternionNormTolerance {
        throw malformed("attitude quaternion is outside norm tolerance", line: line)
      }
      observe(observation.capabilityId, timestampNs: sample.timestampNs)
      observationCount += 1
    }
    if observationCount == 0 {
      throw malformed("sample contains no signals", line: line)
    }
    counts.samples += 1
  }

  func annotation(_ annotation: MotionAnnotation, line: Int) throws {
    if annotation.recordType != "annotation" || !isSafe(annotation.timestampNs)
      || (annotation.endTimestampNs != nil && !isSafe(annotation.endTimestampNs))
    {
      throw malformed("invalid annotation", line: line)
    }
    if let end = annotation.endTimestampNs, end < annotation.timestampNs {
      throw malformed("annotation end precedes start", line: line)
    }
    maximumTimestampNs = max(
      maximumTimestampNs,
      annotation.endTimestampNs ?? annotation.timestampNs
    )
    counts.annotations += 1
  }

  func prediction(_ prediction: MotionPredictedEventRecord, line: Int) throws {
    if prediction.recordType != "predictedEvent" || !isSafe(prediction.timestampNs)
      || !isSafe(prediction.eventSequence)
      || (prediction.sourceSampleSequence != nil && !isSafe(prediction.sourceSampleSequence))
    {
      throw malformed("invalid predicted event", line: line)
    }
    if detectors[prediction.detectorStreamId] == nil {
      throw malformed("unknown detector stream \(prediction.detectorStreamId)", line: line)
    }
    if !eventIds.insert(prediction.eventId).inserted {
      throw malformed("duplicate predicted event ID", line: line)
    }
    var sequences = eventSequences[prediction.detectorStreamId] ?? []
    if !sequences.insert(prediction.eventSequence).inserted {
      throw malformed("duplicate event sequence", line: line)
    }
    eventSequences[prediction.detectorStreamId] = sequences
    let pair = (prediction.timestampNs, prediction.eventSequence)
    if let previous = lastPredictions[prediction.detectorStreamId],
      pair.0 < previous.0 || (pair.0 == previous.0 && pair.1 <= previous.1)
    {
      throw malformed("predicted events are not ordered", line: line)
    }
    lastPredictions[prediction.detectorStreamId] = pair
    if let sourceSequence = prediction.sourceSampleSequence {
      predictionSampleReferences.append((sourceSequence, line))
    }
    maximumTimestampNs = max(maximumTimestampNs, prediction.timestampNs)
    counts.predictedEvents += 1
  }

  func displayChange(_ change: MotionDisplayRotationChange, line: Int) throws {
    if change.recordType != "displayRotationChange" || !isSafe(change.timestampNs)
      || !isSafe(change.changeSequence)
    {
      throw malformed("invalid display rotation change", line: line)
    }
    if !displayChangeSequences.insert(change.changeSequence).inserted {
      throw malformed("duplicate display change sequence", line: line)
    }
    maximumTimestampNs = max(maximumTimestampNs, change.timestampNs)
    counts.displayRotationChanges += 1
  }

  func capabilityChange(_ change: MotionCapabilityChange, line: Int) throws {
    if change.recordType != "capabilityChange" || !isSafe(change.timestampNs)
      || !isSafe(change.changeSequence) || capabilities[change.capabilityId] == nil
      || (change.availability == nil && change.accuracy == nil)
    {
      throw malformed("invalid capability change", line: line)
    }
    if !capabilityChangeSequences.insert(change.changeSequence).inserted {
      throw malformed("duplicate capability change sequence", line: line)
    }
    if let changedAvailability = change.availability {
      availability[change.capabilityId] = changedAvailability
    }
    maximumTimestampNs = max(maximumTimestampNs, change.timestampNs)
    counts.capabilityChanges += 1
  }

  func footer(_ footer: MotionTraceFooter, line: Int) throws {
    if footer.recordType != "traceFooter" || footer.schemaVersion != MotionTraceV1.schemaVersion {
      throw malformed("invalid trace footer", line: line)
    }
    if footer.finalizationStatus != .complete && footer.finalizationStatus != .bounded {
      throw loadError(
        .incompleteTrace,
        "only finalized-complete traces can be replayed",
        line: line
      )
    }
    if !isSafe(footer.durationNs) || footer.durationNs < maximumTimestampNs {
      throw footerMismatch("footer duration precedes trace records", line: line)
    }
    if !counts.matches(footer.recordCounts) {
      throw footerMismatch("footer record counts do not match", line: line)
    }
    if footer.droppedSamples.byReason.reduce(Int64(0), { $0 + $1.count })
      != footer.droppedSamples.total
    {
      throw footerMismatch("dropped-sample totals do not match", line: line)
    }
    if header.orderingPolicy.sampleReordering.kind == "none", footer.reorderedSamples != 0 {
      throw footerMismatch("none reordering requires reorderedSamples = 0", line: line)
    }
    var declaredTiming: [String: MotionObservedTiming] = [:]
    for entry in footer.observedTiming {
      if declaredTiming.updateValue(entry, forKey: entry.capabilityId) != nil {
        throw footerMismatch("duplicate observed timing", line: line)
      }
    }
    if Set(declaredTiming.keys) != Set(capabilityOrder) {
      throw footerMismatch("observed timing capability set does not match", line: line)
    }
    for capabilityId in capabilityOrder {
      guard let actual = timing[capabilityId], let declared = declaredTiming[capabilityId] else {
        throw footerMismatch("observed timing is missing", line: line)
      }
      if declared.acceptedObservationCount != actual.count
        || declared.minimumIntervalNs != actual.minimum
        || declared.maximumIntervalNs != actual.maximum
      {
        throw footerMismatch("observed timing does not match \(capabilityId)", line: line)
      }
    }
    if let limits = header.recorderLimits,
      footer.durationNs > limits.maximumDurationNs
        || counts.samples > limits.maximumSamples
        || Int64(containerBytes) > limits.maximumBytes
    {
      throw footerMismatch("trace exceeds declared recorder limits", line: line)
    }
    for (sequence, referenceLine) in predictionSampleReferences where !sampleSequences.contains(sequence) {
      throw malformed("prediction references unknown sample \(sequence)", line: referenceLine)
    }
  }

  private func validateObservation(
    _ kind: MotionSignalKind,
    capabilityId: String,
    values: [Double],
    line: Int
  ) throws {
    guard let capability = capabilities[capabilityId] else {
      throw malformed("unknown capability \(capabilityId)", line: line)
    }
    if capability.signalKind != kind {
      throw malformed("capability signal kind mismatch", line: line)
    }
    if availability[capabilityId] != .available {
      throw malformed("capability \(capabilityId) is unavailable", line: line)
    }
    if values.contains(where: { !$0.isFinite }) {
      throw malformed("signal contains a non-finite value", line: line)
    }
  }

  private func observe(_ capabilityId: String, timestampNs: Int64) {
    timing[capabilityId]?.observe(timestampNs)
  }

  private func isOrthonormal(_ matrix: [Double], tolerance: Double) -> Bool {
    guard matrix.count == 9, matrix.allSatisfy(\.isFinite), tolerance.isFinite, tolerance >= 0
    else { return false }
    let rows = [Array(matrix[0..<3]), Array(matrix[3..<6]), Array(matrix[6..<9])]
    func dot(_ left: [Double], _ right: [Double]) -> Double {
      zip(left, right).reduce(0) { $0 + $1.0 * $1.1 }
    }
    if rows.contains(where: { abs(dot($0, $0) - 1) > tolerance }) { return false }
    if abs(dot(rows[0], rows[1])) > tolerance || abs(dot(rows[0], rows[2])) > tolerance
      || abs(dot(rows[1], rows[2])) > tolerance
    {
      return false
    }
    let cross = [
      rows[1][1] * rows[2][2] - rows[1][2] * rows[2][1],
      rows[1][2] * rows[2][0] - rows[1][0] * rows[2][2],
      rows[1][0] * rows[2][1] - rows[1][1] * rows[2][0],
    ]
    return abs(dot(rows[0], cross) - 1) <= tolerance
  }

  private func malformed(_ message: String, line: Int) -> ReplayError {
    loadError(.malformedRecord, message, line: line)
  }

  private func footerMismatch(_ message: String, line: Int) -> ReplayError {
    loadError(.footerMismatch, message, line: line)
  }
}

private final class ReplayTiming {
  var count: Int64 = 0
  var minimum: Int64?
  var maximum: Int64?
  private var lastTimestampNs: Int64?

  func observe(_ timestampNs: Int64) {
    if let last = lastTimestampNs {
      let interval = timestampNs - last
      minimum = minimum.map { min($0, interval) } ?? interval
      maximum = maximum.map { max($0, interval) } ?? interval
    }
    lastTimestampNs = timestampNs
    count += 1
  }
}

private struct ReplayCounts {
  var samples: Int64 = 0
  var annotations: Int64 = 0
  var predictedEvents: Int64 = 0
  var displayRotationChanges: Int64 = 0
  var capabilityChanges: Int64 = 0

  func matches(_ counts: MotionTraceRecordCounts) -> Bool {
    samples == counts.samples && annotations == counts.annotations
      && predictedEvents == counts.predictedEvents
      && displayRotationChanges == counts.displayRotationChanges
      && capabilityChanges == counts.capabilityChanges
  }
}

private func isSafe(_ value: Int64?) -> Bool {
  guard let value else { return false }
  return (0...MotionTraceV1.maximumSafeInteger).contains(value)
}

private func loadError(
  _ code: ReplayErrorCode,
  _ diagnostic: String,
  line: Int? = nil
) -> ReplayError {
  ReplayError(code: code, stage: .load, diagnostic: diagnostic, line: line)
}
