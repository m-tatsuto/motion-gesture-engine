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
    )
  ],
  targets: [
    .target(name: "MotionGestureCore"),
    .testTarget(
      name: "MotionGestureCoreTests",
      dependencies: ["MotionGestureCore"]
    ),
  ]
)
