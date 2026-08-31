import Foundation

public struct MotionTraceRecordingResult: Equatable, Sendable {
  public let footer: MotionTraceFooter
  public let bytesWritten: Int64
  public let destinationURL: URL?

  public init(footer: MotionTraceFooter, bytesWritten: Int64, destinationURL: URL?) {
    self.footer = footer
    self.bytesWritten = bytesWritten
    self.destinationURL = destinationURL
  }
}

public struct MotionTraceAppendOutcome: Equatable, Sendable {
  public let accepted: Bool
  public let droppedReason: DroppedSampleReason?
  public let recordingResult: MotionTraceRecordingResult?

  public init(
    accepted: Bool,
    droppedReason: DroppedSampleReason? = nil,
    recordingResult: MotionTraceRecordingResult? = nil
  ) {
    self.accepted = accepted
    self.droppedReason = droppedReason
    self.recordingResult = recordingResult
  }
}

public enum MotionSampleSourceEvent: Sendable {
  case sample(MotionSample)
  case dropped(DroppedSampleReason)
  case finished(durationNs: Int64)
  case failed(code: String, durationNs: Int64)
}

public protocol MotionSampleSource {
  mutating func nextEvent() -> MotionSampleSourceEvent
}

/// A bounded, transport-free JSONL recorder for one Motion Trace v1 session.
public final class MotionTraceRecorder {
  private let metadata: MotionTraceMetadata
  private let limits: MotionTraceRecorderLimits
  private let output: MotionTraceOutput
  private let encoder = MotionTraceRecordEncoder()
  private let lock = NSLock()

  private var internalState: MotionTraceRecorderState = .idle
  private var terminalResult: MotionTraceRecordingResult?
  private var terminalError: MotionTraceRecorderError?
  private var bytesWritten: Int64 = 0
  private var footerReserveBytes: Int64 = 0
  private var counts = MotionTraceRecordCounts()
  private var droppedCounts: [DroppedSampleReason: Int64] = [:]
  private var timing: [String: TimingAccumulator] = [:]
  private var capabilities: [String: MotionCapability] = [:]
  private var lastSampleTimestampNs: Int64?
  private var lastSampleSequence: Int64?
  private var lastDisplayRotationChangeSequence: Int64?
  private var maximumRecordTimestampNs: Int64 = 0
  private var annotationIds = Set<String>()

  public var state: MotionTraceRecorderState {
    lock.lock()
    defer { lock.unlock() }
    return internalState
  }

  public var result: MotionTraceRecordingResult? {
    lock.lock()
    defer { lock.unlock() }
    return terminalResult
  }

  public init(
    metadata: MotionTraceMetadata,
    limits: MotionTraceRecorderLimits,
    output: MotionTraceOutput
  ) {
    self.metadata = metadata
    self.limits = limits
    self.output = output
  }

  public convenience init(
    metadata: MotionTraceMetadata,
    limits: MotionTraceRecorderLimits,
    destinationURL: URL
  ) {
    self.init(
      metadata: metadata,
      limits: limits,
      output: AtomicFileMotionTraceOutput(destinationURL: destinationURL)
    )
  }

  public func start() throws {
    lock.lock()
    defer { lock.unlock() }
    guard internalState == .idle else {
      throw invalidStateError(operation: "start", stage: .start)
    }

    do {
      try MotionTraceValidation.validate(limits: limits)
      try MotionTraceValidation.validate(metadata: metadata)
      capabilities = Dictionary(
        uniqueKeysWithValues: metadata.capabilities.map { ($0.capabilityId, $0) })
      timing = Dictionary(
        uniqueKeysWithValues: metadata.capabilities.map {
          ($0.capabilityId, TimingAccumulator(capabilityId: $0.capabilityId))
        }
      )

      let header = MotionTraceHeader(metadata: metadata, limits: limits)
      let headerLine = try encoder.line(for: header)
      footerReserveBytes = try encoder.line(for: maximumSizeFooter()).count.int64
      guard headerLine.count.int64 + footerReserveBytes <= limits.maximumBytes else {
        throw MotionTraceRecorderError(
          code: .invalidConfiguration,
          stage: .start,
          diagnostic: "maximumBytes cannot contain the header and bounded footer diagnostics"
        )
      }

      try output.start()
      try writeRequired(headerLine, stage: .start)
      bytesWritten = headerLine.count.int64
      internalState = .recording
    } catch let error as MotionTraceRecorderError {
      failWithoutFooter(error)
      throw error
    } catch {
      let recorderError = ioError(stage: .start, underlying: error)
      failWithoutFooter(recorderError)
      throw recorderError
    }
  }

