package io.github.mtatsuto.motiongesture.recorder

import java.nio.file.Path
import kotlinx.serialization.KSerializer

data class MotionTraceRecordingResult(
    val footer: MotionTraceFooter,
    val bytesWritten: Long,
    val destinationPath: Path?,
)

data class MotionTraceAppendOutcome(
    val accepted: Boolean,
    val droppedReason: DroppedSampleReason? = null,
    val recordingResult: MotionTraceRecordingResult? = null,
)

sealed interface MotionSampleSourceEvent {
    data class Sample(val sample: MotionSample) : MotionSampleSourceEvent
    data class Dropped(val reason: DroppedSampleReason) : MotionSampleSourceEvent
    data class Finished(val durationNs: Long) : MotionSampleSourceEvent
    data class Failed(val code: String, val durationNs: Long) : MotionSampleSourceEvent
}

fun interface MotionSampleSource {
    fun nextEvent(): MotionSampleSourceEvent
}

/** A bounded, transport-free JSONL recorder for one Motion Trace v1 session. */
class MotionTraceRecorder(
    private val metadata: MotionTraceMetadata,
    private val limits: MotionTraceRecorderLimits,
    private val output: MotionTraceOutput,
) {
    constructor(
        metadata: MotionTraceMetadata,
        limits: MotionTraceRecorderLimits,
        destinationPath: Path,
    ) : this(metadata, limits, AtomicFileMotionTraceOutput(destinationPath))

    private val encoder = MotionTraceRecordEncoder()

    @Volatile
    var state: MotionTraceRecorderState = MotionTraceRecorderState.IDLE
        private set

    @Volatile
    var result: MotionTraceRecordingResult? = null
        private set

    private var terminalError: MotionTraceRecorderException? = null
    private var bytesWritten: Long = 0
    private var footerReserveBytes: Long = 0
    private var counts = MotionTraceRecordCounts()
    private val droppedCounts = mutableMapOf<DroppedSampleReason, Long>()
    private val timing = mutableMapOf<String, TimingAccumulator>()
    private var capabilities = emptyMap<String, MotionCapability>()
    private val capabilityAvailability = mutableMapOf<String, MotionCapabilityAvailability>()
    private var lastSampleTimestampNs: Long? = null
    private var lastSampleSequence: Long? = null
    private var lastDisplayRotationChangeSequence: Long? = null
    private var lastCapabilityChangeSequence: Long? = null
    private var maximumRecordTimestampNs: Long = 0
    private val annotationIds = mutableSetOf<String>()

    @Synchronized
    fun start() {
        if (state != MotionTraceRecorderState.IDLE) throw invalidState("start", MotionTraceRecorderStage.START)
        try {
            MotionTraceValidation.validate(limits)
            MotionTraceValidation.validate(metadata)
            capabilities = metadata.capabilities.associateBy(MotionCapability::capabilityId)
            metadata.capabilities.forEach {
                timing[it.capabilityId] = TimingAccumulator(it.capabilityId)
                capabilityAvailability[it.capabilityId] = it.availability
            }

            val header = MotionTraceHeader(metadata, limits)
            val headerLine = encoder.line(MotionTraceHeader.serializer(), header)
            footerReserveBytes = encoder.line(MotionTraceFooter.serializer(), maximumSizeFooter()).size.toLong()
            if (headerLine.size.toLong() + footerReserveBytes > limits.maximumBytes) {
                throw MotionTraceRecorderException(
                    MotionTraceRecorderErrorCode.INVALID_CONFIGURATION,
                    MotionTraceRecorderStage.START,
                    "maximumBytes cannot contain the header and bounded footer diagnostics",
                )
            }
            output.start()
            writeRequired(headerLine, MotionTraceRecorderStage.START)
            bytesWritten = headerLine.size.toLong()
            state = MotionTraceRecorderState.RECORDING
        } catch (error: MotionTraceRecorderException) {
            failWithoutFooter(error)
            throw error
        } catch (error: Throwable) {
            val recorderError = ioError(MotionTraceRecorderStage.START, error)
            failWithoutFooter(recorderError)
            throw recorderError
        }
    }

    @Synchronized
    fun append(sample: MotionSample): MotionTraceAppendOutcome {
        requireRecording("append sample")
        when (
            MotionTraceValidation.validate(
                sample,
                capabilities,
                capabilityAvailability,
                metadata.session.attitudeReference != null,
            )
        ) {
            is SampleValidationResult.Malformed -> {
                incrementDrop(DroppedSampleReason.MALFORMED)
                return MotionTraceAppendOutcome(false, DroppedSampleReason.MALFORMED)
            }
            is SampleValidationResult.Unsupported -> {
                incrementDrop(DroppedSampleReason.UNSUPPORTED)
                return MotionTraceAppendOutcome(false, DroppedSampleReason.UNSUPPORTED)
            }
            SampleValidationResult.Valid -> Unit
        }

        val previousTimestamp = lastSampleTimestampNs
        val previousSequence = lastSampleSequence
        if (previousTimestamp != null && previousSequence != null &&
            (sample.timestampNs < previousTimestamp || sample.sequence <= previousSequence)
        ) {
            incrementDrop(DroppedSampleReason.NON_MONOTONIC_TIMESTAMP)
            return MotionTraceAppendOutcome(false, DroppedSampleReason.NON_MONOTONIC_TIMESTAMP)
        }
        if (sample.timestampNs > limits.maximumDurationNs) {
            incrementDrop(DroppedSampleReason.LIMIT_REACHED)
            val terminal = finalize(
                MotionTraceFinalizationStatus.BOUNDED,
                MotionTraceTerminationReason.DURATION_LIMIT,
                durationNs = limits.maximumDurationNs,
            )
            return MotionTraceAppendOutcome(false, DroppedSampleReason.LIMIT_REACHED, terminal)
        }

        val line = try {
            encoder.line(MotionSample.serializer(), sample)
        } catch (_: Throwable) {
            incrementDrop(DroppedSampleReason.MALFORMED)
            return MotionTraceAppendOutcome(false, DroppedSampleReason.MALFORMED)
        }
        if (!canWriteBody(line)) {
            incrementDrop(DroppedSampleReason.LIMIT_REACHED)
            val terminal = finalize(
                MotionTraceFinalizationStatus.BOUNDED,
                MotionTraceTerminationReason.BYTE_LIMIT,
                durationNs = maximumRecordTimestampNs,
            )
            return MotionTraceAppendOutcome(false, DroppedSampleReason.LIMIT_REACHED, terminal)
        }
        try {
            if (output.write(line) == MotionTraceWriteDisposition.BACKPRESSURED) {
                incrementDrop(DroppedSampleReason.WRITER_BACKPRESSURE)
                return MotionTraceAppendOutcome(false, DroppedSampleReason.WRITER_BACKPRESSURE)
            }
        } catch (error: Throwable) {
            val recorderError = ioError(MotionTraceRecorderStage.APPEND, error)
            failWithoutFooter(recorderError)
            throw recorderError
        }

        bytesWritten += line.size
        counts.samples += 1
        lastSampleTimestampNs = sample.timestampNs
        lastSampleSequence = sample.sequence
        maximumRecordTimestampNs = maxOf(maximumRecordTimestampNs, sample.timestampNs)
        updateTiming(sample)

        if (sample.timestampNs >= limits.maximumDurationNs) {
            return MotionTraceAppendOutcome(
                true,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.DURATION_LIMIT,
                    durationNs = limits.maximumDurationNs,
                ),
            )
        }
        if (counts.samples >= limits.maximumSamples) {
            return MotionTraceAppendOutcome(
                true,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.SAMPLE_LIMIT,
                    durationNs = maximumRecordTimestampNs,
                ),
            )
        }
        return MotionTraceAppendOutcome(true)
    }

    @Synchronized
    fun append(change: MotionDisplayRotationChange): MotionTraceAppendOutcome {
        requireRecording("append display rotation change")
        MotionTraceValidation.validate(change)
        if (!MotionTraceValidation.isSafe(change.timestampNs) ||
            !MotionTraceValidation.isSafe(change.changeSequence) ||
            change.timestampNs < maximumRecordTimestampNs ||
            lastDisplayRotationChangeSequence?.let { change.changeSequence <= it } == true
        ) {
            throw MotionTraceRecorderException(
                MotionTraceRecorderErrorCode.INVALID_SAMPLE,
                MotionTraceRecorderStage.APPEND,
                "display rotation change time or sequence is invalid",
            )
        }
        return appendChangeRecord(
            change,
            MotionDisplayRotationChange.serializer(),
            change.timestampNs,
            "display rotation change",
        ) {
            counts.displayRotationChanges += 1
            lastDisplayRotationChangeSequence = change.changeSequence
        }
    }

    @Synchronized
    fun append(change: MotionCapabilityChange): MotionTraceAppendOutcome {
        requireRecording("append capability change")
        MotionTraceValidation.validate(change, capabilities)
        if (!MotionTraceValidation.isSafe(change.timestampNs) ||
            !MotionTraceValidation.isSafe(change.changeSequence) ||
            change.timestampNs < maximumRecordTimestampNs ||
            lastCapabilityChangeSequence?.let { change.changeSequence <= it } == true
        ) {
            throw MotionTraceRecorderException(
                MotionTraceRecorderErrorCode.INVALID_SAMPLE,
                MotionTraceRecorderStage.APPEND,
                "capability change time or sequence is invalid",
            )
        }
        return appendChangeRecord(
            change,
            MotionCapabilityChange.serializer(),
            change.timestampNs,
            "capability change",
        ) {
            counts.capabilityChanges += 1
            lastCapabilityChangeSequence = change.changeSequence
            change.availability?.let { capabilityAvailability[change.capabilityId] = it }
        }
    }

    @Synchronized
    fun append(annotation: MotionAnnotation): MotionTraceAppendOutcome {
        requireRecording("append annotation")
        MotionTraceValidation.validate(annotation, metadata.privacy)
        if (annotation.annotationId in annotationIds) {
            throw MotionTraceRecorderException(
                MotionTraceRecorderErrorCode.INVALID_SAMPLE,
                MotionTraceRecorderStage.APPEND,
                "duplicate annotationId ${annotation.annotationId}",
            )
        }
        val recordEnd = annotation.endTimestampNs ?: annotation.timestampNs
        if (recordEnd > limits.maximumDurationNs) {
            return MotionTraceAppendOutcome(
                false,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.DURATION_LIMIT,
                    durationNs = limits.maximumDurationNs,
                ),
            )
        }
        val line = encoder.line(MotionAnnotation.serializer(), annotation)
        if (!canWriteBody(line)) {
            return MotionTraceAppendOutcome(
                false,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.BYTE_LIMIT,
                    durationNs = maximumRecordTimestampNs,
                ),
            )
        }
        try {
            if (output.write(line) != MotionTraceWriteDisposition.WRITTEN) {
                throw MotionTraceRecorderException(
                    MotionTraceRecorderErrorCode.IO_FAILURE,
                    MotionTraceRecorderStage.APPEND,
                    "annotation write encountered backpressure",
                )
            }
        } catch (error: MotionTraceRecorderException) {
            failWithoutFooter(error)
            throw error
        } catch (error: Throwable) {
            val recorderError = ioError(MotionTraceRecorderStage.APPEND, error)
            failWithoutFooter(recorderError)
            throw recorderError
        }
        bytesWritten += line.size
        counts.annotations += 1
        annotationIds += annotation.annotationId
        maximumRecordTimestampNs = maxOf(maximumRecordTimestampNs, recordEnd)
        if (recordEnd >= limits.maximumDurationNs) {
            return MotionTraceAppendOutcome(
                true,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.DURATION_LIMIT,
                    durationNs = limits.maximumDurationNs,
                ),
            )
        }
        return MotionTraceAppendOutcome(true)
    }

    @Synchronized
    fun reportDroppedSample(reason: DroppedSampleReason) {
        requireRecording("report dropped sample")
        incrementDrop(reason)
    }

    @Synchronized
    fun finish(durationNs: Long): MotionTraceRecordingResult {
        result?.let { return it }
        terminalError?.let { throw it }
        requireRecording("finish")
        validateTerminalDuration(durationNs)
        return if (durationNs >= limits.maximumDurationNs) {
            finalize(
                MotionTraceFinalizationStatus.BOUNDED,
                MotionTraceTerminationReason.DURATION_LIMIT,
                durationNs = limits.maximumDurationNs,
            )
        } else {
            finalize(
                MotionTraceFinalizationStatus.COMPLETE,
                MotionTraceTerminationReason.REQUESTED_STOP,
                durationNs = durationNs,
            )
        }
    }

    @Synchronized
    fun cancel(durationNs: Long): MotionTraceRecordingResult {
        result?.let { return it }
        terminalError?.let { throw it }
        requireRecording("cancel")
        validateTerminalDuration(durationNs)
        return finalize(
            MotionTraceFinalizationStatus.CANCELLED,
            MotionTraceTerminationReason.CALLER_CANCELLED,
            durationNs = minOf(durationNs, limits.maximumDurationNs),
        )
    }

    @Synchronized
    fun failSource(code: String, durationNs: Long): MotionTraceRecordingResult {
        result?.let { return it }
        terminalError?.let { throw it }
        requireRecording("fail source")
        validateTerminalDuration(durationNs)
        if (!Regex("^[A-Za-z][A-Za-z0-9._:-]{0,127}$").matches(code)) {
            throw MotionTraceRecorderException(
                MotionTraceRecorderErrorCode.INVALID_SAMPLE,
                MotionTraceRecorderStage.FINALIZE,
                "source failure code is not a v1 identifier",
            )
        }
        return finalize(
            MotionTraceFinalizationStatus.FAILED,
            MotionTraceTerminationReason.SOURCE_FAILURE,
            failureCode = code,
            durationNs = minOf(durationNs, limits.maximumDurationNs),
        )
    }

    fun record(source: MotionSampleSource): MotionTraceRecordingResult {
        if (state == MotionTraceRecorderState.IDLE) start()
        while (state == MotionTraceRecorderState.RECORDING) {
            when (val event = source.nextEvent()) {
                is MotionSampleSourceEvent.Sample -> append(event.sample).recordingResult?.let { return it }
                is MotionSampleSourceEvent.Dropped -> reportDroppedSample(event.reason)
                is MotionSampleSourceEvent.Finished -> return finish(event.durationNs)
                is MotionSampleSourceEvent.Failed -> return failSource(event.code, event.durationNs)
            }
        }
        result?.let { return it }
        terminalError?.let { throw it }
        throw invalidState("record source", MotionTraceRecorderStage.APPEND)
    }

    private fun finalize(
        status: MotionTraceFinalizationStatus,
        reason: MotionTraceTerminationReason,
        failureCode: String? = null,
        durationNs: Long,
    ): MotionTraceRecordingResult {
        state = MotionTraceRecorderState.FINALIZING
        val footer = makeFooter(status, reason, failureCode, durationNs)
        try {
            val line = encoder.line(MotionTraceFooter.serializer(), footer)
            if (bytesWritten + line.size > limits.maximumBytes) {
                throw MotionTraceRecorderException(
                    MotionTraceRecorderErrorCode.IO_FAILURE,
                    MotionTraceRecorderStage.FINALIZE,
                    "footer exceeded its reserved byte budget",
                )
            }
            writeRequired(line, MotionTraceRecorderStage.FINALIZE)
            bytesWritten += line.size
            val completed = MotionTraceRecordingResult(footer, bytesWritten, output.commit())
            result = completed
            state = when (status) {
                MotionTraceFinalizationStatus.COMPLETE,
                MotionTraceFinalizationStatus.BOUNDED,
                -> MotionTraceRecorderState.FINISHED
                MotionTraceFinalizationStatus.CANCELLED -> MotionTraceRecorderState.CANCELLED
                MotionTraceFinalizationStatus.FAILED -> MotionTraceRecorderState.FAILED
            }
            return completed
        } catch (error: MotionTraceRecorderException) {
            failWithoutFooter(error)
            throw error
        } catch (error: Throwable) {
            val recorderError = ioError(MotionTraceRecorderStage.COMMIT, error)
            failWithoutFooter(recorderError)
            throw recorderError
        }
    }

    private fun makeFooter(
        status: MotionTraceFinalizationStatus,
        reason: MotionTraceTerminationReason,
        failureCode: String?,
        durationNs: Long,
    ) = MotionTraceFooter(
        finalizationStatus = status,
        terminationReason = reason,
        failureCode = failureCode,
        durationNs = durationNs,
        recordCounts = counts.copy(),
        droppedSamples = droppedSummary(),
        observedTiming = metadata.capabilities.mapNotNull { timing[it.capabilityId]?.summary },
    )

    private fun maximumSizeFooter(): MotionTraceFooter {
        val maximum = MotionTraceV1.MAXIMUM_SAFE_INTEGER
        return MotionTraceFooter(
            finalizationStatus = MotionTraceFinalizationStatus.FAILED,
            terminationReason = MotionTraceTerminationReason.SOURCE_FAILURE,
            failureCode = "x".repeat(128),
            durationNs = maximum,
            recordCounts = MotionTraceRecordCounts(maximum, maximum, maximum, maximum, maximum),
            reorderedSamples = maximum,
            droppedSamples = DroppedSampleSummary(
                maximum,
                DroppedSampleReason.entries.map { DroppedSampleCount(it, maximum) },
            ),
            observedTiming = metadata.capabilities.map {
                MotionObservedTiming(it.capabilityId, maximum, maximum, maximum)
            },
        )
    }

    private fun updateTiming(sample: MotionSample) {
        sample.signals.observations.forEach { (_, capabilityId, _) ->
            timing[capabilityId]?.observe(sample.timestampNs)
        }
    }

    private fun <T> appendChangeRecord(
        change: T,
        serializer: KSerializer<T>,
        timestampNs: Long,
        diagnosticName: String,
        onAccepted: () -> Unit,
    ): MotionTraceAppendOutcome {
        if (timestampNs > limits.maximumDurationNs) {
            return MotionTraceAppendOutcome(
                false,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.DURATION_LIMIT,
                    durationNs = limits.maximumDurationNs,
                ),
            )
        }
        val line = try {
            encoder.line(serializer, change)
        } catch (error: Throwable) {
            throw MotionTraceRecorderException(
                MotionTraceRecorderErrorCode.INVALID_SAMPLE,
                MotionTraceRecorderStage.APPEND,
                "$diagnosticName could not be encoded",
                cause = error,
            )
        }
        if (!canWriteBody(line)) {
            return MotionTraceAppendOutcome(
                false,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.BYTE_LIMIT,
                    durationNs = maximumRecordTimestampNs,
                ),
            )
        }
        try {
            if (output.write(line) != MotionTraceWriteDisposition.WRITTEN) {
                throw MotionTraceRecorderException(
                    MotionTraceRecorderErrorCode.IO_FAILURE,
                    MotionTraceRecorderStage.APPEND,
                    "$diagnosticName write encountered backpressure",
                )
            }
        } catch (error: MotionTraceRecorderException) {
            failWithoutFooter(error)
            throw error
        } catch (error: Throwable) {
            val recorderError = ioError(MotionTraceRecorderStage.APPEND, error)
            failWithoutFooter(recorderError)
            throw recorderError
        }
        bytesWritten += line.size
        onAccepted()
        maximumRecordTimestampNs = maxOf(maximumRecordTimestampNs, timestampNs)
        if (timestampNs >= limits.maximumDurationNs) {
            return MotionTraceAppendOutcome(
                true,
                recordingResult = finalize(
                    MotionTraceFinalizationStatus.BOUNDED,
                    MotionTraceTerminationReason.DURATION_LIMIT,
                    durationNs = limits.maximumDurationNs,
                ),
            )
        }
        return MotionTraceAppendOutcome(true)
    }

    private fun droppedSummary(): DroppedSampleSummary {
        val entries = DroppedSampleReason.entries.mapNotNull { reason ->
            droppedCounts[reason]?.takeIf { it > 0 }?.let { DroppedSampleCount(reason, it) }
        }
        return DroppedSampleSummary(entries.sumOf(DroppedSampleCount::count), entries)
    }

    private fun incrementDrop(reason: DroppedSampleReason) {
        droppedCounts[reason] = droppedCounts.getOrDefault(reason, 0) + 1
    }

    private fun canWriteBody(data: ByteArray) =
        bytesWritten + data.size + footerReserveBytes <= limits.maximumBytes

    private fun writeRequired(data: ByteArray, stage: MotionTraceRecorderStage) {
        if (output.write(data) != MotionTraceWriteDisposition.WRITTEN) {
            throw MotionTraceRecorderException(
                MotionTraceRecorderErrorCode.IO_FAILURE,
                stage,
                "required trace write encountered backpressure",
                output.temporaryPath,
            )
        }
    }

    private fun validateTerminalDuration(durationNs: Long) {
        if (!MotionTraceValidation.isSafe(durationNs) || durationNs < maximumRecordTimestampNs) {
            throw MotionTraceRecorderException(
                MotionTraceRecorderErrorCode.INVALID_SAMPLE,
                MotionTraceRecorderStage.FINALIZE,
                "terminal duration is invalid or precedes an accepted record",
            )
        }
    }

    private fun requireRecording(operation: String) {
        if (state != MotionTraceRecorderState.RECORDING) {
            throw invalidState(operation, MotionTraceRecorderStage.APPEND)
        }
    }

    private fun invalidState(operation: String, stage: MotionTraceRecorderStage) =
        MotionTraceRecorderException(
            MotionTraceRecorderErrorCode.INVALID_STATE,
            stage,
            "cannot $operation while recorder is ${state.name.lowercase()}",
            output.temporaryPath,
        )

    private fun ioError(stage: MotionTraceRecorderStage, underlying: Throwable) =
        MotionTraceRecorderException(
            MotionTraceRecorderErrorCode.IO_FAILURE,
            stage,
            underlying.toString(),
            output.temporaryPath,
            underlying,
        )

    private fun failWithoutFooter(error: MotionTraceRecorderException) {
        output.abortPreservingPartial()
        state = MotionTraceRecorderState.FAILED
        terminalError = error
    }
}

private class TimingAccumulator(private val capabilityId: String) {
    private var count = 0L
    private var lastTimestampNs: Long? = null
    private var minimumIntervalNs: Long? = null
    private var maximumIntervalNs: Long? = null

    fun observe(timestampNs: Long) {
        lastTimestampNs?.let { previous ->
            val interval = timestampNs - previous
            minimumIntervalNs = minimumIntervalNs?.let { minOf(it, interval) } ?: interval
            maximumIntervalNs = maximumIntervalNs?.let { maxOf(it, interval) } ?: interval
        }
        lastTimestampNs = timestampNs
        count += 1
    }

    val summary: MotionObservedTiming
        get() = MotionObservedTiming(capabilityId, count, minimumIntervalNs, maximumIntervalNs)
}
