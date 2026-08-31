package io.github.mtatsuto.motiongesture.androidsensors

import android.view.Surface
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracy
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracyLevel
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidSensorTransformsTest {
    @Test
    fun gravityFixtureNegatesSpecificForceAndDividesByStandardGravity() {
        val gravity = AndroidSensorTransforms.vector(
            AndroidSensorType.GRAVITY,
            listOf(0.0, 5.883990, -7.845320),
        )

        assertEquals(0.0, gravity.x, absoluteTolerance = 0.000_000_1)
        assertEquals(0.0.toRawBits(), gravity.x.toRawBits())
        assertEquals(-0.6, gravity.y, absoluteTolerance = 0.000_000_1)
        assertEquals(0.8, gravity.z, absoluteTolerance = 0.000_000_1)
    }

    @Test
    fun linearAccelerationAndGyroscopeUseTheirCanonicalUnits() {
        val acceleration = AndroidSensorTransforms.vector(
            AndroidSensorType.LINEAR_ACCELERATION,
            listOf(0.980665, 0.0, 0.0),
        )
        val rotationRate = AndroidSensorTransforms.vector(
            AndroidSensorType.GYROSCOPE,
            listOf(0.2, -0.3, 0.4),
        )

        assertEquals(0.1, acceleration.x, absoluteTolerance = 0.000_000_1)
        assertEquals(0.2, rotationRate.x)
        assertEquals(-0.3, rotationRate.y)
        assertEquals(0.4, rotationRate.z)
    }

    @Test
    fun androidWxyzQuaternionIsReorderedAndCheckedAgainstRotationMatrix() {
        val half = sqrt(0.5)
        val observation = AndroidSensorTransforms.attitude(
            capabilityId = AndroidSensorCapabilities.ATTITUDE_ID,
            quaternionWxyz = listOf(half, half, 0.0, 0.0),
            rotationMatrixRowMajor = listOf(
                1.0, 0.0, 0.0,
                0.0, 0.0, -1.0,
                0.0, 1.0, 0.0,
            ),
            accuracy = MotionAccuracy(MotionAccuracyLevel.HIGH, nativeValue = 3),
        )

        assertEquals(half, observation.value.x, absoluteTolerance = 0.000_000_1)
        assertEquals(0.0, observation.value.y)
        assertEquals(0.0, observation.value.z)
        assertEquals(half, observation.value.w, absoluteTolerance = 0.000_000_1)

        assertFailsWith<AndroidSensorTransformException> {
            AndroidSensorTransforms.attitude(
                capabilityId = AndroidSensorCapabilities.ATTITUDE_ID,
                quaternionWxyz = listOf(half, half, 0.0, 0.0),
                rotationMatrixRowMajor = listOf(
                    1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0,
                    0.0, -1.0, 0.0,
                ),
                accuracy = MotionAccuracy(MotionAccuracyLevel.HIGH, nativeValue = 3),
            )
        }
    }

    @Test
    fun elapsedRealtimeTimestampRejectsNegativeAndOverflowingResults() {
        assertEquals(250_000_000, AndroidSensorTransforms.timestampNs(9_250_000_000, 9_000_000_000))

        val negative = assertFailsWith<AndroidSensorTransformException> {
            AndroidSensorTransforms.timestampNs(9, 10)
        }
        assertEquals(AndroidSensorTransformError.NON_MONOTONIC_TIMESTAMP, negative.transformError)

        val overflow = assertFailsWith<AndroidSensorTransformException> {
            AndroidSensorTransforms.timestampNs(Long.MAX_VALUE, 0)
        }
        assertEquals(AndroidSensorTransformError.MALFORMED, overflow.transformError)
    }

    @Test
    fun everySurfaceRotationMapsExplicitly() {
        assertEquals(AndroidDisplayRotation.DEGREES_0, AndroidDisplayRotation.fromSurfaceRotation(Surface.ROTATION_0))
        assertEquals(AndroidDisplayRotation.DEGREES_90, AndroidDisplayRotation.fromSurfaceRotation(Surface.ROTATION_90))
        assertEquals(AndroidDisplayRotation.DEGREES_180, AndroidDisplayRotation.fromSurfaceRotation(Surface.ROTATION_180))
        assertEquals(AndroidDisplayRotation.DEGREES_270, AndroidDisplayRotation.fromSurfaceRotation(Surface.ROTATION_270))
        assertFailsWith<AndroidSensorRecorderAdapterException> {
            AndroidDisplayRotation.fromSurfaceRotation(99)
        }
    }
}