  @discardableResult
  public func append(_ sample: MotionSample) throws -> MotionTraceAppendOutcome {
    lock.lock()
    defer { lock.unlock() }
    try requireRecording(operation: "append sample")

    switch MotionTraceValidation.validate(
      sample: sample,
      capabilities: capabilities,
      attitudeReferencePresent: metadata.session.attitudeReference != nil
    ) {
    case .malformed:
      incrementDrop(.malformed)
      return MotionTraceAppendOutcome(accepted: false, droppedReason: .malformed)
    case .unsupported:
      incrementDrop(.unsupported)
      return MotionTraceAppendOutcome(accepted: false, droppedReason: .unsupported)
    case .valid:
      break
    }

    if let previousTimestamp = lastSampleTimestampNs,
      let previousSequence = lastSampleSequence,
      sample.timestampNs < previousTimestamp || sample.sequence <= previousSequence
    {
      incrementDrop(.nonMonotonicTimestamp)
      return MotionTraceAppendOutcome(accepted: false, droppedReason: .nonMonotonicTimestamp)
    }

    if sample.timestampNs > limits.maximumDurationNs {
      incrementDrop(.limitReached)
      let result = try finalize(
        status: .bounded,
        reason: .durationLimit,
        durationNs: limits.maximumDurationNs
      )
      return MotionTraceAppendOutcome(
        accepted: false,
        droppedReason: .limitReached,
        recordingResult: result
      )
    }

    let line: Data
    do {
      line = try encoder.line(for: sample)
    } catch {
      incrementDrop(.malformed)
      return MotionTraceAppendOutcome(accepted: false, droppedReason: .malformed)
    }
    guard canWriteBody(line) else {
      incrementDrop(.limitReached)
      let result = try finalize(
        status: .bounded, reason: .byteLimit, durationNs: maximumRecordTimestampNs)
      return MotionTraceAppendOutcome(
        accepted: false,
        droppedReason: .limitReached,
        recordingResult: result
      )
    }

    do {
      switch try output.write(line) {
      case .written:
        break
      case .backpressured:
        incrementDrop(.writerBackpressure)
        return MotionTraceAppendOutcome(accepted: false, droppedReason: .writerBackpressure)
      }
    } catch {
      let recorderError = ioError(stage: .append, underlying: error)
      failWithoutFooter(recorderError)
      throw recorderError
    }

    bytesWritten += line.count.int64
    counts.samples += 1
    lastSampleTimestampNs = sample.timestampNs
    lastSampleSequence = sample.sequence
    maximumRecordTimestampNs = max(maximumRecordTimestampNs, sample.timestampNs)
    updateTiming(for: sample)

    if sample.timestampNs >= limits.maximumDurationNs {
      let result = try finalize(
        status: .bounded,
        reason: .durationLimit,
        durationNs: limits.maximumDurationNs
      )
      return MotionTraceAppendOutcome(accepted: true, recordingResult: result)
    }
    if counts.samples >= limits.maximumSamples {
      let result = try finalize(
        status: .bounded,
        reason: .sampleLimit,
        durationNs: maximumRecordTimestampNs
      )
      return MotionTraceAppendOutcome(accepted: true, recordingResult: result)
    }
    return MotionTraceAppendOutcome(accepted: true)
  }

