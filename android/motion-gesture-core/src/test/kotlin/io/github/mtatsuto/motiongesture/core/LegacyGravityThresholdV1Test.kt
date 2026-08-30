package io.github.mtatsuto.motiongesture.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacyGravityThresholdV1Test {
    @Test
    fun immutableConfigurationIdentityAndThresholds() {
        assertEquals("LegacyGravityThresholdV1", LegacyGravityThresholdV1Configuration.DETECTOR_ID)
        assertEquals("1.0.0", LegacyGravityThresholdV1Configuration.DETECTOR_VERSION)
        assertEquals("legacy.default.v1", LegacyGravityThresholdV1Configuration.CONFIGURATION_IDENTITY)
        assertEquals(0.65, LegacyGravityThresholdV1Configuration.TRIGGER_MAGNITUDE)
        assertEquals(0.35, LegacyGravityThresholdV1Configuration.REARM_MAGNITUDE)
    }

    @Test
    fun sharedCharacterizationFixture() {
        val detector = LegacyGravityThresholdV1()
        val emittedGestures = mutableListOf<Gesture>()

        fixtureRows().forEach { row ->
            when (row.operation) {
                "start" -> detector.start()
                "stop" -> detector.stop()
                "reset" -> detector.reset()
                "sample" -> {
                    val event = detector.consume(
                        GestureFrameGravitySample(
                            timestampNs = requireNotNull(row.timestampNs),
                            sequence = requireNotNull(row.sequence),
                            gravityZG = requireNotNull(row.gravityZG),
                        ),
                    )
                    assertEquals(row.expectedGesture, event?.gesture?.wireValue, "fixture step ${row.step}")
                    if (event != null) {
                        assertEquals(row.timestampNs, event.timestampNs)
                        assertEquals(row.sequence, event.sourceSampleSequence)
                        emittedGestures += event.gesture
                    }
                }
                else -> error("unsupported fixture operation ${row.operation} at step ${row.step}")
            }
        }

        assertEquals(
            listOf(
                Gesture.TILT_FORWARD,
                Gesture.TILT_BACKWARD,
                Gesture.TILT_FORWARD,
                Gesture.TILT_BACKWARD,
                Gesture.TILT_FORWARD,
            ),
            emittedGestures,
        )
    }

    @Test
    fun lifecycleRejectsInvalidOperations() {
        val detector = LegacyGravityThresholdV1()
        val sample = GestureFrameGravitySample(timestampNs = 0, sequence = 0, gravityZG = 0.0)

        val consumeError = assertFailsWith<DetectorInvalidStateException> {
            detector.consume(sample)
        }
        assertEquals("invalidState", consumeError.code)
        assertEquals(DetectorOperation.CONSUME, consumeError.operation)
        assertEquals(DetectorLifecycleState.IDLE, consumeError.state)

        detector.start()
        val startError = assertFailsWith<DetectorInvalidStateException> {
            detector.start()
        }
        assertEquals(DetectorOperation.START, startError.operation)
        assertEquals(DetectorLifecycleState.RUNNING, startError.state)

        detector.stop()
        val stopError = assertFailsWith<DetectorInvalidStateException> {
            detector.stop()
        }
        assertEquals(DetectorOperation.STOP, stopError.operation)
        assertEquals(DetectorLifecycleState.STOPPED, stopError.state)
    }

    @Test
    fun stopAndResetRestoreArmedState() {
        val detector = LegacyGravityThresholdV1()
        val crossingSample = GestureFrameGravitySample(timestampNs = 0, sequence = 0, gravityZG = 1.0)

        detector.start()
        assertTrue(detector.consume(crossingSample) != null)
        assertFalse(detector.isArmed)
        detector.stop()
        assertTrue(detector.isArmed)
        assertEquals(DetectorLifecycleState.STOPPED, detector.lifecycleState)

        detector.start()
        assertTrue(detector.consume(crossingSample) != null)
        assertFalse(detector.isArmed)
        detector.reset()
        assertTrue(detector.isArmed)
        assertEquals(DetectorLifecycleState.IDLE, detector.lifecycleState)
    }

    private fun fixtureRows(): List<FixtureRow> {
        val fixture = File(requireNotNull(System.getProperty("mge.legacyFixture")))
        return fixture.readLines()
            .drop(1)
            .filter(String::isNotBlank)
            .map { line ->
                val columns = line.split(',', limit = 6)
                require(columns.size == 6) { "invalid fixture row: $line" }
                FixtureRow(
                    step = columns[0].toInt(),
                    operation = columns[1],
                    timestampNs = columns[2].takeIf(String::isNotEmpty)?.toLong(),
                    sequence = columns[3].takeIf(String::isNotEmpty)?.toLong(),
                    gravityZG = columns[4].takeIf(String::isNotEmpty)?.toDouble(),
                    expectedGesture = columns[5].takeIf(String::isNotEmpty),
                )
            }
    }
}

private data class FixtureRow(
    val step: Int,
    val operation: String,
    val timestampNs: Long?,
    val sequence: Long?,
    val gravityZG: Double?,
    val expectedGesture: String?,
)
