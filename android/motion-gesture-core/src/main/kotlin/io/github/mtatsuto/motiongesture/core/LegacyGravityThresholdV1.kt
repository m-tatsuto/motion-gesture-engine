package io.github.mtatsuto.motiongesture.core

import kotlin.math.abs

/**
 * Immutable identity and thresholds for the legacy comparison baseline.
 *
 * Changing any value requires a new detector identifier; these values are not tunable.
 */
object LegacyGravityThresholdV1Configuration {
    const val DETECTOR_ID: String = "LegacyGravityThresholdV1"
    const val DETECTOR_VERSION: String = "1.0.0"
    const val CONFIGURATION_IDENTITY: String = "legacy.default.v1"
    const val TRIGGER_MAGNITUDE: Double = 0.65
    const val REARM_MAGNITUDE: Double = 0.35
}

/**
 * The unfiltered, single-axis gravity detector used as the v1 comparison baseline.
 *
 * This class deliberately performs no smoothing, calibration, confidence scoring,
 * debounce timing, or cooldown. It is not a recommended future production detector.
 */
class LegacyGravityThresholdV1 {
    var lifecycleState: DetectorLifecycleState = DetectorLifecycleState.IDLE
        private set
    var isArmed: Boolean = true
        private set

    /** Starts a clean detector session. */
    fun start() {
        if (lifecycleState == DetectorLifecycleState.RUNNING) {
            throw DetectorInvalidStateException(DetectorOperation.START, lifecycleState)
        }

        isArmed = true
        lifecycleState = DetectorLifecycleState.RUNNING
    }

    /** Stops the current detector session and restores the armed state. */
    fun stop() {
        if (lifecycleState != DetectorLifecycleState.RUNNING) {
            throw DetectorInvalidStateException(DetectorOperation.STOP, lifecycleState)
        }

        isArmed = true
        lifecycleState = DetectorLifecycleState.STOPPED
    }

    /** Returns the detector to its initial clean state for deterministic replay. */
    fun reset() {
        isArmed = true
        lifecycleState = DetectorLifecycleState.IDLE
    }

    /** Consumes one already-normalized gesture-frame gravity sample. */
    fun consume(sample: GestureFrameGravitySample): PredictedGestureEvent? {
        if (lifecycleState != DetectorLifecycleState.RUNNING) {
            throw DetectorInvalidStateException(DetectorOperation.CONSUME, lifecycleState)
        }

        if (isArmed) {
            if (sample.gravityZG > LegacyGravityThresholdV1Configuration.TRIGGER_MAGNITUDE) {
                isArmed = false
                return event(Gesture.TILT_FORWARD, sample)
            }

            if (sample.gravityZG < -LegacyGravityThresholdV1Configuration.TRIGGER_MAGNITUDE) {
                isArmed = false
                return event(Gesture.TILT_BACKWARD, sample)
            }
        } else if (abs(sample.gravityZG) < LegacyGravityThresholdV1Configuration.REARM_MAGNITUDE) {
            isArmed = true
        }

        return null
    }

    private fun event(gesture: Gesture, sample: GestureFrameGravitySample) = PredictedGestureEvent(
        timestampNs = sample.timestampNs,
        sourceSampleSequence = sample.sequence,
        gesture = gesture,
    )
}
