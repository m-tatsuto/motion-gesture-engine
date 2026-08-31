import Foundation
import MotionGestureRecorder

public final class CoreMotionTraceRecorder: @unchecked Sendable {
  public static let adapterName = "coreMotionDeviceMotion"
  public static let adapterVersion = "0.1.0"

  public let metadata: MotionTraceMetadata

  private let configuration: CoreMotionRecorderConfiguration
  private let driver: any CoreMotionDeviceMotionDriving
  private let recorder: MotionTraceRecorder
  private let callbackQueue: OperationQueue
  private let lock = NSLock()
  private let availability: MotionCapabilityAvailability
  private let referenceFrameAvailable: Bool

  private var internalState: CoreMotionRecorderAdapterState = .idle
  private var internalTerminalError: CoreMotionRecorderAdapterError?
  private var originSeconds: TimeInterval?
  private var lastTimelineTimestampNs: Int64 = 0
  private var nextSampleSequence: Int64 = 0
  private var currentDisplayRotation: DisplayRotationClockwise
  private var pendingDisplayRotation: DisplayRotationClockwise?
  private var nextDisplayRotationChangeSequence: Int64 = 0

  public var state: CoreMotionRecorderAdapterState {
    lock.withLock { internalState }
  }

  public var result: MotionTraceRecordingResult? { recorder.result }

  public var terminalError: CoreMotionRecorderAdapterError? {
    lock.withLock { internalTerminalError }
  }

  deinit {
    driver.stopDeviceMotionUpdates()
  }

  public init(
    trace: CoreMotionTraceContext,
    configuration: CoreMotionRecorderConfiguration,
    limits: MotionTraceRecorderLimits,
    output: MotionTraceOutput,
    driver: any CoreMotionDeviceMotionDriving
  ) throws {
    guard
      (1...MotionTraceV1.maximumSafeInteger).contains(
        configuration.requestedUpdateIntervalNs)
    else {
      throw CoreMotionRecorderAdapterError(
        code: .invalidConfiguration,
        diagnostic: "requestedUpdateIntervalNs must be a positive wire-safe integer"
      )
    }
    let requestedSeconds = Double(configuration.requestedUpdateIntervalNs) / 1_000_000_000
    guard requestedSeconds.isFinite, requestedSeconds > 0 else {
      throw CoreMotionRecorderAdapterError(
        code: .invalidConfiguration,
        diagnostic: "requested update interval cannot be represented in seconds"
      )
    }
    guard
      driver.attitudeNativeDirection == .referenceFromDevice
        || driver.attitudeNativeDirection == .deviceFromReference
    else {
      throw CoreMotionRecorderAdapterError(
        code: .invalidConfiguration,
        diagnostic: "driver must declare an explicit attitude quaternion direction"
      )
    }
    if let device = trace.device, device.platformFamily != .ios {
      throw CoreMotionRecorderAdapterError(
        code: .invalidConfiguration,
        diagnostic: "Core Motion trace device metadata must use the iOS platform family"
      )
    }

    let initialRotation = try configuration.initialInterfaceOrientation.displayRotationClockwise
    currentDisplayRotation = initialRotation
    self.configuration = configuration
    self.driver = driver
    availability = driver.deviceMotionAvailability
    referenceFrameAvailable = driver.availableAttitudeReferenceFrames.contains(
      configuration.attitudeReferenceFrame)

    let attitudeReference = try Self.attitudeReference(for: configuration)
    let capabilities = Self.capabilities(
      availability: availability,
      referenceFrameAvailable: referenceFrameAvailable,
      requestedIntervalNs: configuration.requestedUpdateIntervalNs,
      referenceFrame: configuration.attitudeReferenceFrame,
      nativeDirection: driver.attitudeNativeDirection
    )
    let gestureFrame =
      configuration.gestureFrameFromDeviceRowMajor
      ?? CoreMotionTransforms.gestureFrameFromDevice(for: initialRotation)
    metadata = MotionTraceMetadata(
      traceId: trace.traceId,
      producer: MotionTraceProducer(
        libraryName: trace.libraryName,
        libraryVersion: trace.libraryVersion,
        platformAdapterName: Self.adapterName,
        platformAdapterVersion: Self.adapterVersion
      ),
      privacy: trace.privacy,
      session: MotionTraceSession(
        displayRotationClockwiseAtStart: initialRotation,
        gestureFrameFromDeviceRowMajor: gestureFrame,
        attitudeReference: attitudeReference
      ),
      capabilities: capabilities,
      detectors: trace.detectors,
      device: trace.device
    )
    recorder = MotionTraceRecorder(metadata: metadata, limits: limits, output: output)

    let queue = OperationQueue()
    queue.name = "io.github.mtatsuto.motiongesture.coreMotion"
    queue.maxConcurrentOperationCount = 1
    queue.qualityOfService = .userInitiated
    queue.isSuspended = true
    callbackQueue = queue
  }