  @discardableResult
  public func append(_ annotation: MotionAnnotation) throws -> MotionTraceAppendOutcome {
    lock.lock()
    defer { lock.unlock() }
    try requireRecording(operation: "append annotation")
    try MotionTraceValidation.validate(annotation: annotation, privacy: metadata.privacy)
    guard !annotationIds.contains(annotation.annotationId) else {
      throw MotionTraceRecorderError(
        code: .invalidSample,
        stage: .append,
        diagnostic: "duplicate annotationId \(annotation.annotationId)"
      )
    }

    let recordEnd = annotation.endTimestampNs ?? annotation.timestampNs
    if recordEnd > limits.maximumDurationNs {
      let result = try finalize(
        status: .bounded,
        reason: .durationLimit,
        durationNs: limits.maximumDurationNs
      )
      return MotionTraceAppendOutcome(accepted: false, recordingResult: result)
    }

    let line = try encoder.line(for: annotation)
    guard canWriteBody(line) else {
      let result = try finalize(
        status: .bounded, reason: .byteLimit, durationNs: maximumRecordTimestampNs)
      return MotionTraceAppendOutcome(accepted: false, recordingResult: result)
    }
    do {
      guard try output.write(line) == .written else {
        throw MotionTraceRecorderError(
          code: .ioFailure,
          stage: .append,
          diagnostic: "annotation write encountered backpressure"
        )
      }
    } catch let error as MotionTraceRecorderError {
      failWithoutFooter(error)
      throw error
    } catch {
      let recorderError = ioError(stage: .append, underlying: error)
      failWithoutFooter(recorderError)
      throw recorderError
    }

    bytesWritten += line.count.int64
    counts.annotations += 1
    annotationIds.insert(annotation.annotationId)
    maximumRecordTimestampNs = max(maximumRecordTimestampNs, recordEnd)
    if recordEnd >= limits.maximumDurationNs {
      let result = try finalize(
        status: .bounded,
        reason: .durationLimit,
        durationNs: limits.maximumDurationNs
      )
      return MotionTraceAppendOutcome(accepted: true, recordingResult: result)
    }
    return MotionTraceAppendOutcome(accepted: true)
  }

  @discardableResult
  public func append(_ change: MotionDisplayRotationChange) throws -> MotionTraceAppendOutcome {
    lock.lock()
    defer { lock.unlock() }
    try requireRecording(operation: "append display rotation change")
    guard MotionTraceValidation.isSafeInteger(change.timestampNs),
      MotionTraceValidation.isSafeInteger(change.changeSequence),
      change.timestampNs >= maximumRecordTimestampNs,
      lastDisplayRotationChangeSequence.map({ change.changeSequence > $0 }) ?? true
    else {
      throw MotionTraceRecorderError(
        code: .invalidSample,
        stage: .append,
        diagnostic: "display rotation change time or sequence is invalid"
      )
    }

    if change.timestampNs > limits.maximumDurationNs {
      let result = try finalize(
        status: .bounded,
        reason: .durationLimit,
        durationNs: limits.maximumDurationNs
      )
      return MotionTraceAppendOutcome(accepted: false, recordingResult: result)
    }

    let line = try encoder.line(for: change)
    guard canWriteBody(line) else {
      let result = try finalize(
        status: .bounded, reason: .byteLimit, durationNs: maximumRecordTimestampNs)
      return MotionTraceAppendOutcome(accepted: false, recordingResult: result)
    }
    do {
      guard try output.write(line) == .written else {
        throw MotionTraceRecorderError(
          code: .ioFailure,
          stage: .append,
          diagnostic: "display rotation change write encountered backpressure"
        )
      }
    } catch let error as MotionTraceRecorderError {
      failWithoutFooter(error)
      throw error
    } catch {
      let recorderError = ioError(stage: .append, underlying: error)
      failWithoutFooter(recorderError)
      throw recorderError
    }

    bytesWritten += line.count.int64
    counts.displayRotationChanges += 1
    lastDisplayRotationChangeSequence = change.changeSequence
    maximumRecordTimestampNs = max(maximumRecordTimestampNs, change.timestampNs)
    if change.timestampNs >= limits.maximumDurationNs {
      let result = try finalize(
        status: .bounded,
        reason: .durationLimit,
        durationNs: limits.maximumDurationNs
      )
      return MotionTraceAppendOutcome(accepted: true, recordingResult: result)
    }
    return MotionTraceAppendOutcome(accepted: true)
  }

  public func reportDroppedSample(_ reason: DroppedSampleReason) throws {
    lock.lock()
    defer { lock.unlock() }
    try requireRecording(operation: "report dropped sample")
    incrementDrop(reason)
  }

  public func finish(durationNs: Int64) throws -> MotionTraceRecordingResult {
    lock.lock()
    defer { lock.unlock() }
    if let terminalResult { return terminalResult }
    if let terminalError { throw terminalError }
    try requireRecording(operation: "finish")
    try validateTerminalDuration(durationNs)
    if durationNs >= limits.maximumDurationNs {
      return try finalize(
        status: .bounded,
        reason: .durationLimit,
        durationNs: limits.maximumDurationNs
      )
    }
    return try finalize(status: .complete, reason: .requestedStop, durationNs: durationNs)
  }

