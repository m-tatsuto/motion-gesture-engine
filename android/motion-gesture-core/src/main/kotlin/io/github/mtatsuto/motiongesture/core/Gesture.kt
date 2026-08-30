package io.github.mtatsuto.motiongesture.core

/** Generic gesture names shared by detectors, replay, and evaluation. */
enum class Gesture(val wireValue: String) {
    TILT_FORWARD("tiltForward"),
    TILT_BACKWARD("tiltBackward"),
}
