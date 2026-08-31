@preconcurrency import CoreMotion
import Foundation
import MotionGestureRecorder

#if os(iOS)
  public final class CMMotionManagerDeviceMotionDriver: CoreMotionDeviceMotionDriving,
    @unchecked Sendable
  {
    private let manager: CMMotionManager

    public init(manager: CMMotionManager = CMMotionManager()) {
      self.manager = manager
    }

    public var deviceMotionAvailability: MotionCapabilityAvailability {
      manager.isDeviceMotionAvailable ? .available : .unavailable
    }

    public var availableAttitudeReferenceFrames: Set<CoreMotionAttitudeReferenceFrame> {
      #if os(iOS)
        let available = CMMotionManager.availableAttitudeReferenceFrames()
        return Set(
          CoreMotionAttitudeReferenceFrame.allCases.filter { available.contains($0.native) })
      #else
        return []
      #endif
    }

    public var attitudeNativeDirection: MotionNativeSignConvention { .referenceFromDevice }

    public func startDeviceMotionUpdates(
      requestedIntervalSeconds: TimeInterval,
      referenceFrame: CoreMotionAttitudeReferenceFrame,
      queue: OperationQueue,
      handler:
        @escaping @Sendable (
          Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>
        ) -> Void
    ) throws {
      guard manager.isDeviceMotionAvailable else {
        throw CoreMotionDriverFailure(
          domain: "apple.coreMotion",
          code: 1,
          diagnostic: "device motion is unavailable"
        )
      }
      guard !manager.isDeviceMotionActive else {
        throw CoreMotionDriverFailure(
          domain: "apple.coreMotion",
          code: 2,
          diagnostic: "device motion updates are already active"
        )
      }

      manager.deviceMotionUpdateInterval = requestedIntervalSeconds
      #if os(iOS)
        manager.showsDeviceMovementDisplay = false
        manager.startDeviceMotionUpdates(using: referenceFrame.native, to: queue) { motion, error in
          Self.deliver(motion: motion, error: error, to: handler)
        }
      #else
        manager.startDeviceMotionUpdates(to: queue) { motion, error in
          Self.deliver(motion: motion, error: error, to: handler)
        }
      #endif
    }

    public func stopDeviceMotionUpdates() {
      manager.stopDeviceMotionUpdates()
    }

    private static func deliver(
      motion: CMDeviceMotion?,
      error: Error?,
      to handler:
        @escaping @Sendable (
          Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>
        ) -> Void
    ) {
      if let error = error as NSError? {
        handler(
          .failure(
            CoreMotionDriverFailure(
              domain: error.domain,
              code: error.code,
              diagnostic: error.localizedDescription
            )))
        return
      }
      guard let motion else {
        handler(
          .failure(
            CoreMotionDriverFailure(
              domain: "apple.coreMotion",
              code: 3,
              diagnostic: "Core Motion delivered neither data nor an error"
            )))
        return
      }

      let gravity = motion.gravity
      let userAcceleration = motion.userAcceleration
      let rotationRate = motion.rotationRate
      let quaternion = motion.attitude.quaternion
      let matrix = motion.attitude.rotationMatrix
      handler(
        .success(
          CoreMotionRawDeviceMotion(
            timestampSeconds: motion.timestamp,
            gravity: CoreMotionRawVector3(x: gravity.x, y: gravity.y, z: gravity.z),
            userAcceleration: CoreMotionRawVector3(
              x: userAcceleration.x,
              y: userAcceleration.y,
              z: userAcceleration.z
            ),
            rotationRate: CoreMotionRawVector3(
              x: rotationRate.x,
              y: rotationRate.y,
              z: rotationRate.z
            ),
            attitudeQuaternion: CoreMotionRawQuaternion(
              x: quaternion.x,
              y: quaternion.y,
              z: quaternion.z,
              w: quaternion.w
            ),
            attitudeRotationMatrix: CoreMotionRawRotationMatrix(
              m11: matrix.m11,
              m12: matrix.m12,
              m13: matrix.m13,
              m21: matrix.m21,
              m22: matrix.m22,
              m23: matrix.m23,
              m31: matrix.m31,
              m32: matrix.m32,
              m33: matrix.m33
            )
          )))
    }
  }

  extension CoreMotionAttitudeReferenceFrame {
    fileprivate var native: CMAttitudeReferenceFrame {
      switch self {
      case .xArbitraryZVertical:
        return .xArbitraryZVertical
      case .xArbitraryCorrectedZVertical:
        return .xArbitraryCorrectedZVertical
      case .xMagneticNorthZVertical:
        return .xMagneticNorthZVertical
      case .xTrueNorthZVertical:
        return .xTrueNorthZVertical
      }
    }
  }
#else
  public final class CMMotionManagerDeviceMotionDriver: CoreMotionDeviceMotionDriving,
    @unchecked Sendable
  {
    public init() {}

    public var deviceMotionAvailability: MotionCapabilityAvailability { .unavailable }
    public var availableAttitudeReferenceFrames: Set<CoreMotionAttitudeReferenceFrame> { [] }
    public var attitudeNativeDirection: MotionNativeSignConvention { .referenceFromDevice }

    public func startDeviceMotionUpdates(
      requestedIntervalSeconds: TimeInterval,
      referenceFrame: CoreMotionAttitudeReferenceFrame,
      queue: OperationQueue,
      handler:
        @escaping @Sendable (
          Result<CoreMotionRawDeviceMotion, CoreMotionDriverFailure>
        ) -> Void
    ) throws {
      throw CoreMotionDriverFailure(
        domain: "apple.coreMotion",
        code: 4,
        diagnostic: "CMMotionManager device motion is available only on iOS"
      )
    }

    public func stopDeviceMotionUpdates() {}
  }
#endif
