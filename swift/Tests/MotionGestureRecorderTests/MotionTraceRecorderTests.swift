import Foundation
import XCTest

@testable import MotionGestureRecorder

final class MotionTraceRecorderTests: XCTestCase {
  func testNormalCompletionStreamsAndCommitsAtomically() throws {
    let directory = try makeTemporaryDirectory()
    defer { try? FileManager.default.removeItem(at: directory) }
    let destination = directory.appendingPathComponent("normal.mge.jsonl")
    let recorder = MotionTraceRecorder(
      metadata: metadata(includeAnnotations: true),
      limits: limits(),
      destinationURL: destination
    )

    try recorder.start()
    XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))

    XCTAssertTrue(try recorder.append(sample(timestampNs: 0, sequence: 0)).accepted)
    XCTAssertTrue(try recorder.append(sample(timestampNs: 20, sequence: 1)).accepted)
    XCTAssertTrue(try recorder.append(annotation(timestampNs: 20)).accepted)
    let result = try recorder.finish(durationNs: 30)
    let repeatedResult = try recorder.finish(durationNs: 999)

    XCTAssertEqual(recorder.state, .finished)
    XCTAssertEqual(repeatedResult, result)
    XCTAssertEqual(result.footer.finalizationStatus, .complete)
    XCTAssertEqual(result.footer.terminationReason, .requestedStop)
    XCTAssertEqual(result.footer.recordCounts.samples, 2)
    XCTAssertEqual(result.footer.recordCounts.annotations, 1)
    XCTAssertEqual(result.footer.observedTiming.first?.acceptedObservationCount, 2)
    XCTAssertEqual(result.footer.observedTiming.first?.minimumIntervalNs, 20)
    XCTAssertEqual(result.footer.observedTiming.first?.maximumIntervalNs, 20)
    XCTAssertTrue(FileManager.default.fileExists(atPath: destination.path))
    XCTAssertEqual(result.bytesWritten, try Data(contentsOf: destination).count.int64)
    if let exportPath = ProcessInfo.processInfo.environment["MGE_SWIFT_TRACE_OUTPUT"] {
      try Data(contentsOf: destination).write(to: URL(fileURLWithPath: exportPath))
    }

    let records = try jsonRecords(in: destination)
    XCTAssertEqual(records.first?["recordType"] as? String, "traceHeader")
    XCTAssertEqual(
      records.first?["recorderLimits"] as? [String: Int64],
      [
        "maximumDurationNs": 1_000,
        "maximumSamples": 10,
        "maximumBytes": 65_536,
      ])
    XCTAssertEqual(records.last?["recordType"] as? String, "traceFooter")
  }

  func testSampleAndDurationBoundsFinalizeAtAcceptedBoundary() throws {
    let sampleOutput = MemoryTraceOutput()
    let sampleRecorder = MotionTraceRecorder(
      metadata: metadata(),
      limits: MotionTraceRecorderLimits(
        maximumDurationNs: 1_000,
        maximumSamples: 1,
        maximumBytes: 65_536
      ),
      output: sampleOutput
    )
    try sampleRecorder.start()
    let sampleOutcome = try sampleRecorder.append(sample(timestampNs: 25, sequence: 0))
    XCTAssertTrue(sampleOutcome.accepted)
    XCTAssertEqual(sampleOutcome.recordingResult?.footer.finalizationStatus, .bounded)
    XCTAssertEqual(sampleOutcome.recordingResult?.footer.terminationReason, .sampleLimit)

    let durationOutput = MemoryTraceOutput()
    let durationRecorder = MotionTraceRecorder(
      metadata: metadata(),
      limits: MotionTraceRecorderLimits(
        maximumDurationNs: 100,
        maximumSamples: 10,
        maximumBytes: 65_536
      ),
      output: durationOutput
    )
    try durationRecorder.start()
    let durationOutcome = try durationRecorder.append(sample(timestampNs: 100, sequence: 0))
    XCTAssertTrue(durationOutcome.accepted)
    XCTAssertEqual(durationOutcome.recordingResult?.footer.terminationReason, .durationLimit)
  }

  func testByteBoundIncludesHeaderFooterAndConfiguredMetadata() throws {
    let output = MemoryTraceOutput()
    let maximumBytes: Int64 = 8_192
    let recorder = MotionTraceRecorder(
      metadata: metadata(includeAnnotations: true),
      limits: MotionTraceRecorderLimits(
        maximumDurationNs: 1_000_000,
        maximumSamples: 1_000,
        maximumBytes: maximumBytes
      ),
      output: output
    )
    try recorder.start()

    var result: MotionTraceRecordingResult?
    for index in 0..<100 where result == nil {
      let outcome = try recorder.append(annotation(timestampNs: Int64(index), index: index))
      result = outcome.recordingResult
    }

    let bounded = try XCTUnwrap(result)
    XCTAssertEqual(bounded.footer.terminationReason, .byteLimit)
    XCTAssertLessThanOrEqual(bounded.bytesWritten, maximumBytes)
    let header = try XCTUnwrap(jsonRecords(from: output.data).first)
    let serializedLimits = try XCTUnwrap(header["recorderLimits"] as? [String: Any])
    XCTAssertEqual(serializedLimits["maximumBytes"] as? Int64, maximumBytes)
  }

  func testCancellationIsFinalizedIncompleteAndIdempotent() throws {
    let output = MemoryTraceOutput()
    let recorder = MotionTraceRecorder(metadata: metadata(), limits: limits(), output: output)
    try recorder.start()
    _ = try recorder.append(sample(timestampNs: 0, sequence: 0))

    let first = try recorder.cancel(durationNs: 5)
    let second = try recorder.cancel(durationNs: 999)

    XCTAssertEqual(first, second)
    XCTAssertEqual(recorder.state, .cancelled)
    XCTAssertEqual(first.footer.finalizationStatus, .cancelled)
    XCTAssertEqual(first.footer.terminationReason, .callerCancelled)
    XCTAssertTrue(output.committed)
  }

  func testMalformedUnsupportedAndNonMonotonicSamplesAreReported() throws {
    let output = MemoryTraceOutput()
    let recorder = MotionTraceRecorder(metadata: metadata(), limits: limits(), output: output)
    try recorder.start()

    let malformed = MotionSample(timestampNs: 0, sequence: 0, signals: MotionSignals())
    XCTAssertEqual(try recorder.append(malformed).droppedReason, .malformed)

    let unknown = MotionSample(
      timestampNs: 0,
      sequence: 0,
      signals: MotionSignals(
        gravity: MotionVectorObservation(
          capabilityId: "gravity.unknown",
          value: MotionVector3(x: 0, y: 0, z: 1)
        )
      )
    )
    XCTAssertEqual(try recorder.append(unknown).droppedReason, .unsupported)
    XCTAssertTrue(try recorder.append(sample(timestampNs: 10, sequence: 0)).accepted)
    XCTAssertEqual(
      try recorder.append(sample(timestampNs: 11, sequence: 0)).droppedReason,
      .nonMonotonicTimestamp
    )

    let result = try recorder.finish(durationNs: 20)
    XCTAssertEqual(result.footer.recordCounts.samples, 1)
    XCTAssertEqual(result.footer.droppedSamples.total, 3)
    XCTAssertEqual(
      Set(result.footer.droppedSamples.byReason.map(\.reason)),
      Set([.malformed, .unsupported, .nonMonotonicTimestamp])
    )
  }

  func testBackpressureDropsSampleAndWriterFailureLeavesNoCommit() throws {
    let backpressureOutput = MemoryTraceOutput(backpressureWrites: [2])
    let recorder = MotionTraceRecorder(
      metadata: metadata(),
      limits: limits(),
      output: backpressureOutput
    )
    try recorder.start()
    XCTAssertEqual(
      try recorder.append(sample(timestampNs: 0, sequence: 0)).droppedReason,
      .writerBackpressure
    )
    XCTAssertTrue(try recorder.append(sample(timestampNs: 1, sequence: 0)).accepted)
    let result = try recorder.finish(durationNs: 2)
    XCTAssertEqual(result.footer.droppedSamples.byReason.first?.reason, .writerBackpressure)

    let failingOutput = MemoryTraceOutput(failingWrites: [2])
    let failingRecorder = MotionTraceRecorder(
      metadata: metadata(),
      limits: limits(),
      output: failingOutput
    )
    try failingRecorder.start()
    XCTAssertThrowsError(try failingRecorder.append(sample(timestampNs: 0, sequence: 0))) { error in
      XCTAssertEqual((error as? MotionTraceRecorderError)?.code, .ioFailure)
    }
    XCTAssertEqual(failingRecorder.state, .failed)
    XCTAssertFalse(failingOutput.committed)
    XCTAssertFalse(String(decoding: failingOutput.data, as: UTF8.self).contains("traceFooter"))
  }

  func testInjectableSourceReportsItsDrops() throws {
    let output = MemoryTraceOutput()
    let recorder = MotionTraceRecorder(metadata: metadata(), limits: limits(), output: output)
    var source = ArrayMotionSampleSource(events: [
      .sample(sample(timestampNs: 0, sequence: 0)),
      .dropped(.bufferOverflow),
      .finished(durationNs: 10),
    ])

    let result = try recorder.record(from: &source)

    XCTAssertEqual(result.footer.recordCounts.samples, 1)
    XCTAssertEqual(result.footer.droppedSamples.total, 1)
    XCTAssertEqual(result.footer.droppedSamples.byReason.first?.reason, .bufferOverflow)
  }

  private func metadata(includeAnnotations: Bool = false) -> MotionTraceMetadata {
    MotionTraceMetadata(
      traceId: "00000000-0000-4000-8000-000000000101",
      producer: MotionTraceProducer(
        libraryName: "motionGestureRecorderSwift",
        libraryVersion: "0.1.0",
        platformAdapterName: "testAdapter",
        platformAdapterVersion: "0.1.0"
      ),
      privacy: MotionTracePrivacy(
        tier: .synthetic,
        dataClasses: includeAnnotations
          ? [.motionSensorData, .gestureAnnotation] : [.motionSensorData]
      ),
      session: MotionTraceSession(
        displayRotationClockwiseAtStart: .degrees0,
        gestureFrameFromDeviceRowMajor: [1, 0, 0, 0, 1, 0, 0, 0, 1]
      ),
      capabilities: [
        MotionCapability(
          capabilityId: "gravity.main",
          signalKind: .gravity,
          requirement: .required,
          biasCorrection: .notApplicable,
          availability: .available,
          sourceKind: .fused,
          nativeSourceIdentifier: "test.gravity",
          nativeUnit: .standardGravity,
          nativeSignConvention: .physicalGravity,
          conversions: [.none]
        )
      ]
    )
  }

  private func limits() -> MotionTraceRecorderLimits {
    MotionTraceRecorderLimits(
      maximumDurationNs: 1_000,
      maximumSamples: 10,
      maximumBytes: 65_536
    )
  }

  private func sample(timestampNs: Int64, sequence: Int64) -> MotionSample {
    MotionSample(
      timestampNs: timestampNs,
      sequence: sequence,
      signals: MotionSignals(
        gravity: MotionVectorObservation(
          capabilityId: "gravity.main",
          value: MotionVector3(x: 0, y: 0, z: 0.8)
        )
      )
    )
  }

  private func annotation(timestampNs: Int64, index: Int = 1) -> MotionAnnotation {
    MotionAnnotation(
      annotationId: String(format: "10000000-0000-4000-8000-%012x", index),
      annotationKind: .gestureIntent,
      timestampNs: timestampNs,
      gesture: .tiltForward,
      provenance: MotionAnnotationProvenance(
        kind: .synthetic,
        generatorId: "test.generator",
        generatorVersion: "1.0.0"
      )
    )
  }

  private func makeTemporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }

  private func jsonRecords(in url: URL) throws -> [[String: Any]] {
    try jsonRecords(from: Data(contentsOf: url))
  }

  private func jsonRecords(from data: Data) throws -> [[String: Any]] {
    try data.split(separator: 0x0A).map { line in
      try XCTUnwrap(JSONSerialization.jsonObject(with: Data(line)) as? [String: Any])
    }
  }
}