  public func cancel(durationNs: Int64) throws -> MotionTraceRecordingResult {
    lock.lock()
    defer { lock.unlock() }
    if let terminalResult { return terminalResult }
    if let terminalError { throw terminalError }
    try requireRecording(operation: "cancel")
    try validateTerminalDuration(durationNs)
    return try finalize(
      status: .cancelled,
      reason: .callerCancelled,
      durationNs: min(durationNs, limits.maximumDurationNs)
    )
  }

  public func failSource(code: String, durationNs: Int64) throws -> MotionTraceRecordingResult {
    lock.lock()
    defer { lock.unlock() }
    if let terminalResult { return terminalResult }
    if let terminalError { throw terminalError }
    try requireRecording(operation: "fail source")
    try validateTerminalDuration(durationNs)
    guard code.range(of: "^[A-Za-z][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) != nil
    else {
      throw MotionTraceRecorderError(
        code: .invalidSample,
        stage: .finalize,
        diagnostic: "source failure code is not a v1 identifier"
      )
    }
    return try finalize(
      status: .failed,
      reason: .sourceFailure,
      failureCode: code,
      durationNs: min(durationNs, limits.maximumDurationNs)
    )
  }

  public func record<Source: MotionSampleSource>(
    from source: inout Source
  ) throws -> MotionTraceRecordingResult {
    if state == .idle { try start() }
    while state == .recording {
      switch source.nextEvent() {
      case .sample(let sample):
        if let result = try append(sample).recordingResult { return result }
      case .dropped(let reason):
        try reportDroppedSample(reason)
      case .finished(let durationNs):
        return try finish(durationNs: durationNs)
      case .failed(let code, let durationNs):
        return try failSource(code: code, durationNs: durationNs)
      }
    }
    if let result { return result }
    if let terminalError { throw terminalError }
    throw invalidStateError(operation: "record source", stage: .append)
  }

  private func finalize(
    status: MotionTraceFinalizationStatus,
    reason: MotionTraceTerminationReason,
    failureCode: String? = nil,
    durationNs: Int64
  ) throws -> MotionTraceRecordingResult {
    internalState = .finalizing
    let footer = makeFooter(
      status: status,
      reason: reason,
      failureCode: failureCode,
      durationNs: durationNs
    )
    do {
      let line = try encoder.line(for: footer)
      guard bytesWritten + line.count.int64 <= limits.maximumBytes else {
        throw MotionTraceRecorderError(
          code: .ioFailure,
          stage: .finalize,
          diagnostic: "footer exceeded its reserved byte budget"
        )
      }
      try writeRequired(line, stage: .finalize)
      bytesWritten += line.count.int64
      let destinationURL = try output.commit()
      let result = MotionTraceRecordingResult(
        footer: footer,
        bytesWritten: bytesWritten,
        destinationURL: destinationURL
      )
      terminalResult = result
      switch status {
      case .complete, .bounded:
        internalState = .finished
      case .cancelled:
        internalState = .cancelled
      case .failed:
        internalState = .failed
      }
      return result
    } catch let error as MotionTraceRecorderError {
      failWithoutFooter(error)
      throw error
    } catch {
      let recorderError = ioError(stage: .commit, underlying: error)
      failWithoutFooter(recorderError)
      throw recorderError
    }
  }

  private func makeFooter(
    status: MotionTraceFinalizationStatus,
    reason: MotionTraceTerminationReason,
    failureCode: String?,
    durationNs: Int64
  ) -> MotionTraceFooter {
    MotionTraceFooter(
      finalizationStatus: status,
      terminationReason: reason,
      failureCode: failureCode,
      durationNs: durationNs,
      recordCounts: counts,
      droppedSamples: droppedSummary(),
      observedTiming: metadata.capabilities.compactMap { timing[$0.capabilityId]?.summary }
    )
  }

  private func maximumSizeFooter() -> MotionTraceFooter {
    let maximum = MotionTraceV1.maximumSafeInteger
    let maximumCounts = MotionTraceRecordCounts(
      samples: maximum,
      annotations: maximum,
      predictedEvents: maximum,
      displayRotationChanges: maximum,
      capabilityChanges: maximum
    )
    let maximumDrops = DroppedSampleReason.allCases.map {
      DroppedSampleCount(reason: $0, count: maximum)
    }
    let maximumTiming = metadata.capabilities.map {
      MotionObservedTiming(
        capabilityId: $0.capabilityId,
        acceptedObservationCount: maximum,
        minimumIntervalNs: maximum,
        maximumIntervalNs: maximum
      )
    }
    return MotionTraceFooter(
      finalizationStatus: .failed,
      terminationReason: .sourceFailure,
      failureCode: String(repeating: "x", count: 128),
      durationNs: maximum,
      recordCounts: maximumCounts,
      reorderedSamples: maximum,
      droppedSamples: DroppedSampleSummary(total: maximum, byReason: maximumDrops),
      observedTiming: maximumTiming
    )
  }

  private func updateTiming(for sample: MotionSample) {
    for (_, capabilityId, _) in sample.signals.observations {
      timing[capabilityId]?.observe(timestampNs: sample.timestampNs)
    }
  }

  private func droppedSummary() -> DroppedSampleSummary {
    let entries = DroppedSampleReason.allCases.compactMap { reason -> DroppedSampleCount? in
      guard let count = droppedCounts[reason], count > 0 else { return nil }
      return DroppedSampleCount(reason: reason, count: count)
    }
    return DroppedSampleSummary(
      total: entries.reduce(0) { $0 + $1.count },
      byReason: entries
    )
  }

  private func incrementDrop(_ reason: DroppedSampleReason) {
    droppedCounts[reason, default: 0] += 1
  }

  private func canWriteBody(_ data: Data) -> Bool {
    bytesWritten + data.count.int64 + footerReserveBytes <= limits.maximumBytes
  }

  private func writeRequired(_ data: Data, stage: MotionTraceRecorderStage) throws {
    guard try output.write(data) == .written else {
      throw MotionTraceRecorderError(
        code: .ioFailure,
        stage: stage,
        diagnostic: "required trace write encountered backpressure",
        partialURL: output.temporaryURL
      )
    }
  }

  private func validateTerminalDuration(_ durationNs: Int64) throws {
    guard MotionTraceValidation.isSafeInteger(durationNs), durationNs >= maximumRecordTimestampNs
    else {
      throw MotionTraceRecorderError(
        code: .invalidSample,
        stage: .finalize,
        diagnostic: "terminal duration is invalid or precedes an accepted record"
      )
    }
  }

  private func requireRecording(operation: String) throws {
    guard internalState == .recording else {
      throw invalidStateError(operation: operation, stage: .append)
    }
  }

  private func invalidStateError(
    operation: String,
    stage: MotionTraceRecorderStage
  ) -> MotionTraceRecorderError {
    MotionTraceRecorderError(
      code: .invalidState,
      stage: stage,
      diagnostic: "cannot \(operation) while recorder is \(internalState.rawValue)",
      partialURL: output.temporaryURL
    )
  }

  private func ioError(
    stage: MotionTraceRecorderStage,
    underlying: Error
  ) -> MotionTraceRecorderError {
    MotionTraceRecorderError(
      code: .ioFailure,
      stage: stage,
      diagnostic: String(describing: underlying),
      partialURL: output.temporaryURL
    )
  }

  private func failWithoutFooter(_ error: MotionTraceRecorderError) {
    output.abortPreservingPartial()
    internalState = .failed
    terminalError = error
  }
}

private final class TimingAccumulator {
  let capabilityId: String
  private(set) var count: Int64 = 0
  private var lastTimestampNs: Int64?
  private var minimumIntervalNs: Int64?
  private var maximumIntervalNs: Int64?

  init(capabilityId: String) {
    self.capabilityId = capabilityId
  }

  func observe(timestampNs: Int64) {
    if let lastTimestampNs {
      let interval = timestampNs - lastTimestampNs
      minimumIntervalNs = minimumIntervalNs.map { min($0, interval) } ?? interval
      maximumIntervalNs = maximumIntervalNs.map { max($0, interval) } ?? interval
    }
    lastTimestampNs = timestampNs
    count += 1
  }

  var summary: MotionObservedTiming {
    MotionObservedTiming(
      capabilityId: capabilityId,
      acceptedObservationCount: count,
      minimumIntervalNs: minimumIntervalNs,
      maximumIntervalNs: maximumIntervalNs
    )
  }
}

extension Int {
  fileprivate var int64: Int64 { Int64(self) }
}
