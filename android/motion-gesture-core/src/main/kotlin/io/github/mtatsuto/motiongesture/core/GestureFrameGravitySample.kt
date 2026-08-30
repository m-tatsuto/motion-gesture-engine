package io.github.mtatsuto.motiongesture.core

/**
 * One canonical gravity observation expressed in the session's frozen gesture frame.
 *
 * Platform sign and unit conversion must happen before this value reaches the core.
 * Inputs are required to satisfy the core specification: non-negative monotonic time,
 * non-negative sequence, and a finite gravity value in standard-gravity units.
 */
data class GestureFrameGravitySample(
    val timestampNs: Long,
    val sequence: Long,
    val gravityZG: Double,
)
