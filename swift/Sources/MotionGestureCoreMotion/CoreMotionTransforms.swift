import Foundation
import MotionGestureRecorder

enum CoreMotionTransformError: Error, Equatable {
  case malformed
  case nonMonotonicTimestamp
}

enum CoreMotionTransforms {
  private static let nanosecondsPerSecond = 1_000_000_000.0
  private static let maximumNativeQuaternionNormError = 0.01
  private static let matrixTolerance = 0.000_001

  static func timestampNs(timestampSeconds: TimeInterval, originSeconds: TimeInterval) throws
    -> Int64
  {
    guard timestampSeconds.isFinite, originSeconds.isFinite, timestampSeconds >= originSeconds
    else {
      throw CoreMotionTransformError.nonMonotonicTimestamp
    }
    let nanoseconds =
      ((timestampSeconds - originSeconds) * nanosecondsPerSecond).rounded(.toNearestOrEven)
    guard nanoseconds.isFinite, nanoseconds >= 0,
      nanoseconds <= Double(MotionTraceV1.maximumSafeInteger)
    else {
      throw CoreMotionTransformError.malformed
    }
    return Int64(nanoseconds)
  }

  static func vector(_ value: CoreMotionRawVector3) throws -> MotionVector3 {
    guard value.x.isFinite, value.y.isFinite, value.z.isFinite else {
      throw CoreMotionTransformError.malformed
    }
    return MotionVector3(x: value.x, y: value.y, z: value.z)
  }

  static func attitude(
    quaternion rawQuaternion: CoreMotionRawQuaternion,
    rotationMatrix rawMatrix: CoreMotionRawRotationMatrix,
    nativeDirection: MotionNativeSignConvention,
    referenceFrame: CoreMotionAttitudeReferenceFrame
  ) throws -> MotionQuaternionObservation {
    let components = [rawQuaternion.x, rawQuaternion.y, rawQuaternion.z, rawQuaternion.w]
    let matrix = rawMatrix.rowMajor
    guard components.allSatisfy(\.isFinite), matrix.allSatisfy(\.isFinite) else {
      throw CoreMotionTransformError.malformed
    }

    let originalNorm = sqrt(components.reduce(0) { $0 + $1 * $1 })
    guard originalNorm.isFinite, originalNorm > 0,
      abs(originalNorm - 1) <= maximumNativeQuaternionNormError
    else {
      throw CoreMotionTransformError.malformed
    }
    let native = Quaternion(
      x: rawQuaternion.x / originalNorm,
      y: rawQuaternion.y / originalNorm,
      z: rawQuaternion.z / originalNorm,
      w: rawQuaternion.w / originalNorm
    )
    guard maximumElementDifference(native.rotationMatrix, matrix) <= matrixTolerance else {
      throw CoreMotionTransformError.malformed
    }

    let directionNormalized: Quaternion
    switch nativeDirection {
    case .referenceFromDevice:
      directionNormalized = native
    case .deviceFromReference:
      directionNormalized = native.conjugate
    default:
      throw CoreMotionTransformError.malformed
    }

    let canonical: Quaternion
    switch referenceFrame {
    case .xArbitraryZVertical, .xArbitraryCorrectedZVertical:
      canonical = directionNormalized
    case .xMagneticNorthZVertical, .xTrueNorthZVertical:
      canonical = Quaternion.nativeNorthToEastNorthUp * directionNormalized
    }

    return MotionQuaternionObservation(
      capabilityId: CoreMotionCapabilityID.attitude,
      value: MotionQuaternion(x: canonical.x, y: canonical.y, z: canonical.z, w: canonical.w),
      normalization: abs(originalNorm - 1) > 0.000_000_000_001
        ? QuaternionNormalization(originalNorm: originalNorm) : nil
    )
  }

  static func gestureFrameFromDevice(
    for rotation: DisplayRotationClockwise
  ) -> [Double] {
    switch rotation {
    case .degrees0:
      return [1, 0, 0, 0, 1, 0, 0, 0, 1]
    case .degrees90:
      return [0, -1, 0, 1, 0, 0, 0, 0, 1]
    case .degrees180:
      return [-1, 0, 0, 0, -1, 0, 0, 0, 1]
    case .degrees270:
      return [0, 1, 0, -1, 0, 0, 0, 0, 1]
    }
  }

  private static func maximumElementDifference(_ lhs: [Double], _ rhs: [Double]) -> Double {
    zip(lhs, rhs).map { abs($0 - $1) }.max() ?? .infinity
  }
}

private struct Quaternion {
  let x: Double
  let y: Double
  let z: Double
  let w: Double

  static let nativeNorthToEastNorthUp = Quaternion(
    x: 0,
    y: 0,
    z: sqrt(0.5),
    w: sqrt(0.5)
  )

  var conjugate: Quaternion { Quaternion(x: -x, y: -y, z: -z, w: w) }

  static func * (lhs: Quaternion, rhs: Quaternion) -> Quaternion {
    Quaternion(
      x: lhs.w * rhs.x + lhs.x * rhs.w + lhs.y * rhs.z - lhs.z * rhs.y,
      y: lhs.w * rhs.y - lhs.x * rhs.z + lhs.y * rhs.w + lhs.z * rhs.x,
      z: lhs.w * rhs.z + lhs.x * rhs.y - lhs.y * rhs.x + lhs.z * rhs.w,
      w: lhs.w * rhs.w - lhs.x * rhs.x - lhs.y * rhs.y - lhs.z * rhs.z
    )
  }

  var rotationMatrix: [Double] {
    [
      1 - 2 * (y * y + z * z),
      2 * (x * y - z * w),
      2 * (x * z + y * w),
      2 * (x * y + z * w),
      1 - 2 * (x * x + z * z),
      2 * (y * z - x * w),
      2 * (x * z - y * w),
      2 * (y * z + x * w),
      1 - 2 * (x * x + y * y),
    ]
  }
}

extension CoreMotionRawRotationMatrix {
  fileprivate var rowMajor: [Double] {
    [m11, m12, m13, m21, m22, m23, m31, m32, m33]
  }
}
