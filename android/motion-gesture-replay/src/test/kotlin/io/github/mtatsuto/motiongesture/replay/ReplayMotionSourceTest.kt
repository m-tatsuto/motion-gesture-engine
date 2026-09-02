package io.github.mtatsuto.motiongesture.replay

import io.github.mtatsuto.motiongesture.recorder.MotionPredictedEventRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayMotionSourceTest {
    @Test
    fun sharedFixtureProducesDeterministicPredictionsAcrossReset() {
        val source = ReplayMotionSource(LegacyGravityThresholdV1ReplayDetector())
        source.load(fixture("replay/legacy-gravity-threshold-v1.mge.jsonl"))

        val first = source.run()
        assertEquals(expectedPredictions(), first.events)
        assertEquals(9_000_000_000_000, first.finalVirtualTimestampNs)
        assertContentEquals(
            first.encodedPredictionsJsonLines(),
            first.encodedPredictionsJsonLines(),
        )

        source.reset()
        val second = source.run()
        assertEquals(first, second)
        assertContentEquals(
            first.encodedPredictionsJsonLines(),
            second.encodedPredictionsJsonLines(),
        )
    }

    @Test
    fun stepPreservesEqualTimestampsSequenceGapsAndLongVirtualGap() {
        val source = ReplayMotionSource(LegacyGravityThresholdV1ReplayDetector())
        source.load(fixture("replay/legacy-gravity-threshold-v1.mge.jsonl"))

        val first = requireNotNull(source.step())
        assertEquals(0, first.virtualTimestampNs)
        assertEquals(0, first.sample.sequence)
        assertEquals(1, first.emittedEvents.size)
        assertEquals(ReplayLifecycleState.REPLAYING, source.lifecycleState)

        val equalTimestamp = requireNotNull(source.step())
        assertEquals(0, equalTimestamp.virtualTimestampNs)
        assertEquals(2, equalTimestamp.sample.sequence)
        assertTrue(equalTimestamp.emittedEvents.isEmpty())

        requireNotNull(source.step())
        requireNotNull(source.step())
        val longGap = requireNotNull(source.step())
        assertEquals(9_000_000_000_000, longGap.virtualTimestampNs)
        assertEquals(10, longGap.sample.sequence)
        assertTrue(longGap.isFinished)
        assertEquals(ReplayLifecycleState.FINISHED, source.lifecycleState)
    }

    @Test
    fun emptyFinalizedTraceFinishesWithoutEvents() {
        val source = ReplayMotionSource(LegacyGravityThresholdV1ReplayDetector())
        source.load(fixture("replay/empty.mge.jsonl"))

        val result = source.run()

        assertTrue(result.events.isEmpty())
        assertNull(result.finalVirtualTimestampNs)
        assertEquals(ReplayLifecycleState.FINISHED, source.lifecycleState)
    }

    @Test
    fun loaderAcceptsFullSharedV1Fixture() {
        val trace = MotionTraceReplayLoader.load(
            Files.readAllBytes(fixture("v1/valid/full.mge.jsonl")),
        )

        assertEquals(2, trace.samples.size)
        assertEquals(1, trace.footer.recordCounts.predictedEvents)
    }

    @Test
    fun unknownSchemaVersionIsTyped() {
        val data = Files.readString(fixture("replay/empty.mge.jsonl"))
            .replaceFirst("1.0.0-draft.1", "9.0.0")
            .toByteArray()

        val error = assertFailsWith<ReplayException> {
            MotionTraceReplayLoader.load(data)
        }

        assertEquals(ReplayErrorCode.UNSUPPORTED_SCHEMA_VERSION, error.code)
        assertEquals(ReplayStage.LOAD, error.stage)
    }

    @Test
    fun unknownCoreSpecVersionIsTyped() {
        val data = Files.readString(fixture("replay/empty.mge.jsonl"))
            .replaceFirst(
                "\"coreSpecVersion\":\"1.0.0-draft.1\"",
                "\"coreSpecVersion\":\"9.0.0\"",
            )
            .toByteArray()

        val error = assertFailsWith<ReplayException> {
            MotionTraceReplayLoader.load(data)
        }

        assertEquals(ReplayErrorCode.UNSUPPORTED_SPEC_VERSION, error.code)
        assertEquals(ReplayStage.LOAD, error.stage)
    }

    @Test
    fun missingFooterAndFinalizedIncompleteTraceAreTyped() {
        val completeLines = Files.readAllLines(fixture("replay/empty.mge.jsonl"))
        val missingFooter = (completeLines.dropLast(1).joinToString("\n") + "\n").toByteArray()
        val missingError = assertFailsWith<ReplayException> {
            MotionTraceReplayLoader.load(missingFooter)
        }
        assertEquals(ReplayErrorCode.INCOMPLETE_TRACE, missingError.code)

        val cancelledError = assertFailsWith<ReplayException> {
            MotionTraceReplayLoader.load(
                Files.readAllBytes(fixture("v1/valid/cancelled.mge.jsonl")),
            )
        }
        assertEquals(ReplayErrorCode.INCOMPLETE_TRACE, cancelledError.code)
    }

    @Test
    fun malformedAndNonMonotonicTracesAreTyped() {
        val malformed = assertFailsWith<ReplayException> {
            MotionTraceReplayLoader.load("{}\n".toByteArray())
        }
        assertEquals(ReplayErrorCode.MALFORMED_RECORD, malformed.code)

        val nonMonotonic = assertFailsWith<ReplayException> {
            MotionTraceReplayLoader.load(
                Files.readAllBytes(fixture("v1/invalid/nonmonotonic-samples.mge.jsonl")),
            )
        }
        assertEquals(ReplayErrorCode.NON_MONOTONIC_SAMPLE, nonMonotonic.code)
    }

    @Test
    fun footerMismatchIsTyped() {
        val error = assertFailsWith<ReplayException> {
            MotionTraceReplayLoader.load(
                Files.readAllBytes(fixture("v1/invalid/footer-count-mismatch.mge.jsonl")),
            )
        }

        assertEquals(ReplayErrorCode.FOOTER_MISMATCH, error.code)
        assertEquals(ReplayStage.LOAD, error.stage)
    }

    @Test
    fun lifecycleRejectsRunBeforeLoad() {
        val source = ReplayMotionSource(LegacyGravityThresholdV1ReplayDetector())

        val error = assertFailsWith<ReplayException> { source.run() }

        assertEquals(ReplayErrorCode.INVALID_STATE, error.code)
        assertEquals(ReplayStage.RUN, error.stage)
        assertEquals(ReplayLifecycleState.IDLE, source.lifecycleState)
    }

    private fun expectedPredictions(): List<MotionPredictedEventRecord> {
        val json = Json { ignoreUnknownKeys = false }
        return Files.readAllLines(fixture("replay/legacy-gravity-threshold-v1.expected.jsonl"))
            .filter(String::isNotBlank)
            .map { json.decodeFromString(it) }
    }

    private fun fixture(relativePath: String): Path =
        Path.of(requireNotNull(System.getProperty("mge.fixtureDirectory")), relativePath)
}
