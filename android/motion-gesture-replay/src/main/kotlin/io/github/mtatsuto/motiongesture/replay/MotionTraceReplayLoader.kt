package io.github.mtatsuto.motiongesture.replay

import io.github.mtatsuto.motiongesture.recorder.MotionAnnotation
import io.github.mtatsuto.motiongesture.recorder.MotionCapability
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityAvailability
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityChange
import io.github.mtatsuto.motiongesture.recorder.MotionDisplayRotationChange
import io.github.mtatsuto.motiongesture.recorder.MotionPredictedEventRecord
import io.github.mtatsuto.motiongesture.recorder.MotionSample
import io.github.mtatsuto.motiongesture.recorder.MotionSignalKind
import io.github.mtatsuto.motiongesture.recorder.MotionTraceFinalizationStatus
import io.github.mtatsuto.motiongesture.recorder.MotionTraceFooter
import io.github.mtatsuto.motiongesture.recorder.MotionTraceHeader
import io.github.mtatsuto.motiongesture.recorder.MotionTraceRecordCounts
import io.github.mtatsuto.motiongesture.recorder.MotionTraceV1
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.sqrt

object MotionTraceReplayLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        allowSpecialFloatingPointValues = false
    }

    fun load(data: ByteArray, limits: ReplayLimits = ReplayLimits()): ValidatedReplayTrace {
        validateContainer(data, limits)
        val text = decodeUtf8(data)
        val lines = text.dropLast(1).split('\n')
        if (lines.any(String::isEmpty)) {
            throw replayError(ReplayErrorCode.INVALID_CONTAINER, "blank JSON Lines are forbidden")
        }
        if (lines.size > limits.maximumBodyRecords + 2) {
            throw replayError(
                ReplayErrorCode.LIMIT_EXCEEDED,
                "trace exceeds ${limits.maximumBodyRecords} body records",
            )
        }

        var header: MotionTraceHeader? = null
        var footer: MotionTraceFooter? = null
        val samples = mutableListOf<MotionSample>()
        var validator: ReplayTraceValidator? = null

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            if (line.toByteArray(Charsets.UTF_8).size > limits.maximumLineBytes) {
                throw replayError(
                    ReplayErrorCode.LIMIT_EXCEEDED,
                    "JSON line exceeds ${limits.maximumLineBytes} bytes",
                    lineNumber,
                )
            }
            if (footer != null) {
                throw replayError(
                    ReplayErrorCode.MALFORMED_RECORD,
                    "record follows traceFooter",
                    lineNumber,
                )
            }

            val objectValue = parseObject(line, lineNumber)
            val recordType = (objectValue["recordType"] as? JsonPrimitive)?.contentOrNull
                ?: throw replayError(
                    ReplayErrorCode.MALFORMED_RECORD,
                    "recordType is missing or is not a string",
                    lineNumber,
                )

            if (lineNumber == 1 && recordType != "traceHeader") {
                throw replayError(
                    ReplayErrorCode.MALFORMED_RECORD,
                    "line 1 must be traceHeader",
                    lineNumber,
                )
            }
            if (recordType == "traceHeader" && lineNumber != 1) {
                throw replayError(
                    ReplayErrorCode.MALFORMED_RECORD,
                    "traceHeader is allowed only on line 1",
                    lineNumber,
                )
            }

            when (recordType) {
                "traceHeader" -> {
                    checkVersion(objectValue, "schemaVersion", MotionTraceV1.SCHEMA_VERSION, lineNumber)
                    checkVersion(
                        objectValue,
                        "coreSpecVersion",
                        MotionTraceV1.CORE_SPEC_VERSION,
                        lineNumber,
                        spec = true,
                    )
                    val decoded = decode<MotionTraceHeader>(line, lineNumber)
                    val traceValidator = ReplayTraceValidator(decoded, data.size.toLong())
                    traceValidator.validateHeader()
                    header = decoded
                    validator = traceValidator
                }

                "sample" -> {
                    val decoded = decode<MotionSample>(line, lineNumber)
                    requireValidator(validator, lineNumber).sample(decoded, lineNumber)
                    samples += decoded
                }

                "annotation" -> requireValidator(validator, lineNumber).annotation(
                    decode<MotionAnnotation>(line, lineNumber),
                    lineNumber,
                )

                "predictedEvent" -> requireValidator(validator, lineNumber).prediction(
                    decode<MotionPredictedEventRecord>(line, lineNumber),
                    lineNumber,
                )

                "displayRotationChange" -> requireValidator(validator, lineNumber).displayChange(
                    decode<MotionDisplayRotationChange>(line, lineNumber),
                    lineNumber,
                )

                "capabilityChange" -> requireValidator(validator, lineNumber).capabilityChange(
                    decode<MotionCapabilityChange>(line, lineNumber),
                    lineNumber,
                )

                "traceFooter" -> {
                    checkVersion(objectValue, "schemaVersion", MotionTraceV1.SCHEMA_VERSION, lineNumber)
                    val decoded = decode<MotionTraceFooter>(line, lineNumber)
                    requireValidator(validator, lineNumber).footer(decoded, lineNumber)
                    footer = decoded
                }

                else -> throw replayError(
                    ReplayErrorCode.MALFORMED_RECORD,
                    "unknown recordType $recordType",
                    lineNumber,
                )
            }
        }

        val loadedHeader = header ?: throw replayError(
            ReplayErrorCode.INCOMPLETE_TRACE,
            "traceHeader is missing",
        )
        val loadedFooter = footer ?: throw replayError(
            ReplayErrorCode.INCOMPLETE_TRACE,
            "valid terminal traceFooter is missing",
        )
        return ValidatedReplayTrace(loadedHeader, samples.toList(), loadedFooter)
    }

    private fun validateContainer(data: ByteArray, limits: ReplayLimits) {
        if (data.size.toLong() > limits.maximumBytes) {
            throw replayError(
                ReplayErrorCode.LIMIT_EXCEEDED,
                "trace exceeds ${limits.maximumBytes} bytes",
            )
        }
        if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            throw replayError(
                ReplayErrorCode.UNSUPPORTED_COMPRESSION,
                "gzip input must be decompressed before native replay",
            )
        }
        if (data.size >= 3 && data[0] == 0xef.toByte() && data[1] == 0xbb.toByte() &&
            data[2] == 0xbf.toByte()
        ) {
            throw replayError(ReplayErrorCode.INVALID_CONTAINER, "UTF-8 BOM is forbidden")
        }
        if (data.any { it == '\r'.code.toByte() }) {
            throw replayError(ReplayErrorCode.INVALID_CONTAINER, "JSON Lines must use LF, not CRLF")
        }
        if (data.isEmpty() || data.last() != '\n'.code.toByte()) {
            throw replayError(
                ReplayErrorCode.INCOMPLETE_TRACE,
                "trace must end with LF and a complete footer",
            )
        }
    }

    private fun decodeUtf8(data: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
    } catch (error: Exception) {
        throw replayError(ReplayErrorCode.INVALID_CONTAINER, "trace is not valid UTF-8", cause = error)
    }

    private fun parseObject(line: String, lineNumber: Int): JsonObject = try {
        json.parseToJsonElement(line).jsonObject
    } catch (error: Exception) {
        throw replayError(
            ReplayErrorCode.MALFORMED_RECORD,
            "line is not a JSON object: ${error.message}",
            lineNumber,
            error,
        )
    }

    private inline fun <reified T> decode(line: String, lineNumber: Int): T = try {
        json.decodeFromString(line)
    } catch (error: SerializationException) {
        throw replayError(
            ReplayErrorCode.MALFORMED_RECORD,
            "record does not match Motion Trace v1: ${error.message}",
            lineNumber,
            error,
        )
    } catch (error: IllegalArgumentException) {
        throw replayError(
            ReplayErrorCode.MALFORMED_RECORD,
            "record contains an invalid value: ${error.message}",
            lineNumber,
            error,
        )
    }

    private fun checkVersion(
        value: JsonObject,
        field: String,
        supported: String,
        line: Int,
        spec: Boolean = false,
    ) {
        val actual = (value[field] as? JsonPrimitive)?.contentOrNull
            ?: throw replayError(
                ReplayErrorCode.MALFORMED_RECORD,
                "$field is missing",
                line,
            )
        if (actual != supported) {
            throw replayError(
                if (spec) ReplayErrorCode.UNSUPPORTED_SPEC_VERSION
                else ReplayErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "unsupported $field $actual",
                line,
            )
        }
    }

    private fun requireValidator(validator: ReplayTraceValidator?, line: Int) = validator
        ?: throw replayError(
            ReplayErrorCode.MALFORMED_RECORD,
            "traceHeader must precede body records",
            line,
        )
}

