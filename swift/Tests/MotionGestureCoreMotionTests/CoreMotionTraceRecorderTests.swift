import Foundation
import MotionGestureRecorder
import XCTest

@testable import MotionGestureCoreMotion

final class CoreMotionTraceRecorderTests: XCTestCase {
  func testUnavailableDeviceMotionFailsWithTypedErrorBeforeWriting() throws {
    let driver = FakeCoreMotionDriver(availability: .unavailable)
    let output = MemoryTraceOutput()
    let adapter = try makeAdapter(driver: driver, output: output)

    XCTAssertThrowsError(try adapter.start()) { error in
      XCTAssertEqual(
        (error as? CoreMotionRecorderAdapterError)?.code,
        .requiredCapabilityUnavailable
      )
    }

    XCTAssertEqual(adapter.state, .failed)
    XCTAssertEqual(driver.startCount, 0)
    XCTAssertTrue(output.data.isEmpty)
    XCTAssertFalse(output.committed)

    let missingFrameDriver = FakeCoreMotionDriver(referenceFrames: [])
    let missingFrameAdapter = try makeAdapter(
      driver: missingFrameDriver,
      output: MemoryTraceOutput()
    )
    XCTAssertThrowsError(try missingFrameAdapter.start()) { error in
      XCTAssertEqual(
        (error as? CoreMotionRecorderAdapterError)?.code,
        .attitudeReferenceFrameUnavailable
      )
    }
  }

  func testRequestedTimingMetadataAndObservedTimingRemainDistinct() throws {
    let driver = FakeCoreMotionDriver()
    let output = MemoryTraceOutput()
    let requestedIntervalNs: Int64 = 10_000_000
    let adapter = try makeAdapter(
      driver: driver,
      output: output,
      requestedIntervalNs: requestedIntervalNs,
      initialOrientation: .landscapeLeft
    )

    XCTAssertEqual(adapter.metadata.session.displayRotationClockwiseAtStart, .degrees90)
    XCTAssertEqual(
      adapter.metadata.session.gestureFrameFromDeviceRowMajor,
      [0, -1, 0, 1, 0, 0, 0, 0, 1]
    )
    XCTAssertEqual(adapter.metadata.capabilities.count, 4)
    XCTAssertTrue(
      adapter.metadata.capabilities.allSatisfy {
        $0.requestedTiming?.intervalNs == requestedIntervalNs
          && $0.availability == .available
      })

    try adapter.start()
    XCTAssertEqual(driver.requestedIntervalSeconds, 0.01, accuracy: 0.000_000_001)
    XCTAssertEqual(driver.callbackQueue?.maxConcurrentOperationCount, 1)
    XCTAssertEqual(
      driver.callbackQueue?.name,
      "io.github.mtatsuto.motiongesture.coreMotion"
    )

    driver.emit(.success(raw(timestampSeconds: 100)))
    driver.emit(.success(raw(timestampSeconds: 100.02)))
    let result = try adapter.finish()

    XCTAssertEqual(result.footer.recordCounts.samples, 2)
    XCTAssertEqual(result.footer.observedTiming.count, 4)
    XCTAssertTrue(
      result.footer.observedTiming.allSatisfy {
        $0.acceptedObservationCount == 2
          && $0.minimumIntervalNs == 20_000_000
          && $0.maximumIntervalNs == 20_000_000
      })
    XCTAssertEqual(adapter.state, .finished)
    XCTAssertTrue(output.committed)

    if let exportPath = ProcessInfo.processInfo.environment["MGE_CORE_MOTION_TRACE_OUTPUT"] {
      try output.data.write(to: URL(fileURLWithPath: exportPath))
    }
  }

  func testTimestampConversionPreservesRejectedObservationSequenceGaps() throws {
    XCTAssertEqual(
      try CoreMotionTransforms.timestampNs(
        timestampSeconds: 0.000_000_000_5,
        originSeconds: 0
      ),
      0
    )
    XCTAssertEqual(
      try CoreMotionTransforms.timestampNs(
        timestampSeconds: 0.000_000_001_5,
        originSeconds: 0
      ),
      2
    )

    let driver = FakeCoreMotionDriver()
    let output = MemoryTraceOutput()
    let adapter = try makeAdapter(driver: driver, output: output)
    try adapter.start()

    driver.emit(.success(raw(timestampSeconds: .nan)))
    driver.emit(.success(raw(timestampSeconds: 100)))
    driver.emit(.success(raw(timestampSeconds: 99)))
    driver.emit(.success(raw(timestampSeconds: 100.5)))
    let result = try adapter.finish()

    let samples = try jsonRecords(output.data).filter { $0["recordType"] as? String == "sample" }
    XCTAssertEqual(samples.compactMap { $0["sequence"] as? Int64 }, [1, 3])
    XCTAssertEqual(samples.compactMap { $0["timestampNs"] as? Int64 }, [0, 500_000_000])
    XCTAssertEqual(result.footer.recordCounts.samples, 2)
    XCTAssertEqual(result.footer.droppedSamples.total, 2)
    XCTAssertEqual(
      Set(result.footer.droppedSamples.byReason.map(\.reason)),
      Set([.malformed, .nonMonotonicTimestamp])
    )
  }

