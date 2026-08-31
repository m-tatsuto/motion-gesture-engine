import Foundation
import XCTest

@testable import MotionGestureReplay
import MotionGestureRecorder

final class ReplayMotionSourceTests: XCTestCase {
  func testSharedFixtureProducesDeterministicPredictionsAcrossReset() throws {
    let source = ReplayMotionSource(detector: LegacyGravityThresholdV1ReplayDetector())
    try source.load(url: fixture("replay/legacy-gravity-threshold-v1.mge.jsonl"))

    let first = try source.run()
    XCTAssertEqual(first.events, try expectedPredictions())
    XCTAssertEqual(first.finalVirtualTimestampNs, 9_000_000_000_000)
    XCTAssertEqual(
      try first.encodedPredictionsJSONLines(),
      try first.encodedPredictionsJSONLines()
    )

    try source.reset()
    let second = try source.run()
    XCTAssertEqual(first, second)
    XCTAssertEqual(
      try first.encodedPredictionsJSONLines(),
      try second.encodedPredictionsJSONLines()
    )
  }

  func testStepPreservesEqualTimestampsSequenceGapsAndLongVirtualGap() throws {
    let source = ReplayMotionSource(detector: LegacyGravityThresholdV1ReplayDetector())
    try source.load(url: fixture("replay/legacy-gravity-threshold-v1.mge.jsonl"))

    let first = try XCTUnwrap(source.step())
    XCTAssertEqual(first.virtualTimestampNs, 0)
    XCTAssertEqual(first.sample.sequence, 0)
    XCTAssertEqual(first.emittedEvents.count, 1)
    XCTAssertEqual(source.lifecycleState, .replaying)

    let equalTimestamp = try XCTUnwrap(source.step())
    XCTAssertEqual(equalTimestamp.virtualTimestampNs, 0)
    XCTAssertEqual(equalTimestamp.sample.sequence, 2)
    XCTAssertTrue(equalTimestamp.emittedEvents.isEmpty)

    _ = try XCTUnwrap(source.step())
    _ = try XCTUnwrap(source.step())
    let longGap = try XCTUnwrap(source.step())
    XCTAssertEqual(longGap.virtualTimestampNs, 9_000_000_000_000)
    XCTAssertEqual(longGap.sample.sequence, 10)
    XCTAssertTrue(longGap.isFinished)
    XCTAssertEqual(source.lifecycleState, .finished)
  }

  func testEmptyFinalizedTraceFinishesWithoutEvents() throws {
    let source = ReplayMotionSource(detector: LegacyGravityThresholdV1ReplayDetector())
    try source.load(url: fixture("replay/empty.mge.jsonl"))

    let result = try source.run()

    XCTAssertTrue(result.events.isEmpty)
    XCTAssertNil(result.finalVirtualTimestampNs)
    XCTAssertEqual(source.lifecycleState, .finished)
  }

  func testLoaderAcceptsFullSharedV1Fixture() throws {
    let trace = try MotionTraceReplayLoader.load(
      data: Data(contentsOf: fixture("v1/valid/full.mge.jsonl"))
    )

    XCTAssertEqual(trace.samples.count, 2)
    XCTAssertEqual(trace.footer.recordCounts.predictedEvents, 1)
  }

  func testUnknownSchemaVersionIsTyped() throws {
    let original = try String(
      contentsOf: fixture("replay/empty.mge.jsonl"),
      encoding: .utf8
    )
    let data = Data(original.replacingOccurrences(
      of: "1.0.0-draft.1",
      with: "9.0.0",
      options: [],
      range: original.range(of: "1.0.0-draft.1")
    ).utf8)

    XCTAssertThrowsError(try MotionTraceReplayLoader.load(data: data)) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .unsupportedSchemaVersion)
      XCTAssertEqual((error as? ReplayError)?.stage, .load)
    }
  }

  func testUnknownCoreSpecVersionIsTyped() throws {
    let original = try String(
      contentsOf: fixture("replay/empty.mge.jsonl"),
      encoding: .utf8
    )
    let data = Data(
      original.replacingOccurrences(
        of: "\"coreSpecVersion\":\"1.0.0-draft.1\"",
        with: "\"coreSpecVersion\":\"9.0.0\""
      ).utf8
    )

    XCTAssertThrowsError(try MotionTraceReplayLoader.load(data: data)) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .unsupportedSpecVersion)
      XCTAssertEqual((error as? ReplayError)?.stage, .load)
    }
  }

  func testMissingFooterAndFinalizedIncompleteTraceAreTyped() throws {
    let complete = try String(
      contentsOf: fixture("replay/empty.mge.jsonl"),
      encoding: .utf8
    )
    let lines = complete.split(separator: "\n")
    let missingFooter = Data((lines.dropLast().joined(separator: "\n") + "\n").utf8)

    XCTAssertThrowsError(try MotionTraceReplayLoader.load(data: missingFooter)) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .incompleteTrace)
    }
    XCTAssertThrowsError(
      try MotionTraceReplayLoader.load(
        data: Data(contentsOf: fixture("v1/valid/cancelled.mge.jsonl"))
      )
    ) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .incompleteTrace)
    }
  }

  func testMalformedAndNonMonotonicTracesAreTyped() throws {
    XCTAssertThrowsError(try MotionTraceReplayLoader.load(data: Data("{}\n".utf8))) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .malformedRecord)
    }
    XCTAssertThrowsError(
      try MotionTraceReplayLoader.load(
        data: Data(contentsOf: fixture("v1/invalid/nonmonotonic-samples.mge.jsonl"))
      )
    ) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .nonMonotonicSample)
    }
  }

  func testFooterMismatchIsTyped() throws {
    XCTAssertThrowsError(
      try MotionTraceReplayLoader.load(
        data: Data(contentsOf: fixture("v1/invalid/footer-count-mismatch.mge.jsonl"))
      )
    ) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .footerMismatch)
      XCTAssertEqual((error as? ReplayError)?.stage, .load)
    }
  }

  func testLifecycleRejectsRunBeforeLoad() {
    let source = ReplayMotionSource(detector: LegacyGravityThresholdV1ReplayDetector())

    XCTAssertThrowsError(try source.run()) { error in
      XCTAssertEqual((error as? ReplayError)?.code, .invalidState)
      XCTAssertEqual((error as? ReplayError)?.stage, .run)
    }
    XCTAssertEqual(source.lifecycleState, .idle)
  }

  private func expectedPredictions() throws -> [MotionPredictedEventRecord] {
    let contents = try String(
      contentsOf: fixture("replay/legacy-gravity-threshold-v1.expected.jsonl"),
      encoding: .utf8
    )
    let decoder = JSONDecoder()
    return try contents.split(separator: "\n").map { line in
      try decoder.decode(MotionPredictedEventRecord.self, from: Data(line.utf8))
    }
  }

  private func fixture(_ relativePath: String) -> URL {
    let repositoryRoot = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
    return repositoryRoot.appendingPathComponent("fixtures/\(relativePath)")
  }
}
