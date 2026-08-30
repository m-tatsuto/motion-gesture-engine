package io.github.mtatsuto.motiongesture.core

enum class DetectorLifecycleState {
    IDLE,
    RUNNING,
    STOPPED,
}

enum class DetectorOperation {
    START,
    CONSUME,
    STOP,
}

/** Stable invalid-state failure for the detector lifecycle defined by core specification v1. */
class DetectorInvalidStateException(
    val operation: DetectorOperation,
    val state: DetectorLifecycleState,
) : IllegalStateException("invalidState: cannot ${operation.name.lowercase()} while detector is ${state.name.lowercase()}") {
    val code: String = "invalidState"
}