  func testOrientationMappingAndRuntimeChangePrecedesNextUnrotatedSample() throws {
    XCTAssertEqual(try CoreMotionInterfaceOrientation.portrait.displayRotationClockwise, .degrees0)
    XCTAssertEqual(
      try CoreMotionInterfaceOrientation.landscapeLeft.displayRotationClockwise,
      .degrees90
    )
    XCTAssertEqual(
      try CoreMotionInterfaceOrientation.portraitUpsideDown.displayRotationClockwise,
      .degrees180
    )
    XCTAssertEqual(
      try CoreMotionInterfaceOrientation.landscapeRight.displayRotationClockwise,
      .degrees270
    )
    XCTAssertThrowsError(try CoreMotionInterfaceOrientation.unknown.displayRotationClockwise)

    let driver = FakeCoreMotionDriver()
    let output = MemoryTraceOutput()
    let adapter = try makeAdapter(driver: driver, output: output)
    try adapter.start()
    driver.emit(.success(raw(timestampSeconds: 10)))
    try adapter.updateInterfaceOrientation(.landscapeLeft)
    driver.emit(.success(raw(timestampSeconds: 10.25)))
    let result = try adapter.finish()

    let records = try jsonRecords(output.data)
    XCTAssertEqual(
      records.compactMap { $0["recordType"] as? String },
      ["traceHeader", "sample", "displayRotationChange", "sample", "traceFooter"]
    )
    let change = try XCTUnwrap(
      records.first { $0["recordType"] as? String == "displayRotationChange" })
    XCTAssertEqual(change["timestampNs"] as? Int64, 250_000_000)
    XCTAssertEqual(change["changeSequence"] as? Int64, 0)
    XCTAssertEqual(change["displayRotationClockwise"] as? Int64, 90)
    let secondSample = records.filter { $0["recordType"] as? String == "sample" }[1]
    XCTAssertEqual(secondSample["timestampNs"] as? Int64, 250_000_000)
    let gravity = try XCTUnwrap(
      ((secondSample["signals"] as? [String: Any])?["gravity"] as? [String: Any])?["value"]
        as? [Double])
    XCTAssertEqual(gravity, [0.1, -0.2, 0.9])
    XCTAssertEqual(result.footer.recordCounts.displayRotationChanges, 1)
    if let exportPath = ProcessInfo.processInfo.environment[
      "MGE_CORE_MOTION_ORIENTATION_TRACE_OUTPUT"
    ] {
      try output.data.write(to: URL(fileURLWithPath: exportPath))
    }
  }

  func testMagneticReferenceAppliesENUTransformAndDeclaredQuaternionInversion() throws {
    let driver = FakeCoreMotionDriver(nativeDirection: .deviceFromReference)
    let output = MemoryTraceOutput()
    let adapter = try makeAdapter(
      driver: driver,
      output: output,
      referenceFrame: .xMagneticNorthZVertical,
      localReferenceInstanceId: nil
    )

    let attitudeCapability = try XCTUnwrap(
      adapter.metadata.capabilities.first { $0.signalKind == .attitude })
    XCTAssertEqual(attitudeCapability.nativeSignConvention, .deviceFromReference)
    XCTAssertEqual(
      attitudeCapability.conversions.map(\.rawValue),
      [
        MotionConversion.quaternionInvert.rawValue,
        MotionConversion.referenceBasisTransform.rawValue,
      ]
    )
    XCTAssertEqual(adapter.metadata.session.attitudeReference?.kind, .eastNorthUpMagnetic)
    XCTAssertEqual(adapter.metadata.session.attitudeReference?.scope, .global)

    try adapter.start()
    driver.emit(.success(rawZQuarterTurn(timestampSeconds: 50)))
    _ = try adapter.finish()

    let sample = try XCTUnwrap(
      try jsonRecords(output.data).first { $0["recordType"] as? String == "sample" })
    let attitude = try XCTUnwrap(
      ((sample["signals"] as? [String: Any])?["attitude"] as? [String: Any])?["value"]
        as? [Double])
    XCTAssertEqual(attitude[0], 0, accuracy: 0.000_000_001)
    XCTAssertEqual(attitude[1], 0, accuracy: 0.000_000_001)
    XCTAssertEqual(attitude[2], 0, accuracy: 0.000_000_001)
    XCTAssertEqual(attitude[3], 1, accuracy: 0.000_000_001)
  }