private class ReplayTraceValidator(
    private val header: MotionTraceHeader,
    private val containerBytes: Long,
) {
    private val capabilities = linkedMapOf<String, MotionCapability>()
    private val availability = mutableMapOf<String, MotionCapabilityAvailability>()
    private val timing = linkedMapOf<String, Timing>()
    private val sampleSequences = mutableSetOf<Long>()
    private val eventIds = mutableSetOf<String>()
    private val eventSequences = mutableMapOf<String, MutableSet<Long>>()
    private val lastPrediction = mutableMapOf<String, Pair<Long, Long>>()
    private val predictionSampleReferences = mutableListOf<Pair<Long, Int>>()
    private val displayChangeSequences = mutableSetOf<Long>()
    private val capabilityChangeSequences = mutableSetOf<Long>()
    private val detectorIds = header.detectors.orEmpty().associateBy { it.detectorStreamId }
    private var lastSample: Pair<Long, Long>? = null
    private var maximumTimestampNs = 0L
    private val counts = MotionTraceRecordCounts()

    fun validateHeader() {
        if (header.recordType != "traceHeader") malformed("invalid traceHeader recordType", 1)
        val conventions = header.conventions
        if (
            conventions.storedVectorFrame != "deviceD" ||
            conventions.gravityUnit != "standardGravity" ||
            conventions.userAccelerationUnit != "standardGravity" ||
            conventions.rotationRateUnit != "radianPerSecond" ||
            conventions.attitudeQuaternion != "xyzwReferenceFromDevice" ||
            conventions.timestampUnit != "nanosecond" ||
            conventions.timestampOrigin != "sessionMonotonicOrigin" ||
            conventions.sampleOrdering != "timestampThenSequence" ||
            conventions.standardGravityMps2 != 9.80665
        ) {
            malformed("unsupported Motion Trace convention", 1)
        }
        if (!conventions.attitudeQuaternionNormTolerance.isFinite() ||
            conventions.attitudeQuaternionNormTolerance < 0
        ) {
            malformed("invalid quaternion norm tolerance", 1)
        }
        if (!isOrthonormal(
                header.session.gestureFrameFromDeviceRowMajor,
                conventions.frameOrthonormalTolerance,
            )
        ) {
            malformed("gesture frame must be right-handed and orthonormal", 1)
        }
        if (!header.session.gestureFrameFrozen) malformed("gesture frame must be frozen", 1)
        if (header.capabilities.isEmpty()) malformed("at least one capability is required", 1)
        header.capabilities.forEach { capability ->
            if (capabilities.put(capability.capabilityId, capability) != null) {
                malformed("duplicate capability ${capability.capabilityId}", 1)
            }
            if (capability.requirement.name == "REQUIRED" &&
                capability.availability != MotionCapabilityAvailability.AVAILABLE
            ) {
                malformed("required capability ${capability.capabilityId} is unavailable", 1)
            }
            if (capability.conversions.isEmpty() ||
                (capability.conversions.any { it.name == "NONE" } && capability.conversions.size != 1)
            ) {
                malformed("invalid conversions for ${capability.capabilityId}", 1)
            }
            availability[capability.capabilityId] = capability.availability
            timing[capability.capabilityId] = Timing()
        }
        if (detectorIds.size != header.detectors.orEmpty().size) {
            malformed("duplicate detectorStreamId", 1)
        }
        val reordering = header.orderingPolicy.sampleReordering
        if (reordering.kind == "none" && reordering.maximumLatenessNs != null) {
            malformed("none reordering cannot declare maximumLatenessNs", 1)
        }
        if (reordering.kind == "bounded" && !isSafe(reordering.maximumLatenessNs)) {
            malformed("bounded reordering requires safe maximumLatenessNs", 1)
        }
        if (reordering.kind != "none" && reordering.kind != "bounded") {
            malformed("unsupported sample reordering ${reordering.kind}", 1)
        }
    }

    fun sample(sample: MotionSample, line: Int) {
        if (sample.recordType != "sample") malformed("invalid sample recordType", line)
        if (!isSafe(sample.timestampNs) || !isSafe(sample.sequence)) {
            malformed("sample timestamp and sequence must be wire-safe", line)
        }
        if (!sampleSequences.add(sample.sequence)) malformed("duplicate sample sequence", line)
        val pair = sample.timestampNs to sample.sequence
        lastSample?.let { previous ->
            if (pair.first < previous.first ||
                (pair.first == previous.first && pair.second <= previous.second)
            ) {
                throw replayError(
                    ReplayErrorCode.NON_MONOTONIC_SAMPLE,
                    "samples are not ordered by (timestampNs, sequence)",
                    line,
                )
            }
        }
        lastSample = pair
        maximumTimestampNs = maxOf(maximumTimestampNs, sample.timestampNs)

        var observationCount = 0
        sample.signals.gravity?.let {
            validateObservation(MotionSignalKind.GRAVITY, it.capabilityId, it.value.values(), line)
            observe(it.capabilityId, sample.timestampNs)
            observationCount += 1
        }
        sample.signals.userAcceleration?.let {
            validateObservation(
                MotionSignalKind.USER_ACCELERATION,
                it.capabilityId,
                it.value.values(),
                line,
            )
            observe(it.capabilityId, sample.timestampNs)
            observationCount += 1
        }
        sample.signals.rotationRate?.let {
            validateObservation(MotionSignalKind.ROTATION_RATE, it.capabilityId, it.value.values(), line)
            observe(it.capabilityId, sample.timestampNs)
            observationCount += 1
        }
        sample.signals.attitude?.let {
            val values = listOf(it.value.x, it.value.y, it.value.z, it.value.w)
            validateObservation(MotionSignalKind.ATTITUDE, it.capabilityId, values, line)
            if (header.session.attitudeReference == null) {
                malformed("attitude requires a session reference", line)
            }
            val norm = sqrt(values.sumOf { component -> component * component })
            if (abs(norm - 1.0) > header.conventions.attitudeQuaternionNormTolerance) {
                malformed("attitude quaternion is outside norm tolerance", line)
            }
            observe(it.capabilityId, sample.timestampNs)
            observationCount += 1
        }
        if (observationCount == 0) malformed("sample contains no signals", line)
        counts.samples += 1
    }

    fun annotation(annotation: MotionAnnotation, line: Int) {
        val endTimestampNs = annotation.endTimestampNs
        if (annotation.recordType != "annotation" || !isSafe(annotation.timestampNs) ||
            (endTimestampNs != null && !isSafe(endTimestampNs))
        ) {
            malformed("invalid annotation", line)
        }
        if (endTimestampNs != null && endTimestampNs < annotation.timestampNs) {
            malformed("annotation end precedes start", line)
        }
        maximumTimestampNs = maxOf(
            maximumTimestampNs,
            endTimestampNs ?: annotation.timestampNs,
        )
        counts.annotations += 1
    }

    fun prediction(prediction: MotionPredictedEventRecord, line: Int) {
        if (prediction.recordType != "predictedEvent" || !isSafe(prediction.timestampNs) ||
            !isSafe(prediction.eventSequence) ||
            (prediction.sourceSampleSequence != null && !isSafe(prediction.sourceSampleSequence))
        ) {
            malformed("invalid predicted event", line)
        }
        if (prediction.detectorStreamId !in detectorIds) {
            malformed("unknown detector stream ${prediction.detectorStreamId}", line)
        }
        if (!eventIds.add(prediction.eventId)) malformed("duplicate predicted event ID", line)
        val sequences = eventSequences.getOrPut(prediction.detectorStreamId) { mutableSetOf() }
        if (!sequences.add(prediction.eventSequence)) malformed("duplicate event sequence", line)
        val pair = prediction.timestampNs to prediction.eventSequence
        lastPrediction[prediction.detectorStreamId]?.let { previous ->
            if (pair.first < previous.first ||
                (pair.first == previous.first && pair.second <= previous.second)
            ) {
                malformed("predicted events are not ordered", line)
            }
        }
        lastPrediction[prediction.detectorStreamId] = pair
        prediction.sourceSampleSequence?.let { predictionSampleReferences += it to line }
        maximumTimestampNs = maxOf(maximumTimestampNs, prediction.timestampNs)
        counts.predictedEvents += 1
    }

    fun displayChange(change: MotionDisplayRotationChange, line: Int) {
        if (change.recordType != "displayRotationChange" || !isSafe(change.timestampNs) ||
            !isSafe(change.changeSequence) ||
            change.displayRotationClockwise !in setOf(0, 90, 180, 270)
        ) {
            malformed("invalid display rotation change", line)
        }
        if (!displayChangeSequences.add(change.changeSequence)) {
            malformed("duplicate display change sequence", line)
        }
        maximumTimestampNs = maxOf(maximumTimestampNs, change.timestampNs)
        counts.displayRotationChanges += 1
    }

    fun capabilityChange(change: MotionCapabilityChange, line: Int) {
        if (change.recordType != "capabilityChange" || !isSafe(change.timestampNs) ||
            !isSafe(change.changeSequence) || change.capabilityId !in capabilities ||
            (change.availability == null && change.accuracy == null)
        ) {
            malformed("invalid capability change", line)
        }
        if (!capabilityChangeSequences.add(change.changeSequence)) {
            malformed("duplicate capability change sequence", line)
        }
        change.availability?.let { availability[change.capabilityId] = it }
        maximumTimestampNs = maxOf(maximumTimestampNs, change.timestampNs)
        counts.capabilityChanges += 1
    }

    fun footer(footer: MotionTraceFooter, line: Int) {
        if (footer.recordType != "traceFooter" || footer.schemaVersion != MotionTraceV1.SCHEMA_VERSION) {
            malformed("invalid trace footer", line)
        }
        if (footer.finalizationStatus != MotionTraceFinalizationStatus.COMPLETE &&
            footer.finalizationStatus != MotionTraceFinalizationStatus.BOUNDED
        ) {
            throw replayError(
                ReplayErrorCode.INCOMPLETE_TRACE,
                "only finalized-complete traces can be replayed",
                line,
            )
        }
        if (!isSafe(footer.durationNs) || footer.durationNs < maximumTimestampNs) {
            footerMismatch("footer duration precedes trace records", line)
        }
        if (footer.recordCounts != counts) footerMismatch("footer record counts do not match", line)
        if (footer.droppedSamples.byReason.sumOf { it.count } != footer.droppedSamples.total) {
            footerMismatch("dropped-sample totals do not match", line)
        }
        if (header.orderingPolicy.sampleReordering.kind == "none" && footer.reorderedSamples != 0L) {
            footerMismatch("none reordering requires reorderedSamples = 0", line)
        }
        val declaredTiming = footer.observedTiming.associateBy { it.capabilityId }
        if (declaredTiming.size != footer.observedTiming.size || declaredTiming.keys != timing.keys) {
            footerMismatch("observed timing capability set does not match", line)
        }
        timing.forEach { (capabilityId, actual) ->
            val declared = declaredTiming.getValue(capabilityId)
            if (declared.acceptedObservationCount != actual.count ||
                declared.minimumIntervalNs != actual.minimum ||
                declared.maximumIntervalNs != actual.maximum
            ) {
                footerMismatch("observed timing does not match $capabilityId", line)
            }
        }
        header.recorderLimits?.let { limits ->
            if (footer.durationNs > limits.maximumDurationNs ||
                counts.samples > limits.maximumSamples || containerBytes > limits.maximumBytes
            ) {
                footerMismatch("trace exceeds declared recorder limits", line)
            }
        }
        predictionSampleReferences.forEach { (sequence, referenceLine) ->
            if (sequence !in sampleSequences) {
                malformed("prediction references unknown sample $sequence", referenceLine)
            }
        }
    }

    private fun validateObservation(
        kind: MotionSignalKind,
        capabilityId: String,
        values: List<Double>,
        line: Int,
    ) {
        val capability = capabilities[capabilityId]
            ?: malformed("unknown capability $capabilityId", line)
        if (capability.signalKind != kind) malformed("capability signal kind mismatch", line)
        if (availability[capabilityId] != MotionCapabilityAvailability.AVAILABLE) {
            malformed("capability $capabilityId is unavailable", line)
        }
        if (values.any { !it.isFinite() }) malformed("signal contains a non-finite value", line)
    }

    private fun observe(capabilityId: String, timestampNs: Long) {
        timing.getValue(capabilityId).observe(timestampNs)
    }

    private fun isOrthonormal(matrix: List<Double>, tolerance: Double): Boolean {
        if (matrix.size != 9 || matrix.any { !it.isFinite() } || !tolerance.isFinite() || tolerance < 0) {
            return false
        }
        val rows = listOf(matrix.subList(0, 3), matrix.subList(3, 6), matrix.subList(6, 9))
        fun dot(left: List<Double>, right: List<Double>) =
            left.zip(right).sumOf { (a, b) -> a * b }
        if (rows.any { abs(dot(it, it) - 1) > tolerance }) return false
        if (abs(dot(rows[0], rows[1])) > tolerance ||
            abs(dot(rows[0], rows[2])) > tolerance ||
            abs(dot(rows[1], rows[2])) > tolerance
        ) {
            return false
        }
        val cross = listOf(
            rows[1][1] * rows[2][2] - rows[1][2] * rows[2][1],
            rows[1][2] * rows[2][0] - rows[1][0] * rows[2][2],
            rows[1][0] * rows[2][1] - rows[1][1] * rows[2][0],
        )
        return abs(dot(rows[0], cross) - 1) <= tolerance
    }

    private fun footerMismatch(message: String, line: Int): Nothing = throw replayError(
        ReplayErrorCode.FOOTER_MISMATCH,
        message,
        line,
    )

    private fun malformed(message: String, line: Int): Nothing = throw replayError(
        ReplayErrorCode.MALFORMED_RECORD,
        message,
        line,
    )
}

private class Timing {
    var count: Long = 0
    var minimum: Long? = null
    var maximum: Long? = null
    private var lastTimestampNs: Long? = null

    fun observe(timestampNs: Long) {
        lastTimestampNs?.let { last ->
            val interval = timestampNs - last
            minimum = minimum?.let { minOf(it, interval) } ?: interval
            maximum = maximum?.let { maxOf(it, interval) } ?: interval
        }
        lastTimestampNs = timestampNs
        count += 1
    }
}

private fun io.github.mtatsuto.motiongesture.recorder.MotionVector3.values() = listOf(x, y, z)

private fun isSafe(value: Long?): Boolean =
    value != null && value in 0..MotionTraceV1.MAXIMUM_SAFE_INTEGER

private fun replayError(
    code: ReplayErrorCode,
    diagnostic: String,
    line: Int? = null,
    cause: Throwable? = null,
) = ReplayException(code, ReplayStage.LOAD, diagnostic, line, cause)
