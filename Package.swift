// swift-tools-version: 6.0

import PackageDescription

// The repository root is the public Swift Package URL. The nested swift/
// manifest remains the standalone development entry point for this code.
let package = Package(
  name: "motion-gesture-engine",
  platforms: [
    .iOS(.v15),
    .macOS(.v13),
  ],
  products: [
    .library(name: "MotionGestureCore", targets: ["MotionGestureCore"]),
    .library(name: "MotionGestureRecorder", targets: ["MotionGestureRecorder"]),
    .library(name: "MotionGestureCoreMotion", targets: ["MotionGestureCoreMotion"]),
    .library(name: "MotionGestureReplay", targets: ["MotionGestureReplay"]),
  ],
  targets: [
    .target(name: "MotionGestureCore", path: "swift/Sources/MotionGestureCore"),
    .target(
      name: "MotionGestureRecorder",
      dependencies: ["MotionGestureCore"],
      path: "swift/Sources/MotionGestureRecorder"
    ),
    .target(
      name: "MotionGestureCoreMotion",
      dependencies: ["MotionGestureRecorder"],
      path: "swift/Sources/MotionGestureCoreMotion",
      linkerSettings: [.linkedFramework("CoreMotion")]
    ),
    .target(
      name: "MotionGestureReplay",
      dependencies: ["MotionGestureCore", "MotionGestureRecorder"],
      path: "swift/Sources/MotionGestureReplay"
    ),
    .testTarget(
      name: "MotionGestureCoreTests",
      dependencies: ["MotionGestureCore"],
      path: "swift/Tests/MotionGestureCoreTests"
    ),
    .testTarget(
      name: "MotionGestureRecorderTests",
      dependencies: ["MotionGestureRecorder"],
      path: "swift/Tests/MotionGestureRecorderTests"
    ),
    .testTarget(
      name: "MotionGestureCoreMotionTests",
      dependencies: ["MotionGestureCoreMotion"],
      path: "swift/Tests/MotionGestureCoreMotionTests"
    ),
    .testTarget(
      name: "MotionGestureReplayTests",
      dependencies: ["MotionGestureReplay"],
      path: "swift/Tests/MotionGestureReplayTests"
    ),
  ]
)