  public convenience init(
    trace: CoreMotionTraceContext,
    configuration: CoreMotionRecorderConfiguration,
    limits: MotionTraceRecorderLimits,
    destinationURL: URL
  ) throws {
    try self.init(
      trace: trace,
      configuration: configuration,
      limits: limits,
      output: AtomicFileMotionTraceOutput(destinationURL: destinationURL),
      driver: CMMotionManagerDeviceMotionDriver()
    )
  }

  public func start() throws {
    try lock.withLock {
      guard internalState == .idle else {
        throw adapterError(.invalidState, "cannot start while adapter is \(internalState.rawValue)")
      }
      if availability != .available {
        let error = adapterError(
          .requiredCapabilityUnavailable,
          "Core Motion device motion is \(availability.rawValue)"
        )
        internalState = .failed
        internalTerminalError = error
        throw error
      }
      if !referenceFrameAvailable {
        let error = adapterError(
          .attitudeReferenceFrameUnavailable,
          "requested attitude reference frame is unavailable"
        )
        internalState = .failed
        internalTerminalError = error
        throw error
      }
      internalState = .starting
    }

    do {
      try recorder.start()
    } catch {
      let adapterError = recorderError(error)
      lock.withLock {
        internalState = .failed
        internalTerminalError = adapterError
      }
      throw adapterError
    }

    do {
      try driver.startDeviceMotionUpdates(
        requestedIntervalSeconds: Double(configuration.requestedUpdateIntervalNs)
          / 1_000_000_000,
        referenceFrame: configuration.attitudeReferenceFrame,
        queue: callbackQueue
      ) { [weak self] event in
        self?.receive(event)
      }
    } catch {
      var failure = driverError(error)
      driver.stopDeviceMotionUpdates()
      callbackQueue.cancelAllOperations()
      do {
        _ = try recorder.failSource(
          code: "coreMotion.driverFailure",
          durationNs: lastTimelineTimestampNs
        )
      } catch {
        failure = recorderError(error)
      }
      lock.withLock {
        internalState = .failed
        internalTerminalError = failure
      }
      throw failure
    }
    lock.withLock { internalState = .running }
    callbackQueue.isSuspended = false
  }

  public func updateInterfaceOrientation(_ orientation: CoreMotionInterfaceOrientation) throws {
    let rotation = try orientation.displayRotationClockwise
    try lock.withLock {
      guard internalState == .running else {
        throw adapterError(
          .invalidState,
          "cannot update orientation while adapter is \(internalState.rawValue)"
        )
      }
      pendingDisplayRotation = rotation == currentDisplayRotation ? nil : rotation
    }
  }

  public func finish() throws -> MotionTraceRecordingResult {
    try finalize(cancelled: false)
  }

  public func cancel() throws -> MotionTraceRecordingResult {
    try finalize(cancelled: true)
  }

  private func receive(
    _ event: Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>
  ) {
    guard lock.withLock({ internalState == .running }) else { return }
    switch event {
    case .failure(let failure):
      handleDriverFailure(failure)
    case .success(let raw):
      handle(raw)
    }
  }

