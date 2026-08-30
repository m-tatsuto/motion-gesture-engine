package io.github.mtatsuto.motiongesture.recorder

import java.nio.file.Path

enum class MotionTraceRecorderState {
    IDLE,
    RECORDING,
    FINALIZING,
    FINISHED,
    CANCELLED,
    FAILED,
}

enum class MotionTraceRecorderStage {
    START,
    APPEND,
    FINALIZE,
    COMMIT,
}

enum class MotionTraceRecorderErrorCode(val wireValue: String) {
    INVALID_CONFIGURATION("invalidConfiguration"),
    INVALID_STATE("invalidState"),
    INVALID_SAMPLE("invalidSample"),
    IO_FAILURE("ioFailure"),
}

class MotionTraceRecorderException(
    val code: MotionTraceRecorderErrorCode,
    val stage: MotionTraceRecorderStage,
    val diagnostic: String,
    val partialPath: Path? = null,
    cause: Throwable? = null,
) : Exception("${code.wireValue} during ${stage.name.lowercase()}: $diagnostic", cause)
