/// Generic gesture names shared by detectors, replay, and evaluation.
///
/// Applications own any mapping from these values to product actions.
public enum Gesture: String, CaseIterable, Codable, Sendable {
  case tiltForward
  case tiltBackward
}