  private func handle(_ raw: CoreMotionRawDeviceMotion) {
    let reservedSequence: Int64
    do {
      reservedSequence = try reserveSampleSequence()
    } catch let error as CoreMotionRecorderAdapterError {
      handleTerminalAdapterFailure(error, failureCode: "coreMotion.sequenceOverflow")
      return
    } catch {
      return
    }

    let timestampNs: Int64
    do {
      guard raw.timestampSeconds.isFinite, raw.timestampSeconds >= 0 else {
        throw CoreMotionTransformError.malformed
      }
      if originSeconds == nil { originSeconds = raw.timestampSeconds }
      timestampNs = try CoreMotionTransforms.timestampNs(
        timestampSeconds: raw.timestampSeconds,
        originSeconds: originSeconds ?? raw.timestampSeconds
      )
      guard timestampNs >= lastTimelineTimestampNs else {
        throw CoreMotionTransformError.nonMonotonicTimestamp
      }
    } catch CoreMotionTransformError.nonMonotonicTimestamp {
      reportDrop(.nonMonotonicTimestamp)
      return
    } catch {
      reportDrop(.malformed)
      return
    }

    let signals: MotionSignals
    do {
      signals = MotionSignals(
        gravity: MotionVectorObservation(
          capabilityId: CoreMotionCapabilityID.gravity,
          value: try CoreMotionTransforms.vector(raw.gravity)
        ),
        userAcceleration: MotionVectorObservation(
          capabilityId: CoreMotionCapabilityID.userAcceleration,
          value: try CoreMotionTransforms.vector(raw.userAcceleration)
        ),
        rotationRate: MotionVectorObservation(
          capabilityId: CoreMotionCapabilityID.rotationRate,
          value: try CoreMotionTransforms.vector(raw.rotationRate)
        ),
        attitude: try CoreMotionTransforms.attitude(
          quaternion: raw.attitudeQuaternion,
          rotationMatrix: raw.attitudeRotationMatrix,
          nativeDirection: driver.attitudeNativeDirection,
          referenceFrame: configuration.attitudeReferenceFrame
        )
      )
    } catch {
      lastTimelineTimestampNs = timestampNs
      reportDrop(.malformed)
      return
    }

    lastTimelineTimestampNs = timestampNs
    do {
      if let pending = lock.withLock({ pendingDisplayRotation }) {
        let change = MotionDisplayRotationChange(
          timestampNs: timestampNs,
          changeSequence: nextDisplayRotationChangeSequence,
          displayRotationClockwise: pending
        )
        let outcome = try recorder.append(change)
        if let result = outcome.recordingResult {
          transitionToTerminal(result)
          return
        }
        if outcome.accepted {
          nextDisplayRotationChangeSequence += 1
          lock.withLock {
            currentDisplayRotation = pending
            if pendingDisplayRotation == pending { pendingDisplayRotation = nil }
          }
        }
      }

      let outcome = try recorder.append(
        MotionSample(
          timestampNs: timestampNs,
          sequence: reservedSequence,
          signals: signals
        ))
      if let result = outcome.recordingResult { transitionToTerminal(result) }
    } catch {
      handleRecorderFailure(error)
    }
  }

  private func reserveSampleSequence() throws -> Int64 {
    guard nextSampleSequence <= MotionTraceV1.maximumSafeInteger else {
      throw adapterError(.driverFailure, "sample sequence exceeded the wire-safe range")
    }
    defer { nextSampleSequence += 1 }
    return nextSampleSequence
  }

  private func reportDrop(_ reason: DroppedSampleReason) {
    do {
      try recorder.reportDroppedSample(reason)
    } catch {
      handleRecorderFailure(error)
    }
  }

  private func handleDriverFailure(_ failure: CoreMotionDriverFailure) {
    let error = CoreMotionRecorderAdapterError(
      code: .driverFailure,
      diagnostic: failure.diagnostic,
      nativeDomain: failure.domain,
      nativeCode: failure.code
    )
    handleTerminalAdapterFailure(error, failureCode: "coreMotion.driverFailure")
  }

