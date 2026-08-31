import Foundation
import MotionGestureCore
import MotionGestureRecorder

public final class ReplayMotionSource {
  public private(set) var lifecycleState: ReplayLifecycleState = .idle
  public private(set) var currentVirtualTimestampNs: Int64?
  public private(set) var trace: ValidatedReplayTrace?

  private let detector: MotionReplayDetector
  private let limits: ReplayLimits
  private var sampleIndex = 0
  private var detectorStarted = false
  private var deliveredSampleSequences: Set<Int64> = []
  private var predictions: [MotionPredictedEventRecord] = []

  public init(
    detector: MotionReplayDetector,
    limits: ReplayLimits = ReplayLimits()
  ) {
    self.detector = detector
    self.limits = limits
  }

  @discardableResult
  public func load(data: Data) throws -> ValidatedReplayTrace {
    try requireState([.idle], stage: .load)
    do {
      let loaded = try MotionTraceReplayLoader.load(data: data, limits: limits)
      trace = loaded
      lifecycleState = .ready
      return loaded
    } catch let error as ReplayError {
      lifecycleState = .failed
      throw error
    }
  }

  @discardableResult
  public func load(url: URL) throws -> ValidatedReplayTrace {
    try requireState([.idle], stage: .load)
    do {
      if let fileSize = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
        fileSize > limits.maximumBytes
      {
        lifecycleState = .failed
        throw ReplayError(
          code: .limitExceeded,
          stage: .load,
          diagnostic: "trace exceeds \(limits.maximumBytes) bytes"
        )
      }
    } catch let error as ReplayError {
      throw error
    } catch {
      lifecycleState = .failed
      throw ReplayError(
        code: .ioFailure,
        stage: .load,
        diagnostic: "could not inspect replay trace"
      )
    }
    let data: Data
    do {
      data = try Data(contentsOf: url, options: [.mappedIfSafe])
    } catch {
      lifecycleState = .failed
      throw ReplayError(
        code: .ioFailure,
        stage: .load,
        diagnostic: "could not read replay trace"
      )
    }
    return try load(data: data)
  }

  @discardableResult
  public func step() throws -> ReplayStep? {
    try requireState([.ready, .replaying], stage: .step)
    guard let trace else {
      throw ReplayError(
        code: .invalidState,
        stage: .step,
        diagnostic: "loaded trace is unavailable"
      )
    }
    try ensureDetectorStarted(trace)

    if sampleIndex >= trace.samples.count {
      try finishDetector(stage: .step)
      lifecycleState = .finished
      return nil
    }

    let sample = trace.samples[sampleIndex]
    currentVirtualTimestampNs = sample.timestampNs
    deliveredSampleSequences.insert(sample.sequence)
    let emitted: [PredictedGestureEvent]
    do {
      emitted = try detector.consume(sample)
    } catch {
      throw failDetector(stage: .step, diagnostic: "detector consume failed")
    }

    var records: [MotionPredictedEventRecord] = []
    for event in emitted {
      let record = try makeRecord(
        trace: trace,
        event: event,
        currentTimestampNs: sample.timestampNs
      )
      predictions.append(record)
      records.append(record)
    }
    sampleIndex += 1

    let finished = sampleIndex == trace.samples.count
    if finished {
      try finishDetector(stage: .step)
      lifecycleState = .finished
    }
    return ReplayStep(
      virtualTimestampNs: sample.timestampNs,
      sample: sample,
      emittedEvents: records,
      isFinished: finished
    )
  }

  public func run() throws -> ReplayRunResult {
    try requireState([.ready, .replaying], stage: .run)
    while lifecycleState != .finished {
      _ = try step()
    }
    return try result()
  }

  public func cancel() throws {
    try requireState([.ready, .replaying], stage: .cancel)
    if detectorStarted {
      try finishDetector(stage: .cancel)
    }
    lifecycleState = .cancelled
  }

  public func reset() throws {
    try requireState([.finished, .cancelled], stage: .reset)
    do {
      try detector.reset()
    } catch {
      throw failDetector(stage: .reset, diagnostic: "detector reset failed")
    }
    detectorStarted = false
    sampleIndex = 0
    currentVirtualTimestampNs = nil
    deliveredSampleSequences.removeAll(keepingCapacity: true)
    predictions.removeAll(keepingCapacity: true)
    lifecycleState = .ready
  }

  public func result() throws -> ReplayRunResult {
    try requireState([.finished, .cancelled], stage: .run)
    return ReplayRunResult(
      detector: detector.descriptor,
      events: predictions,
      finalVirtualTimestampNs: currentVirtualTimestampNs
    )
  }