  func testQuaternionRotationMatrixMismatchIsReportedAsMalformed() throws {
    let driver = FakeCoreMotionDriver()
    let output = MemoryTraceOutput()
    let adapter = try makeAdapter(driver: driver, output: output)
    try adapter.start()
    let quarterTurn = rawZQuarterTurn(timestampSeconds: 7)
    driver.emit(
      .success(
        CoreMotionRawDeviceMotion(
          timestampSeconds: quarterTurn.timestampSeconds,
          gravity: quarterTurn.gravity,
          userAcceleration: quarterTurn.userAcceleration,
          rotationRate: quarterTurn.rotationRate,
          attitudeQuaternion: quarterTurn.attitudeQuaternion,
          attitudeRotationMatrix: raw(timestampSeconds: 7).attitudeRotationMatrix
        )))
    let result = try adapter.finish()

    XCTAssertEqual(result.footer.recordCounts.samples, 0)
    XCTAssertEqual(result.footer.droppedSamples.total, 1)
    XCTAssertEqual(result.footer.droppedSamples.byReason.first?.reason, .malformed)
  }

  func testDriverFailureFinalizesFailedTraceAndStopsSource() throws {
    let driver = FakeCoreMotionDriver()
    let output = MemoryTraceOutput()
    let adapter = try makeAdapter(driver: driver, output: output)
    try adapter.start()
    driver.emit(.success(raw(timestampSeconds: 20)))
    driver.emit(
      .failure(
        CoreMotionDriverFailure(
          domain: "CMErrorDomain",
          code: 109,
          diagnostic: "injected source failure"
        )))

    XCTAssertEqual(adapter.state, .failed)
    XCTAssertEqual(adapter.terminalError?.code, .driverFailure)
    XCTAssertEqual(adapter.terminalError?.nativeDomain, "CMErrorDomain")
    XCTAssertEqual(adapter.terminalError?.nativeCode, 109)
    XCTAssertGreaterThan(driver.stopCount, 0)
    let result = try XCTUnwrap(adapter.result)
    XCTAssertEqual(result.footer.finalizationStatus, .failed)
    XCTAssertEqual(result.footer.terminationReason, .sourceFailure)
    XCTAssertEqual(result.footer.failureCode, "coreMotion.driverFailure")
    XCTAssertTrue(output.committed)
  }

  private func makeAdapter(
    driver: FakeCoreMotionDriver,
    output: MemoryTraceOutput,
    requestedIntervalNs: Int64 = 33_333_333,
    initialOrientation: CoreMotionInterfaceOrientation = .portrait,
    referenceFrame: CoreMotionAttitudeReferenceFrame = .xArbitraryZVertical,
    localReferenceInstanceId: String? = "20000000-0000-4000-8000-000000000001"
  ) throws -> CoreMotionTraceRecorder {
    try CoreMotionTraceRecorder(
      trace: CoreMotionTraceContext(
        traceId: "20000000-0000-4000-8000-000000000002",
        libraryName: "motionGestureEngineTests",
        libraryVersion: "0.1.0",
        privacy: MotionTracePrivacy(tier: .synthetic, dataClasses: [.motionSensorData]),
        device: MotionDeviceMetadata(platformFamily: .ios)
      ),
      configuration: CoreMotionRecorderConfiguration(
        requestedUpdateIntervalNs: requestedIntervalNs,
        attitudeReferenceFrame: referenceFrame,
        initialInterfaceOrientation: initialOrientation,
        localReferenceInstanceId: localReferenceInstanceId
      ),
      limits: MotionTraceRecorderLimits(
        maximumDurationNs: 10_000_000_000,
        maximumSamples: 100,
        maximumBytes: 131_072
      ),
      output: output,
      driver: driver
    )
  }

