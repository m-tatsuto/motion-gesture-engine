import Foundation
import XCTest

@testable import MotionGestureCore

final class LegacyGravityThresholdV1Tests: XCTestCase {
  func testImmutableConfigurationIdentityAndThresholds() {
    XCTAssertEqual(LegacyGravityThresholdV1Configuration.detectorId, "LegacyGravityThresholdV1")
    XCTAssertEqual(LegacyGravityThresholdV1Configuration.detectorVersion, "1.0.0")
    XCTAssertEqual(LegacyGravityThresholdV1Configuration.configurationIdentity, "legacy.default.v1")
    XCTAssertEqual(LegacyGravityThresholdV1Configuration.triggerMagnitude, 0.65)
    XCTAssertEqual(LegacyGravityThresholdV1Configuration.rearmMagnitude, 0.35)
  }

  func testSharedCharacterizationFixture() throws {
    var detector = LegacyGravityThresholdV1()
    var emittedGestures: [Gesture] = []

    for row in try fixtureRows() {
      switch row.operation {
      case "start":
        try detector.start()
      case "stop":
        try detector.stop()
      case "reset":
        detector.reset()
      case "sample":
        let event = try detector.consume(
          GestureFrameGravitySample(
            timestampNs: try XCTUnwrap(row.timestampNs),
            sequence: try XCTUnwrap(row.sequence),
            gravityZG: try XCTUnwrap(row.gravityZG)
          )
        )
        XCTAssertEqual(event?.gesture.rawValue, row.expectedGesture, "fixture step \(row.step)")
        if let event {
          XCTAssertEqual(event.timestampNs, row.timestampNs)
          XCTAssertEqual(event.sourceSampleSequence, row.sequence)
          emittedGestures.append(event.gesture)
        }
      default:
        XCTFail("unsupported fixture operation \(row.operation) at step \(row.step)")
      }
    }

    XCTAssertEqual(
      emittedGestures,
      [.tiltForward, .tiltBackward, .tiltForward, .tiltBackward, .tiltForward]
    )
  }

  func testLifecycleRejectsInvalidOperations() throws {
    var detector = LegacyGravityThresholdV1()
    let sample = GestureFrameGravitySample(timestampNs: 0, sequence: 0, gravityZG: 0)

    XCTAssertThrowsError(try detector.consume(sample)) { error in
      XCTAssertEqual(
        error as? DetectorInvalidStateError,
        DetectorInvalidStateError(operation: .consume, state: .idle)
      )
    }

    try detector.start()
    XCTAssertThrowsError(try detector.start()) { error in
      XCTAssertEqual(
        error as? DetectorInvalidStateError,
        DetectorInvalidStateError(operation: .start, state: .running)
      )
    }

    try detector.stop()
    XCTAssertThrowsError(try detector.stop()) { error in
      XCTAssertEqual(
        error as? DetectorInvalidStateError,
        DetectorInvalidStateError(operation: .stop, state: .stopped)
      )
    }
  }

  func testStopAndResetRestoreArmedState() throws {
    var detector = LegacyGravityThresholdV1()
    let crossingSample = GestureFrameGravitySample(timestampNs: 0, sequence: 0, gravityZG: 1)

    try detector.start()
    XCTAssertNotNil(try detector.consume(crossingSample))
    XCTAssertFalse(detector.isArmed)
    try detector.stop()
    XCTAssertTrue(detector.isArmed)
    XCTAssertEqual(detector.lifecycleState, .stopped)

    try detector.start()
    XCTAssertNotNil(try detector.consume(crossingSample))
    XCTAssertFalse(detector.isArmed)
    detector.reset()
    XCTAssertTrue(detector.isArmed)
    XCTAssertEqual(detector.lifecycleState, .idle)
  }

  private func fixtureRows() throws -> [FixtureRow] {
    let repositoryRoot = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
    let fixtureURL =
      repositoryRoot
      .appendingPathComponent("fixtures/characterization/legacy-gravity-threshold-v1.csv")
    let contents = try String(contentsOf: fixtureURL, encoding: .utf8)

    return
      try contents
      .split(whereSeparator: \Character.isNewline)
      .dropFirst()
      .map { line in
        let columns = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        guard columns.count == 6, let step = Int(columns[0]) else {
          throw FixtureError.invalidRow(String(line))
        }

        return FixtureRow(
          step: step,
          operation: columns[1],
          timestampNs: Int64(columns[2]),
          sequence: Int64(columns[3]),
          gravityZG: Double(columns[4]),
          expectedGesture: columns[5].isEmpty ? nil : columns[5]
        )
      }
  }
}

private struct FixtureRow {
  let step: Int
  let operation: String
  let timestampNs: Int64?
  let sequence: Int64?
  let gravityZG: Double?
  let expectedGesture: String?
}

private enum FixtureError: Error {
  case invalidRow(String)
}