  private func ensureDetectorStarted(_ trace: ValidatedReplayTrace) throws {
    guard !detectorStarted else { return }
    let descriptor = detector.descriptor
    let identifierPattern = "^[A-Za-z][A-Za-z0-9._-]{0,127}$"
    let semanticVersionPattern =
      "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
      + "(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$"
    if !matches(descriptor.detectorStreamId, pattern: identifierPattern)
      || !matches(descriptor.detectorId, pattern: identifierPattern)
      || !matches(descriptor.configurationIdentity, pattern: identifierPattern)
      || !matches(descriptor.detectorVersion, pattern: semanticVersionPattern)
    {
      throw failDetector(stage: .step, diagnostic: "detector descriptor is not wire-compatible")
    }
    if let declared = trace.header.detectors?.first(where: {
      $0.detectorStreamId == descriptor.detectorStreamId
    }), declared != descriptor {
      throw failDetector(
        stage: .step,
        diagnostic: "detector descriptor conflicts with the trace declaration"
      )
    }
    do {
      try detector.reset()
      try detector.start(session: trace.header.session)
      detectorStarted = true
      lifecycleState = .replaying
    } catch {
      throw failDetector(stage: .step, diagnostic: "detector start failed")
    }
  }

  private func finishDetector(stage: ReplayStage) throws {
    guard detectorStarted else { return }
    do {
      try detector.stop()
      detectorStarted = false
    } catch {
      throw failDetector(stage: stage, diagnostic: "detector stop failed")
    }
  }

  private func makeRecord(
    trace: ValidatedReplayTrace,
    event: PredictedGestureEvent,
    currentTimestampNs: Int64
  ) throws -> MotionPredictedEventRecord {
    guard (0...MotionTraceV1.maximumSafeInteger).contains(event.timestampNs),
      event.timestampNs <= currentTimestampNs,
      deliveredSampleSequences.contains(event.sourceSampleSequence)
    else {
      throw failDetector(
        stage: .step,
        diagnostic: "detector emitted an invalid timestamp or source sample reference"
      )
    }
    if let previous = predictions.last, event.timestampNs < previous.timestampNs {
      throw failDetector(stage: .step, diagnostic: "detector predictions are not monotonic")
    }
    guard predictions.count <= MotionTraceV1.maximumSafeInteger else {
      throw failDetector(stage: .step, diagnostic: "detector emitted too many predictions")
    }
    let eventSequence = Int64(predictions.count)
    return MotionPredictedEventRecord(
      eventId: DeterministicReplayEventID.make(
        traceId: trace.header.traceId,
        detectorStreamId: detector.descriptor.detectorStreamId,
        eventSequence: eventSequence
      ),
      detectorStreamId: detector.descriptor.detectorStreamId,
      eventSequence: eventSequence,
      timestampNs: event.timestampNs,
      gesture: event.gesture,
      sourceSampleSequence: event.sourceSampleSequence
    )
  }

  private func requireState(_ allowed: Set<ReplayLifecycleState>, stage: ReplayStage) throws {
    guard allowed.contains(lifecycleState) else {
      throw ReplayError(
        code: .invalidState,
        stage: stage,
        diagnostic: "operation is invalid while state is \(lifecycleState.rawValue)"
      )
    }
  }

  private func matches(_ value: String, pattern: String) -> Bool {
    value.range(of: pattern, options: .regularExpression) != nil
  }

  private func failDetector(stage: ReplayStage, diagnostic: String) -> ReplayError {
    lifecycleState = .failed
    return ReplayError(code: .detectorFailure, stage: stage, diagnostic: diagnostic)
  }
}

private enum DeterministicReplayEventID {
  private static let offsetBasis: UInt64 = 0xCBF29CE484222325
  private static let prime: UInt64 = 0x100000001B3
  private static let hexDigits = Array("0123456789abcdef".utf8)

  static func make(traceId: String, detectorStreamId: String, eventSequence: Int64) -> String {
    let seed = "\(traceId)|\(detectorStreamId)|\(eventSequence)"
    let first = fnv1a("mge.replay.event.v1.a|\(seed)")
    let second = fnv1a("mge.replay.event.v1.b|\(seed)")
    var bytes = [UInt8](repeating: 0, count: 16)
    writeBigEndian(first, destination: &bytes, offset: 0)
    writeBigEndian(second, destination: &bytes, offset: 8)
    bytes[6] = (bytes[6] & 0x0F) | 0x80
    bytes[8] = (bytes[8] & 0x3F) | 0x80

    var hex: [UInt8] = []
    hex.reserveCapacity(32)
    for byte in bytes {
      hex.append(hexDigits[Int(byte >> 4)])
      hex.append(hexDigits[Int(byte & 0x0F)])
    }
    let value = String(decoding: hex, as: UTF8.self)
    return "\(value.prefix(8))-\(value.dropFirst(8).prefix(4))-"
      + "\(value.dropFirst(12).prefix(4))-\(value.dropFirst(16).prefix(4))-\(value.dropFirst(20))"
  }

  private static func fnv1a(_ value: String) -> UInt64 {
    var hash = offsetBasis
    for byte in value.utf8 {
      hash = (hash ^ UInt64(byte)) &* prime
    }
    return hash
  }

  private static func writeBigEndian(
    _ value: UInt64,
    destination: inout [UInt8],
    offset: Int
  ) {
    for index in 0..<8 {
      destination[offset + index] = UInt8(truncatingIfNeeded: value >> UInt64((7 - index) * 8))
    }
  }
}