private final class MemoryTraceOutput: MotionTraceOutput {
  let temporaryURL: URL? = nil
  let destinationURL: URL? = nil
  private(set) var data = Data()
  private(set) var committed = false
  private var writeCount = 0
  private let backpressureWrites: Set<Int>
  private let failingWrites: Set<Int>

  init(backpressureWrites: Set<Int> = [], failingWrites: Set<Int> = []) {
    self.backpressureWrites = backpressureWrites
    self.failingWrites = failingWrites
  }

  func start() throws {}

  func write(_ data: Data) throws -> MotionTraceWriteDisposition {
    writeCount += 1
    if failingWrites.contains(writeCount) { throw MemoryOutputError.injectedFailure }
    if backpressureWrites.contains(writeCount) { return .backpressured }
    self.data.append(data)
    return .written
  }

  func commit() throws -> URL? {
    committed = true
    return nil
  }

  func abortPreservingPartial() {}
}

private enum MemoryOutputError: Error {
  case injectedFailure
}

private struct ArrayMotionSampleSource: MotionSampleSource {
  var events: [MotionSampleSourceEvent]
  private var index = 0

  init(events: [MotionSampleSourceEvent]) {
    self.events = events
  }

  mutating func nextEvent() -> MotionSampleSourceEvent {
    defer { index += 1 }
    return events[index]
  }
}

extension Int {
  fileprivate var int64: Int64 { Int64(self) }
}
