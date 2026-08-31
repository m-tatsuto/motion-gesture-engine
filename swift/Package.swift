// swift-tools-version: 6.0

import PackageDescription

let package = Package(
  name: "motion-gesture-engine",
  platforms: [
    .iOS(.v15),
    .macOS(.v13),
  ],
  products: [
    .library(
      name: "MotionGestureCore",
      targets: ["MotionGestureCore"]
    ),
    .library(
      name: "MotionGestureRecorder",
      targets: ["MotionGestureRecorder"]
    ),
    .library(
      name: "MotionGestureCoreMotion",
      targets: ["MotionGestureCoreMotion"]
    ),
  ],
  targets: [
    .target(name: "MotionGestureCore"),
    .target(
      name: "MotionGestureRecorder",
      dependencies: ["MotionGestureCore"]
    ),
    .target(
      name: "MotionGestureCoreMotion",
      dependencies: ["MotionGestureRecorder"],
      linkerSettings: [.linkedFramework("CoreMotion")]
    ),
    .testTarget(
      name: "MotionGestureCoreTests",
      dependencies: ["MotionGestureCore"]
    ),
    .testTarget(
      name: "MotionGestureRecorderTests",
      dependencies: ["MotionGestureRecorder"]
    ),
    .testTarget(
      name: "MotionGestureCoreMotionTests",
      dependencies: ["MotionGestureCoreMotion"]
    ),
  ]
)
