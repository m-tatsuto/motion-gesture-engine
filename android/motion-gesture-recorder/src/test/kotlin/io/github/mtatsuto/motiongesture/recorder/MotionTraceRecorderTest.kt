package io.github.mtatsuto.motiongesture.recorder

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class MotionTraceRecorderTest {
    @Test
    fun normalCompletionStreamsAndCommitsAtomically() {
        val directory = Files.createTempDirectory("mge-recorder-test")
        try {
            val destination = directory.resolve("normal.mge.jsonl")
            val recorder = MotionTraceRecorder(
                metadata(includeAnnotations = true),
                limits(),
                destination,
            )

            recorder.start()
            assertFalse(Files.exists(destination))
            assertTrue(recorder.append(sample(0, 0)).accepted)
            assertTrue(recorder.append(sample(20, 1)).accepted)
            assertTrue(recorder.append(annotation(20)).accepted)
            val result = recorder.finish(30)
            val repeatedResult = recorder.finish(999)

            assertEquals(MotionTraceRecorderState.FINISHED, recorder.state)
            assertEquals(result, repeatedResult)
            assertEquals(MotionTraceFinalizationStatus.COMPLETE, result.footer.finalizationStatus)
            assertEquals(MotionTraceTerminationReason.REQUESTED_STOP, result.footer.terminationReason)
            assertEquals(2, result.footer.recordCounts.samples)
            assertEquals(1, result.footer.recordCounts.annotations)
            assertEquals(2, result.footer.observedTiming.first().acceptedObservationCount)
            assertEquals(20, result.footer.observedTiming.first().minimumIntervalNs)
            assertEquals(20, result.footer.observedTiming.first().maximumIntervalNs)
            assertEquals(destination, result.destinationPath)
            assertEquals(destination.readBytes().size.toLong(), result.bytesWritten)
            System.getenv("MGE_KOTLIN_TRACE_OUTPUT")?.let { exportPath ->
                Files.copy(destination, Path.of(exportPath), StandardCopyOption.REPLACE_EXISTING)
            }

            val records = jsonRecords(destination.readBytes())
            assertEquals("traceHeader", records.first()["recordType"]?.jsonPrimitive?.content)
            assertEquals(
                65_536,
                records.first()["recorderLimits"]?.jsonObject
                    ?.get("maximumBytes")?.jsonPrimitive?.long,
            )
            assertEquals("traceFooter", records.last()["recordType"]?.jsonPrimitive?.content)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun sampleAndDurationBoundsFinalizeAtAcceptedBoundary() {
        val sampleRecorder = MotionTraceRecorder(
            metadata(),
            MotionTraceRecorderLimits(1_000, 1, 65_536),
            MemoryTraceOutput(),
        )
        sampleRecorder.start()
        val sampleOutcome = sampleRecorder.append(sample(25, 0))
        assertTrue(sampleOutcome.accepted)
        assertEquals(
            MotionTraceTerminationReason.SAMPLE_LIMIT,
            sampleOutcome.recordingResult?.footer?.terminationReason,
        )

        val durationRecorder = MotionTraceRecorder(
            metadata(),
            MotionTraceRecorderLimits(100, 10, 65_536),
            MemoryTraceOutput(),
        )
        durationRecorder.start()
        val durationOutcome = durationRecorder.append(sample(100, 0))
        assertTrue(durationOutcome.accepted)
        assertEquals(
            MotionTraceTerminationReason.DURATION_LIMIT,
            durationOutcome.recordingResult?.footer?.terminationReason,
        )
    }

    @Test
    fun byteBoundIncludesHeaderFooterAndConfiguredMetadata() {
        val output = MemoryTraceOutput()
        val maximumBytes = 8_192L
        val recorder = MotionTraceRecorder(
            metadata(includeAnnotations = true),
            MotionTraceRecorderLimits(1_000_000, 1_000, maximumBytes),
            output,
        )
        recorder.start()

        var result: MotionTraceRecordingResult? = null
        for (index in 0 until 100) {
            if (result != null) break
            result = recorder.append(annotation(index.toLong(), index)).recordingResult
        }

        val bounded = assertNotNull(result)
        assertEquals(MotionTraceTerminationReason.BYTE_LIMIT, bounded.footer.terminationReason)
        assertTrue(bounded.bytesWritten <= maximumBytes)
        val header = jsonRecords(output.data.toByteArray()).first()
        assertEquals(
            maximumBytes,
            header["recorderLimits"]?.jsonObject?.get("maximumBytes")?.jsonPrimitive?.long,
        )
    }

    @Test
    fun cancellationIsFinalizedIncompleteAndIdempotent() {
        val output = MemoryTraceOutput()
        val recorder = MotionTraceRecorder(metadata(), limits(), output)
        recorder.start()
        recorder.append(sample(0, 0))

        val first = recorder.cancel(5)
        val second = recorder.cancel(999)

        assertEquals(first, second)
        assertEquals(MotionTraceRecorderState.CANCELLED, recorder.state)
        assertEquals(MotionTraceFinalizationStatus.CANCELLED, first.footer.finalizationStatus)
        assertEquals(MotionTraceTerminationReason.CALLER_CANCELLED, first.footer.terminationReason)
        assertTrue(output.committed)
    }

    @Test
    fun malformedUnsupportedAndNonMonotonicSamplesAreReported() {
        val recorder = MotionTraceRecorder(metadata(), limits(), MemoryTraceOutput())
        recorder.start()

        assertEquals(
            DroppedSampleReason.MALFORMED,
            recorder.append(MotionSample(timestampNs = 0, sequence = 0, signals = MotionSignals())).droppedReason,
        )
        val unknown = MotionSample(
            timestampNs = 0,
            sequence = 0,
            signals = MotionSignals(
                gravity = MotionVectorObservation(
                    "gravity.unknown",
                    MotionVector3(0.0, 0.0, 1.0),
                ),
            ),
        )
        assertEquals(DroppedSampleReason.UNSUPPORTED, recorder.append(unknown).droppedReason)
        assertTrue(recorder.append(sample(10, 2)).accepted)
        assertEquals(
            DroppedSampleReason.NON_MONOTONIC_TIMESTAMP,
            recorder.append(sample(11, 2)).droppedReason,
        )

        val result = recorder.finish(20)
        assertEquals(1, result.footer.recordCounts.samples)
        assertEquals(3, result.footer.droppedSamples.total)
        assertEquals(
            setOf(
                DroppedSampleReason.MALFORMED,
                DroppedSampleReason.UNSUPPORTED,
                DroppedSampleReason.NON_MONOTONIC_TIMESTAMP,
            ),
            result.footer.droppedSamples.byReason.map(DroppedSampleCount::reason).toSet(),
        )
    }

    @Test
    fun backpressureDropsSampleAndWriterFailureLeavesNoCommit() {
        val backpressureOutput = MemoryTraceOutput(backpressureWrites = setOf(2))
        val recorder = MotionTraceRecorder(metadata(), limits(), backpressureOutput)
        recorder.start()
        assertEquals(
            DroppedSampleReason.WRITER_BACKPRESSURE,
            recorder.append(sample(0, 0)).droppedReason,
        )
        assertTrue(recorder.append(sample(1, 0)).accepted)
        val result = recorder.finish(2)
        assertEquals(
            DroppedSampleReason.WRITER_BACKPRESSURE,
            result.footer.droppedSamples.byReason.first().reason,
        )

        val failingOutput = MemoryTraceOutput(failingWrites = setOf(2))
        val failingRecorder = MotionTraceRecorder(metadata(), limits(), failingOutput)
        failingRecorder.start()
        val error = assertFailsWith<MotionTraceRecorderException> {
            failingRecorder.append(sample(0, 0))
        }
        assertEquals(MotionTraceRecorderErrorCode.IO_FAILURE, error.code)
        assertEquals(MotionTraceRecorderState.FAILED, failingRecorder.state)
        assertFalse(failingOutput.committed)
        assertFalse(failingOutput.data.toByteArray().toString(Charsets.UTF_8).contains("traceFooter"))
    }

    @Test
    fun injectableSourceReportsItsDrops() {
        val output = MemoryTraceOutput()
        val recorder = MotionTraceRecorder(metadata(), limits(), output)
        val events = ArrayDeque<MotionSampleSourceEvent>(
            listOf(
                MotionSampleSourceEvent.Sample(sample(0, 0)),
                MotionSampleSourceEvent.Dropped(DroppedSampleReason.BUFFER_OVERFLOW),
                MotionSampleSourceEvent.Finished(10),
            ),
        )

        val result = recorder.record { events.removeFirst() }

        assertEquals(1, result.footer.recordCounts.samples)
        assertEquals(1, result.footer.droppedSamples.total)
        assertEquals(DroppedSampleReason.BUFFER_OVERFLOW, result.footer.droppedSamples.byReason.first().reason)
    }

    private fun metadata(includeAnnotations: Boolean = false) = MotionTraceMetadata(
        traceId = "00000000-0000-4000-8000-000000000102",
        producer = MotionTraceProducer(
            libraryName = "motionGestureRecorderKotlin",
            libraryVersion = "0.1.0",
            platformAdapterName = "testAdapter",
            platformAdapterVersion = "0.1.0",
        ),
        privacy = MotionTracePrivacy(
            MotionTracePrivacyTier.SYNTHETIC,
            if (includeAnnotations) {
                listOf(MotionTraceDataClass.MOTION_SENSOR_DATA, MotionTraceDataClass.GESTURE_ANNOTATION)
            } else {
                listOf(MotionTraceDataClass.MOTION_SENSOR_DATA)
            },
        ),
        session = MotionTraceSession(
            displayRotationClockwiseAtStart = 0,
            gestureFrameFromDeviceRowMajor = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        ),
        capabilities = listOf(
            MotionCapability(
                capabilityId = "gravity.main",
                signalKind = MotionSignalKind.GRAVITY,
                requirement = MotionCapabilityRequirement.REQUIRED,
                biasCorrection = MotionBiasCorrection.NOT_APPLICABLE,
                availability = MotionCapabilityAvailability.AVAILABLE,
                sourceKind = MotionSourceKind.FUSED,
                nativeSourceIdentifier = "test.gravity",
                nativeUnit = MotionNativeUnit.STANDARD_GRAVITY,
                nativeSignConvention = MotionNativeSignConvention.PHYSICAL_GRAVITY,
                conversions = listOf(MotionConversion.NONE),
            ),
        ),
    )

    private fun limits() = MotionTraceRecorderLimits(1_000, 10, 65_536)

    private fun sample(timestampNs: Long, sequence: Long) = MotionSample(
        timestampNs = timestampNs,
        sequence = sequence,
        signals = MotionSignals(
            gravity = MotionVectorObservation(
                "gravity.main",
                MotionVector3(0.0, 0.0, 0.8),
            ),
        ),
    )

    private fun annotation(timestampNs: Long, index: Int = 1) = MotionAnnotation(
        annotationId = "10000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}",
        annotationKind = MotionAnnotationKind.GESTURE_INTENT,
        timestampNs = timestampNs,
        gesture = MotionTraceGesture.TILT_FORWARD,
        provenance = MotionAnnotationProvenance(
            kind = MotionAnnotationProvenanceKind.SYNTHETIC,
            generatorId = "test.generator",
            generatorVersion = "1.0.0",
        ),
    )

    private fun jsonRecords(data: ByteArray) = data.toString(Charsets.UTF_8)
        .lineSequence()
        .filter(String::isNotEmpty)
        .map { Json.parseToJsonElement(it).jsonObject }
        .toList()
}

private class MemoryTraceOutput(
    private val backpressureWrites: Set<Int> = emptySet(),
    private val failingWrites: Set<Int> = emptySet(),
) : MotionTraceOutput {
    override val temporaryPath: Path? = null
    override val destinationPath: Path? = null
    val data = mutableListOf<Byte>()
    var committed = false
        private set
    private var writeCount = 0

    override fun start() = Unit

    override fun write(data: ByteArray): MotionTraceWriteDisposition {
        writeCount += 1
        if (writeCount in failingWrites) error("injected failure")
        if (writeCount in backpressureWrites) return MotionTraceWriteDisposition.BACKPRESSURED
        data.forEach(this.data::add)
        return MotionTraceWriteDisposition.WRITTEN
    }

    override fun commit(): Path? {
        committed = true
        return null
    }

    override fun abortPreservingPartial() = Unit
}

private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
