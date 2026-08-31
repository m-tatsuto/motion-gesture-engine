package io.github.mtatsuto.motiongesture.replay

import io.github.mtatsuto.motiongesture.core.Gesture
import io.github.mtatsuto.motiongesture.core.GestureFrameGravitySample
import io.github.mtatsuto.motiongesture.core.LegacyGravityThresholdV1
import io.github.mtatsuto.motiongesture.core.LegacyGravityThresholdV1Configuration
import io.github.mtatsuto.motiongesture.core.PredictedGestureEvent
import io.github.mtatsuto.motiongesture.recorder.MotionDetectorDescriptor
import io.github.mtatsuto.motiongesture.recorder.MotionPredictedEventRecord
import io.github.mtatsuto.motiongesture.recorder.MotionSample
import io.github.mtatsuto.motiongesture.recorder.MotionTraceFooter
import io.github.mtatsuto.motiongesture.recorder.MotionTraceGesture
import io.github.mtatsuto.motiongesture.recorder.MotionTraceHeader
import io.github.mtatsuto.motiongesture.recorder.MotionTraceSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReplayLifecycleState {
    IDLE,
    READY,
    REPLAYING,
    FINISHED,
    CANCELLED,
    FAILED,
}

enum class ReplayStage {
    LOAD,
    STEP,
    RUN,
    RESET,
    CANCEL,
}

enum class ReplayErrorCode(val wireValue: String) {
    INVALID_STATE("invalidState"),
    INVALID_CONTAINER("invalidContainer"),
    MALFORMED_RECORD("malformedRecord"),
    UNSUPPORTED_SCHEMA_VERSION("unsupportedSchemaVersion"),
    UNSUPPORTED_SPEC_VERSION("unsupportedSpecVersion"),
    UNSUPPORTED_COMPRESSION("unsupportedCompression"),
    INCOMPLETE_TRACE("incompleteTrace"),
    NON_MONOTONIC_SAMPLE("nonMonotonicSample"),
    FOOTER_MISMATCH("footerMismatch"),
    LIMIT_EXCEEDED("limitExceeded"),
    IO_FAILURE("ioFailure"),
    DETECTOR_FAILURE("detectorFailure"),
}

class ReplayException(
    val code: ReplayErrorCode,
    val stage: ReplayStage,
    val diagnostic: String,
    val line: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException("${code.wireValue} at ${stage.name.lowercase()}: $diagnostic", cause)

data class ReplayLimits(
    val maximumBytes: Long = 64L * 1024 * 1024,
    val maximumLineBytes: Int = 1024 * 1024,
    val maximumBodyRecords: Int = 1_000_000,
) {
    init {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        require(maximumLineBytes > 0) { "maximumLineBytes must be positive" }
        require(maximumBodyRecords > 0) { "maximumBodyRecords must be positive" }
        require(maximumBodyRecords <= Int.MAX_VALUE - 2) {
            "maximumBodyRecords is too large"
        }
    }
}

data class ValidatedReplayTrace(
    val header: MotionTraceHeader,
    val samples: List<MotionSample>,
    val footer: MotionTraceFooter,
)

interface MotionReplayDetector {
    val descriptor: MotionDetectorDescriptor

    fun start(session: MotionTraceSession)

    fun consume(sample: MotionSample): List<PredictedGestureEvent>

    fun stop()

    fun reset()
}

class LegacyGravityThresholdV1ReplayDetector(
    detectorStreamId: String = "legacy.replay.v1",
) : MotionReplayDetector {
    override val descriptor = MotionDetectorDescriptor(
        detectorStreamId = detectorStreamId,
        detectorId = LegacyGravityThresholdV1Configuration.DETECTOR_ID,
        detectorVersion = LegacyGravityThresholdV1Configuration.DETECTOR_VERSION,
        configurationIdentity = LegacyGravityThresholdV1Configuration.CONFIGURATION_IDENTITY,
    )

    private val detector = LegacyGravityThresholdV1()
    private var gestureFrameFromDevice = emptyList<Double>()

    override fun start(session: MotionTraceSession) {
        gestureFrameFromDevice = session.gestureFrameFromDeviceRowMajor
        detector.start()
    }

    override fun consume(sample: MotionSample): List<PredictedGestureEvent> {
        val gravity = sample.signals.gravity ?: return emptyList()
        val value = gravity.value
        val gravityZG =
            gestureFrameFromDevice[6] * value.x +
                gestureFrameFromDevice[7] * value.y +
                gestureFrameFromDevice[8] * value.z
        val event = detector.consume(
            GestureFrameGravitySample(
                timestampNs = sample.timestampNs,
                sequence = sample.sequence,
                gravityZG = gravityZG,
            ),
        )
        return listOfNotNull(event)
    }

    override fun stop() {
        detector.stop()
    }

    override fun reset() {
        detector.reset()
        gestureFrameFromDevice = emptyList()
    }
}

data class ReplayStep(
    val virtualTimestampNs: Long,
    val sample: MotionSample,
    val emittedEvents: List<MotionPredictedEventRecord>,
    val isFinished: Boolean,
)

data class ReplayRunResult(
    val detector: MotionDetectorDescriptor,
    val events: List<MotionPredictedEventRecord>,
    val finalVirtualTimestampNs: Long?,
) {
    fun encodedPredictionsJsonLines(): ByteArray = ReplayPredictionEncoding.encode(events)
}

internal object ReplayPredictionEncoding {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(events: List<MotionPredictedEventRecord>): ByteArray = buildString {
        events.forEach { event ->
            append(json.encodeToString(event))
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)
}

internal fun Gesture.toTraceGesture(): MotionTraceGesture = when (this) {
    Gesture.TILT_FORWARD -> MotionTraceGesture.TILT_FORWARD
    Gesture.TILT_BACKWARD -> MotionTraceGesture.TILT_BACKWARD
}
