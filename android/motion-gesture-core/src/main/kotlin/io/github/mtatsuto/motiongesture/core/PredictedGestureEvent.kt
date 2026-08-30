package io.github.mtatsuto.motiongesture.core

/** A detector event before trace-specific stream, UUID, and event-sequence fields are assigned. */
data class PredictedGestureEvent(
    val timestampNs: Long,
    val sourceSampleSequence: Long,
    val gesture: Gesture,
)
