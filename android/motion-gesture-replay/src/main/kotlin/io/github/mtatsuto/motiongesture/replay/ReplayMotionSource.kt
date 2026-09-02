package io.github.mtatsuto.motiongesture.replay

import io.github.mtatsuto.motiongesture.core.PredictedGestureEvent
import io.github.mtatsuto.motiongesture.recorder.MotionPredictedEventRecord
import io.github.mtatsuto.motiongesture.recorder.MotionTraceV1
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ReplayMotionSource(
    private val detector: MotionReplayDetector,
    private val limits: ReplayLimits = ReplayLimits(),
) {
    var lifecycleState: ReplayLifecycleState = ReplayLifecycleState.IDLE
        private set
    var currentVirtualTimestampNs: Long? = null
        private set
    var trace: ValidatedReplayTrace? = null
        private set

    private var sampleIndex = 0
    private var detectorStarted = false
    private val deliveredSampleSequences = mutableSetOf<Long>()
    private val predictions = mutableListOf<MotionPredictedEventRecord>()

    fun load(data: ByteArray): ValidatedReplayTrace {
        requireState(setOf(ReplayLifecycleState.IDLE), ReplayStage.LOAD)
        return try {
            MotionTraceReplayLoader.load(data, limits).also {
                trace = it
                lifecycleState = ReplayLifecycleState.READY
            }
        } catch (error: ReplayException) {
            lifecycleState = ReplayLifecycleState.FAILED
            throw error
        }
    }

    fun load(path: Path): ValidatedReplayTrace {
        requireState(setOf(ReplayLifecycleState.IDLE), ReplayStage.LOAD)
        val fileSize = try {
            Files.size(path)
        } catch (error: IOException) {
            lifecycleState = ReplayLifecycleState.FAILED
            throw ReplayException(
                ReplayErrorCode.IO_FAILURE,
                ReplayStage.LOAD,
                "could not inspect replay trace",
                cause = error,
            )
        }
        if (fileSize > limits.maximumBytes) {
            lifecycleState = ReplayLifecycleState.FAILED
            throw ReplayException(
                ReplayErrorCode.LIMIT_EXCEEDED,
                ReplayStage.LOAD,
                "trace exceeds ${limits.maximumBytes} bytes",
            )
        }
        val data = try {
            Files.readAllBytes(path)
        } catch (error: IOException) {
            lifecycleState = ReplayLifecycleState.FAILED
            throw ReplayException(
                ReplayErrorCode.IO_FAILURE,
                ReplayStage.LOAD,
                "could not read replay trace",
                cause = error,
            )
        }
        return load(data)
    }

    fun step(): ReplayStep? {
        requireState(
            setOf(ReplayLifecycleState.READY, ReplayLifecycleState.REPLAYING),
            ReplayStage.STEP,
        )
        val loadedTrace = requireNotNull(trace)
        ensureDetectorStarted(loadedTrace)

        if (sampleIndex >= loadedTrace.samples.size) {
            finishDetector(ReplayStage.STEP)
            lifecycleState = ReplayLifecycleState.FINISHED
            return null
        }

        val sample = loadedTrace.samples[sampleIndex]
        currentVirtualTimestampNs = sample.timestampNs
        deliveredSampleSequences += sample.sequence
        val emitted = try {
            detector.consume(sample)
        } catch (error: Exception) {
            failDetector(ReplayStage.STEP, "detector consume failed", error)
        }
        val records = buildList {
            emitted.forEach { event ->
                val record = makeRecord(loadedTrace, event, sample.timestampNs)
                predictions += record
                add(record)
            }
        }
        sampleIndex += 1

        val finished = sampleIndex == loadedTrace.samples.size
        if (finished) {
            finishDetector(ReplayStage.STEP)
            lifecycleState = ReplayLifecycleState.FINISHED
        }
        return ReplayStep(
            virtualTimestampNs = sample.timestampNs,
            sample = sample,
            emittedEvents = records,
            isFinished = finished,
        )
    }

    fun run(): ReplayRunResult {
        requireState(
            setOf(ReplayLifecycleState.READY, ReplayLifecycleState.REPLAYING),
            ReplayStage.RUN,
        )
        while (lifecycleState != ReplayLifecycleState.FINISHED) {
            step()
        }
        return result()
    }

    fun cancel() {
        requireState(
            setOf(ReplayLifecycleState.READY, ReplayLifecycleState.REPLAYING),
            ReplayStage.CANCEL,
        )
        if (detectorStarted) finishDetector(ReplayStage.CANCEL)
        lifecycleState = ReplayLifecycleState.CANCELLED
    }

    fun reset() {
        requireState(
            setOf(ReplayLifecycleState.FINISHED, ReplayLifecycleState.CANCELLED),
            ReplayStage.RESET,
        )
        try {
            detector.reset()
        } catch (error: Exception) {
            failDetector(ReplayStage.RESET, "detector reset failed", error)
        }
        detectorStarted = false
        sampleIndex = 0
        currentVirtualTimestampNs = null
        deliveredSampleSequences.clear()
        predictions.clear()
        lifecycleState = ReplayLifecycleState.READY
    }

    fun result(): ReplayRunResult {
        requireState(
            setOf(ReplayLifecycleState.FINISHED, ReplayLifecycleState.CANCELLED),
            ReplayStage.RUN,
        )
        return ReplayRunResult(
            detector = detector.descriptor,
            events = predictions.toList(),
            finalVirtualTimestampNs = currentVirtualTimestampNs,
        )
    }

    private fun ensureDetectorStarted(loadedTrace: ValidatedReplayTrace) {
        if (detectorStarted) return
        val descriptor = detector.descriptor
        val identifier = Regex("^[A-Za-z][A-Za-z0-9._-]{0,127}$")
        val semanticVersion = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$",
        )
        if (!identifier.matches(descriptor.detectorStreamId) ||
            !identifier.matches(descriptor.detectorId) ||
            !identifier.matches(descriptor.configurationIdentity) ||
            !semanticVersion.matches(descriptor.detectorVersion)
        ) {
            failDetector(ReplayStage.STEP, "detector descriptor is not wire-compatible")
        }
        loadedTrace.header.detectors.orEmpty()
            .firstOrNull { it.detectorStreamId == descriptor.detectorStreamId }
            ?.let { declared ->
                if (declared != descriptor) {
                    failDetector(
                        ReplayStage.STEP,
                        "detector descriptor conflicts with the trace declaration",
                    )
                }
            }
        try {
            detector.reset()
            detector.start(loadedTrace.header.session)
            detectorStarted = true
            lifecycleState = ReplayLifecycleState.REPLAYING
        } catch (error: Exception) {
            failDetector(ReplayStage.STEP, "detector start failed", error)
        }
    }

    private fun finishDetector(stage: ReplayStage) {
        if (!detectorStarted) return
        try {
            detector.stop()
            detectorStarted = false
        } catch (error: Exception) {
            failDetector(stage, "detector stop failed", error)
        }
    }

    private fun makeRecord(
        loadedTrace: ValidatedReplayTrace,
        event: PredictedGestureEvent,
        currentTimestampNs: Long,
    ): MotionPredictedEventRecord {
        if (event.timestampNs !in 0..MotionTraceV1.MAXIMUM_SAFE_INTEGER ||
            event.timestampNs > currentTimestampNs ||
            event.sourceSampleSequence !in deliveredSampleSequences
        ) {
            failDetector(
                ReplayStage.STEP,
                "detector emitted an invalid timestamp or source sample reference",
            )
        }
        predictions.lastOrNull()?.let { previous ->
            if (event.timestampNs < previous.timestampNs) {
                failDetector(ReplayStage.STEP, "detector predictions are not monotonic")
            }
        }
        val eventSequence = predictions.size.toLong()
        if (eventSequence > MotionTraceV1.MAXIMUM_SAFE_INTEGER) {
            failDetector(ReplayStage.STEP, "detector emitted too many predictions")
        }
        return MotionPredictedEventRecord(
            eventId = DeterministicReplayEventId.make(
                traceId = loadedTrace.header.traceId,
                detectorStreamId = detector.descriptor.detectorStreamId,
                eventSequence = eventSequence,
            ),
            detectorStreamId = detector.descriptor.detectorStreamId,
            eventSequence = eventSequence,
            timestampNs = event.timestampNs,
            gesture = event.gesture.toTraceGesture(),
            sourceSampleSequence = event.sourceSampleSequence,
        )
    }

    private fun requireState(allowed: Set<ReplayLifecycleState>, stage: ReplayStage) {
        if (lifecycleState !in allowed) {
            throw ReplayException(
                ReplayErrorCode.INVALID_STATE,
                stage,
                "operation is invalid while state is ${lifecycleState.name.lowercase()}",
            )
        }
    }

    private fun failDetector(
        stage: ReplayStage,
        diagnostic: String,
        cause: Throwable? = null,
    ): Nothing {
        lifecycleState = ReplayLifecycleState.FAILED
        throw ReplayException(
            ReplayErrorCode.DETECTOR_FAILURE,
            stage,
            diagnostic,
            cause = cause,
        )
    }
}

private object DeterministicReplayEventId {
    private const val OFFSET_BASIS: ULong = 0xcbf29ce484222325UL
    private const val PRIME: ULong = 0x100000001b3UL

    fun make(traceId: String, detectorStreamId: String, eventSequence: Long): String {
        val seed = "$traceId|$detectorStreamId|$eventSequence"
        val first = fnv1a("mge.replay.event.v1.a|$seed")
        val second = fnv1a("mge.replay.event.v1.b|$seed")
        val bytes = ByteArray(16)
        writeBigEndian(first, bytes, 0)
        writeBigEndian(second, bytes, 8)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-" +
            "${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun fnv1a(value: String): ULong {
        var hash = OFFSET_BASIS
        value.encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * PRIME
        }
        return hash
    }

    private fun writeBigEndian(value: ULong, destination: ByteArray, offset: Int) {
        for (index in 0 until 8) {
            destination[offset + index] = (value shr ((7 - index) * 8)).toByte()
        }
    }
}
