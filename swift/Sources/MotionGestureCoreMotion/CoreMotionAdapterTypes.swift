import Foundation
import MotionGestureRecorder

public enum CoreMotionInterfaceOrientation: String, CaseIterable, Sendable {
  case portrait
  case portraitUpsideDown
  case landscapeLeft
  case landscapeRight
  case unknown

  public var displayRotationClockwise: DisplayRotationClockwise {
    get throws {
      switch self {
      case .portrait:
        return .degrees0
      case .landscapeLeft:
        return .degrees90
      case .portraitUpsideDown:
        return .degrees180
      case .landscapeRight:
        return .degrees270
      case .unknown:
        throw CoreMotionRecorderAdapterError(
          code: .unsupportedInterfaceOrientation,
          diagnostic: "unknown interface orientation cannot define a trace frame"
        )
      }
    }
  }
}

public enum CoreMotionAttitudeReferenceFrame: String, CaseIterable, Hashable, Sendable {
  case xArbitraryZVertical
  case xArbitraryCorrectedZVertical
  case xMagneticNorthZVertical
  case xTrueNorthZVertical
}

public enum CoreMotionRecorderAdapterState: String, Sendable {
  case idle
  case starting
  case running
  case finalizing
  case finished
  case cancelled
  case failed
}

public enum CoreMotionRecorderAdapterErrorCode: String, Sendable {
  case invalidConfiguration
  case invalidState
  case requiredCapabilityUnavailable
  case attitudeReferenceFrameUnavailable
  case unsupportedInterfaceOrientation
  case driverFailure
  case recorderFailure
}

public struct CoreMotionRecorderAdapterError: Error, Equatable, Sendable {
  public let code: CoreMotionRecorderAdapterErrorCode
  public let diagnostic: String
  public let nativeDomain: String?
  public let nativeCode: Int?

  public init(
    code: CoreMotionRecorderAdapterErrorCode,
    diagnostic: String,
    nativeDomain: String? = nil,
    nativeCode: Int? = nil
  ) {
    self.code = code
    self.diagnostic = diagnostic
    self.nativeDomain = nativeDomain
    self.nativeCode = nativeCode
  }
}

extension CoreMotionRecorderAdapterError: LocalizedError {
  public var errorDescription: String? { "\(code.rawValue): \(diagnostic)" }
}

public struct CoreMotionDriverFailure: Error, Equatable, Sendable {
  public let domain: String
  public let code: Int
  public let diagnostic: String

  public init(domain: String, code: Int, diagnostic: String) {
    self.domain = domain
    self.code = code
    self.diagnostic = diagnostic
  }
}

public struct CoreMotionRawVector3: Equatable, Sendable {
  public let x: Double
  public let y: Double
  public let z: Double

  public init(x: Double, y: Double, z: Double) {
    self.x = x
    self.y = y
    self.z = z
  }
}

public struct CoreMotionRawQuaternion: Equatable, Sendable {
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
}

public struct CoreMotionRawRotationMatrix: Equatable, Sendable {
  public let m11: Double
  public let m12: Double
  public let m13: Double
  public let m21: Double
  public let m22: Double
  public let m23: Double
  public let m31: Double
  public let m32: Double
  public let m33: Double

  public init(
    m11: Double,
    m12: Double,
    m13: Double,
    m21: Double,
    m22: Double,
    m23: Double,
    m31: Double,
    m32: Double,
    m33: Double
  ) {
    self.m11 = m11
    self.m12 = m12
    self.m13 = m13
    self.m21 = m21
    self.m22 = m22
    self.m23 = m23
    self.m31 = m31
    self.m32 = m32
    self.m33 = m33
  }
}

public struct CoreMotionRawDeviceMotion: Equatable, Sendable {
  public let timestampSeconds: TimeInterval
  public let gravity: CoreMotionRawVector3
  public let userAcceleration: CoreMotionRawVector3
  public let rotationRate: CoreMotionRawVector3
  public let attitudeQuaternion: CoreMotionRawQuaternion
  public let attitudeRotationMatrix: CoreMotionRawRotationMatrix

  public init(
    timestampSeconds: TimeInterval,
    gravity: CoreMotionRawVector3,
    userAcceleration: CoreMotionRawVector3,
    rotationRate: CoreMotionRawVector3,
    attitudeQuaternion: CoreMotionRawQuaternion,
    attitudeRotationMatrix: CoreMotionRawRotationMatrix
  ) {
    self.timestampSeconds = timestampSeconds
    self.gravity = gravity
    self.userAcceleration = userAcceleration
    self.rotationRate = rotationRate
    self.attitudeQuaternion = attitudeQuaternion
    self.attitudeRotationMatrix = attitudeRotationMatrix
  }
}

public protocol CoreMotionDeviceMotionDriving: AnyObject {
  var deviceMotionAvailability: MotionCapabilityAvailability { get }
  var availableAttitudeReferenceFrames: Set<CoreMotionAttitudeReferenceFrame> { get }
  var attitudeNativeDirection: MotionNativeSignConvention { get }

  func startDeviceMotionUpdates(
    requestedIntervalSeconds: TimeInterval,
    referenceFrame: CoreMotionAttitudeReferenceFrame,
    queue: OperationQueue,
    handler:
      @escaping @Sendable (
        Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>
      ) -> Void
  ) throws

  func stopDeviceMotionUpdates()
}

public struct CoreMotionRecorderConfiguration: Equatable, Sendable {
  public let requestedUpdateIntervalNs: Int64
  public let attitudeReferenceFrame: CoreMotionAttitudeReferenceFrame
  public let initialInterfaceOrientation: CoreMotionInterfaceOrientation
  public let localReferenceInstanceId: String?
  public let gestureFrameFromDeviceRowMajor: [Double]?

  public init(
    requestedUpdateIntervalNs: Int64,
    attitudeReferenceFrame: CoreMotionAttitudeReferenceFrame = .xArbitraryZVertical,
    initialInterfaceOrientation: CoreMotionInterfaceOrientation,
    localReferenceInstanceId: String? = nil,
    gestureFrameFromDeviceRowMajor: [Double]? = nil
  ) {
    self.requestedUpdateIntervalNs = requestedUpdateIntervalNs
    self.attitudeReferenceFrame = attitudeReferenceFrame
    self.initialInterfaceOrientation = initialInterfaceOrientation
    self.localReferenceInstanceId = localReferenceInstanceId
    self.gestureFrameFromDeviceRowMajor = gestureFrameFromDeviceRowMajor
  }
}

public struct CoreMotionTraceContext: Equatable, Sendable {
  public let traceId: String
  public let libraryName: String
  public let libraryVersion: String
  public let privacy: MotionTracePrivacy
  public let detectors: [MotionDetectorDescriptor]?
  public let device: MotionDeviceMetadata?

  public init(
    traceId: String,
    libraryName: String,
    libraryVersion: String,
    privacy: MotionTracePrivacy,
    detectors: [MotionDetectorDescriptor]? = nil,
    device: MotionDeviceMetadata? = nil
  ) {
    self.traceId = traceId
    self.libraryName = libraryName
    self.libraryVersion = libraryVersion
    self.privacy = privacy
    self.detectors = detectors
    self.device = device
  }
}

public enum CoreMotionCapabilityID {
  public static let gravity = "coreMotion.deviceMotion.gravity"
  public static let userAcceleration = "coreMotion.deviceMotion.userAcceleration"
  public static let rotationRate = "coreMotion.deviceMotion.rotationRate"
  public static let attitude = "coreMotion.deviceMotion.attitude"
}