  private func handleTerminalAdapterFailure(
    _ error: CoreMotionRecorderAdapterError,
    failureCode: String
  ) {
    driver.stopDeviceMotionUpdates()
    do {
      _ = try recorder.failSource(
        code: failureCode,
        durationNs: lastTimelineTimestampNs
      )
    } catch {
      lock.withLock {
        internalState = .failed
        internalTerminalError = recorderError(error)
      }
      return
    }
    lock.withLock {
      internalState = .failed
      internalTerminalError = error
    }
  }

  private func handleRecorderFailure(_ error: Error) {
    driver.stopDeviceMotionUpdates()
    let failure = recorderError(error)
    lock.withLock {
      internalState = .failed
      internalTerminalError = failure
    }
  }

  private func finalize(cancelled: Bool) throws -> MotionTraceRecordingResult {
    let initialState = state
    if initialState == .idle || initialState == .starting {
      throw adapterError(.invalidState, "cannot finalize while adapter is \(initialState.rawValue)")
    }
    if initialState == .failed, let terminalError, recorder.result == nil {
      throw terminalError
    }

    driver.stopDeviceMotionUpdates()
    if OperationQueue.current !== callbackQueue {
      callbackQueue.waitUntilAllOperationsAreFinished()
    }
    lock.withLock {
      if internalState == .running { internalState = .finalizing }
    }

    do {
      let result =
        cancelled
        ? try recorder.cancel(durationNs: lastTimelineTimestampNs)
        : try recorder.finish(durationNs: lastTimelineTimestampNs)
      transitionToTerminal(result)
      return result
    } catch {
      let failure = recorderError(error)
      lock.withLock {
        internalState = .failed
        internalTerminalError = failure
      }
      throw failure
    }
  }

  private func transitionToTerminal(_ result: MotionTraceRecordingResult) {
    driver.stopDeviceMotionUpdates()
    lock.withLock {
      switch result.footer.finalizationStatus {
      case .complete, .bounded:
        internalState = .finished
      case .cancelled:
        internalState = .cancelled
      case .failed:
        internalState = .failed
      }
    }
  }

  private func adapterError(
    _ code: CoreMotionRecorderAdapterErrorCode,
    _ diagnostic: String
  ) -> CoreMotionRecorderAdapterError {
    CoreMotionRecorderAdapterError(code: code, diagnostic: diagnostic)
  }

  private func driverError(_ error: Error) -> CoreMotionRecorderAdapterError {
    if let failure = error as? CoreMotionDriverFailure {
      return CoreMotionRecorderAdapterError(
        code: .driverFailure,
        diagnostic: failure.diagnostic,
        nativeDomain: failure.domain,
        nativeCode: failure.code
      )
    }
    return adapterError(.driverFailure, String(describing: error))
  }

  private func recorderError(_ error: Error) -> CoreMotionRecorderAdapterError {
    if let error = error as? CoreMotionRecorderAdapterError { return error }
    return adapterError(.recorderFailure, String(describing: error))
  }

  private static func attitudeReference(
    for configuration: CoreMotionRecorderConfiguration
  ) throws -> MotionAttitudeReference {
    switch configuration.attitudeReferenceFrame {
    case .xArbitraryZVertical, .xArbitraryCorrectedZVertical:
      return MotionAttitudeReference(
        kind: .gravityAlignedSessionLocal,
        scope: .session,
        referenceInstanceId: configuration.localReferenceInstanceId
          ?? UUID().uuidString.lowercased(),
        nativeReferenceId: nativeReferenceId(configuration.attitudeReferenceFrame)
      )
    case .xMagneticNorthZVertical:
      guard configuration.localReferenceInstanceId == nil else {
        throw CoreMotionRecorderAdapterError(
          code: .invalidConfiguration,
          diagnostic: "global magnetic reference must not have a session instance ID"
        )
      }
      return MotionAttitudeReference(
        kind: .eastNorthUpMagnetic,
        scope: .global,
        nativeReferenceId: nativeReferenceId(configuration.attitudeReferenceFrame)
      )
    case .xTrueNorthZVertical:
      guard configuration.localReferenceInstanceId == nil else {
        throw CoreMotionRecorderAdapterError(
          code: .invalidConfiguration,
          diagnostic: "global true-north reference must not have a session instance ID"
        )
      }
      return MotionAttitudeReference(
        kind: .eastNorthUpTrue,
        scope: .global,
        nativeReferenceId: nativeReferenceId(configuration.attitudeReferenceFrame)
      )
    }
  }