  private func raw(timestampSeconds: TimeInterval) -> CoreMotionRawDeviceMotion {
    CoreMotionRawDeviceMotion(
      timestampSeconds: timestampSeconds,
      gravity: CoreMotionRawVector3(x: 0.1, y: -0.2, z: 0.9),
      userAcceleration: CoreMotionRawVector3(x: 0.01, y: 0.02, z: 0.03),
      rotationRate: CoreMotionRawVector3(x: 0.4, y: 0.5, z: 0.6),
      attitudeQuaternion: CoreMotionRawQuaternion(x: 0, y: 0, z: 0, w: 1),
      attitudeRotationMatrix: CoreMotionRawRotationMatrix(
        m11: 1,
        m12: 0,
        m13: 0,
        m21: 0,
        m22: 1,
        m23: 0,
        m31: 0,
        m32: 0,
        m33: 1
      )
    )
  }

  private func rawZQuarterTurn(timestampSeconds: TimeInterval) -> CoreMotionRawDeviceMotion {
    let halfSqrt = sqrt(0.5)
    return CoreMotionRawDeviceMotion(
      timestampSeconds: timestampSeconds,
      gravity: CoreMotionRawVector3(x: 0.1, y: -0.2, z: 0.9),
      userAcceleration: CoreMotionRawVector3(x: 0.01, y: 0.02, z: 0.03),
      rotationRate: CoreMotionRawVector3(x: 0.4, y: 0.5, z: 0.6),
      attitudeQuaternion: CoreMotionRawQuaternion(x: 0, y: 0, z: halfSqrt, w: halfSqrt),
      attitudeRotationMatrix: CoreMotionRawRotationMatrix(
        m11: 0,
        m12: -1,
        m13: 0,
        m21: 1,
        m22: 0,
        m23: 0,
        m31: 0,
        m32: 0,
        m33: 1
      )
    )
  }

  private func jsonRecords(_ data: Data) throws -> [[String: Any]] {
    try data.split(separator: 0x0A).map { line in
      try XCTUnwrap(JSONSerialization.jsonObject(with: Data(line)) as? [String: Any])
    }
  }
}

private final class FakeCoreMotionDriver: CoreMotionDeviceMotionDriving, @unchecked Sendable {
  let deviceMotionAvailability: MotionCapabilityAvailability
  let availableAttitudeReferenceFrames: Set<CoreMotionAttitudeReferenceFrame>
  let attitudeNativeDirection: MotionNativeSignConvention
  private(set) var startCount = 0
  private(set) var stopCount = 0
  private(set) var requestedIntervalSeconds: TimeInterval = 0
  private(set) var requestedReferenceFrame: CoreMotionAttitudeReferenceFrame?
  private(set) var callbackQueue: OperationQueue?
  private var handler:
    (
      @Sendable (
        Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>
      ) -> Void
    )?

  init(
    availability: MotionCapabilityAvailability = .available,
    referenceFrames: Set<CoreMotionAttitudeReferenceFrame> = Set(
      CoreMotionAttitudeReferenceFrame.allCases),
    nativeDirection: MotionNativeSignConvention = .referenceFromDevice
  ) {
    deviceMotionAvailability = availability
    availableAttitudeReferenceFrames = referenceFrames
    attitudeNativeDirection = nativeDirection
  }

  func startDeviceMotionUpdates(
    requestedIntervalSeconds: TimeInterval,
    referenceFrame: CoreMotionAttitudeReferenceFrame,
    queue: OperationQueue,
    handler:
      @escaping @Sendable (
        Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>
      ) -> Void
  ) throws {
    startCount += 1
    self.requestedIntervalSeconds = requestedIntervalSeconds
    requestedReferenceFrame = referenceFrame
    callbackQueue = queue
    self.handler = handler
  }

  func stopDeviceMotionUpdates() {
    stopCount += 1
  }

  func emit(_ event: Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>) {
    guard let callbackQueue, let handler else {
      XCTFail("fake driver emitted before start")
      return
    }
    callbackQueue.addOperations(
      [BlockOperation { handler(event) }],
      waitUntilFinished: true
    )
  }
}

private final class MemoryTraceOutput: MotionTraceOutput {
  let temporaryURL: URL? = nil
  let destinationURL: URL? = nil
  private(set) var data = Data()
  private(set) var committed = false

  func start() throws {}

  func write(_ data: Data) throws -> MotionTraceWriteDisposition {
    self.data.append(data)
    return .written
  }

  func commit() throws -> URL? {
    committed = true
    return nil
  }

  func abortPreservingPartial() {}
}