  private static func capabilities(
    availability: MotionCapabilityAvailability,
    referenceFrameAvailable: Bool,
    requestedIntervalNs: Int64,
    referenceFrame: CoreMotionAttitudeReferenceFrame,
    nativeDirection: MotionNativeSignConvention
  ) -> [MotionCapability] {
    let requestedTiming = MotionRequestedTiming(
      intervalNs: requestedIntervalNs,
      nativeModeIdentifier: "apple.coreMotion.deviceMotion"
    )
    let attitudeAvailability: MotionCapabilityAvailability =
      availability == .available && !referenceFrameAvailable ? .unavailable : availability
    var attitudeConversions: [MotionConversion] = []
    if nativeDirection == .deviceFromReference { attitudeConversions.append(.quaternionInvert) }
    if referenceFrame == .xMagneticNorthZVertical || referenceFrame == .xTrueNorthZVertical {
      attitudeConversions.append(.referenceBasisTransform)
    }
    if attitudeConversions.isEmpty { attitudeConversions = [.none] }

    return [
      MotionCapability(
        capabilityId: CoreMotionCapabilityID.gravity,
        signalKind: .gravity,
        requirement: .required,
        biasCorrection: .notApplicable,
        availability: availability,
        sourceKind: .fused,
        nativeSourceIdentifier: "apple.coreMotion.deviceMotion.gravity",
        nativeUnit: .standardGravity,
        nativeSignConvention: .physicalGravity,
        conversions: [.none],
        requestedTiming: requestedTiming
      ),
      MotionCapability(
        capabilityId: CoreMotionCapabilityID.userAcceleration,
        signalKind: .userAcceleration,
        requirement: .required,
        biasCorrection: .notApplicable,
        availability: availability,
        sourceKind: .fused,
        nativeSourceIdentifier: "apple.coreMotion.deviceMotion.userAcceleration",
        nativeUnit: .standardGravity,
        nativeSignConvention: .gravityRemovedAcceleration,
        conversions: [.none],
        requestedTiming: requestedTiming
      ),
      MotionCapability(
        capabilityId: CoreMotionCapabilityID.rotationRate,
        signalKind: .rotationRate,
        requirement: .required,
        biasCorrection: .biasCorrected,
        availability: availability,
        sourceKind: .fused,
        nativeSourceIdentifier: "apple.coreMotion.deviceMotion.rotationRate",
        nativeUnit: .radianPerSecond,
        nativeSignConvention: .rightHandAngularVelocity,
        conversions: [.none],
        requestedTiming: requestedTiming
      ),
      MotionCapability(
        capabilityId: CoreMotionCapabilityID.attitude,
        signalKind: .attitude,
        requirement: .required,
        biasCorrection: .notApplicable,
        availability: attitudeAvailability,
        sourceKind: .fused,
        nativeSourceIdentifier: "apple.coreMotion.deviceMotion.attitude",
        nativeUnit: .unitQuaternion,
        nativeSignConvention: nativeDirection,
        conversions: attitudeConversions,
        requestedTiming: requestedTiming
      ),
    ]
  }

  private static func nativeReferenceId(
    _ frame: CoreMotionAttitudeReferenceFrame
  ) -> String {
    "apple.coreMotion.attitude.\(frame.rawValue)"
  }
}

extension NSLock {
  fileprivate func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock()
    defer { unlock() }
    return try body()
  }
}
