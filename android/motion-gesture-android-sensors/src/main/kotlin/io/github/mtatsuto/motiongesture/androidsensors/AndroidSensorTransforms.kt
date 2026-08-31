package io.github.mtatsuto.motiongesture.androidsensors

import android.hardware.SensorManager
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracy
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracyLevel
import io.github.mtatsuto.motiongesture.recorder.MotionQuaternion
import io.github.mtatsuto.motiongesture.recorder.MotionQuaternionObservation
import io.github.mtatsuto.motiongesture.recorder.MotionTraceV1
import io.github.mtatsuto.motiongesture.recorder.MotionVector3
import io.github.mtatsuto.motiongesture.recorder.QuaternionNormalization
import kotlin.math.abs
import kotlin.math.sqrt

internal enum class AndroidSensorTransformError {
    MALFORMED,
    NON_MONOTONIC_TIMESTAMP,
}

internal class AndroidSensorTransformException(
    val transformError: AndroidSensorTransformError,
) : Exception(transformError.name.lowercase())

internal object AndroidSensorTransforms {
    const val STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED = 9.80665
    private const val MAXIMUM_NATIVE_QUATERNION_NORM_ERROR = 0.01
    private const val MATRIX_TOLERANCE = 0.000_01

    fun timestampNs(timestampElapsedRealtimeNs: Long, originElapsedRealtimeNs: Long): Long {
        if (timestampElapsedRealtimeNs < originElapsedRealtimeNs) {
            throw AndroidSensorTransformException(AndroidSensorTransformError.NON_MONOTONIC_TIMESTAMP)
        }
        val relative = try {
            Math.subtractExact(timestampElapsedRealtimeNs, originElapsedRealtimeNs)
        } catch (_: ArithmeticException) {
            throw AndroidSensorTransformException(AndroidSensorTransformError.MALFORMED)
        }
        if (relative !in 0..MotionTraceV1.MAXIMUM_SAFE_INTEGER) {
            throw AndroidSensorTransformException(AndroidSensorTransformError.MALFORMED)
        }
        return relative
    }

    fun vector(sensorType: AndroidSensorType, values: List<Double>): MotionVector3 {
        if (values.size < 3 || values.take(3).any { !it.isFinite() }) {
            throw AndroidSensorTransformException(AndroidSensorTransformError.MALFORMED)
        }
        val scale = when (sensorType) {
            AndroidSensorType.GRAVITY -> -1.0 / STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
            AndroidSensorType.LINEAR_ACCELERATION -> 1.0 / STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
            AndroidSensorType.GYROSCOPE,
            AndroidSensorType.GYROSCOPE_UNCALIBRATED,
            -> 1.0
            AndroidSensorType.ROTATION_VECTOR,
            AndroidSensorType.GAME_ROTATION_VECTOR,
            AndroidSensorType.GEOMAGNETIC_ROTATION_VECTOR,
            -> throw AndroidSensorTransformException(AndroidSensorTransformError.MALFORMED)
        }
        fun canonical(value: Double): Double = (value * scale).let { if (it == 0.0) 0.0 else it }
        return MotionVector3(canonical(values[0]), canonical(values[1]), canonical(values[2]))
    }

    fun attitude(
        capabilityId: String,
        quaternionWxyz: List<Double>?,
        rotationMatrixRowMajor: List<Double>?,
        accuracy: MotionAccuracy,
    ): MotionQuaternionObservation {
        if (quaternionWxyz == null || quaternionWxyz.size != 4 ||
            rotationMatrixRowMajor == null || rotationMatrixRowMajor.size != 9 ||
            quaternionWxyz.any { !it.isFinite() } || rotationMatrixRowMajor.any { !it.isFinite() }
        ) {
            throw AndroidSensorTransformException(AndroidSensorTransformError.MALFORMED)
        }
        val originalNorm = sqrt(quaternionWxyz.sumOf { it * it })
        if (!originalNorm.isFinite() || originalNorm <= 0 ||
            abs(originalNorm - 1) > MAXIMUM_NATIVE_QUATERNION_NORM_ERROR
        ) {
            throw AndroidSensorTransformException(AndroidSensorTransformError.MALFORMED)
        }
        val quaternion = Quaternion(
            x = quaternionWxyz[1] / originalNorm,
            y = quaternionWxyz[2] / originalNorm,
            z = quaternionWxyz[3] / originalNorm,
            w = quaternionWxyz[0] / originalNorm,
        )
        if (quaternion.rotationMatrix.zip(rotationMatrixRowMajor).maxOf { (left, right) -> abs(left - right) } >
            MATRIX_TOLERANCE
        ) {
            throw AndroidSensorTransformException(AndroidSensorTransformError.MALFORMED)
        }
        return MotionQuaternionObservation(
            capabilityId = capabilityId,
            value = MotionQuaternion(quaternion.x, quaternion.y, quaternion.z, quaternion.w),
            accuracy = accuracy,
            normalization = if (abs(originalNorm - 1) > 0.000_000_000_001) {
                QuaternionNormalization(originalNorm = originalNorm)
            } else {
                null
            },
        )
    }

    fun accuracy(nativeAccuracy: Int): MotionAccuracy = MotionAccuracy(
        level = when (nativeAccuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> MotionAccuracyLevel.UNRELIABLE
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> MotionAccuracyLevel.LOW
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MotionAccuracyLevel.MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> MotionAccuracyLevel.HIGH
            else -> MotionAccuracyLevel.UNKNOWN
        },
        nativeValue = nativeAccuracy,
    )

    fun gestureFrameFromDevice(rotation: AndroidDisplayRotation): List<Double> = when (rotation) {
        AndroidDisplayRotation.DEGREES_0 -> listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        AndroidDisplayRotation.DEGREES_90 -> listOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        AndroidDisplayRotation.DEGREES_180 -> listOf(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0)
        AndroidDisplayRotation.DEGREES_270 -> listOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    }
}

private data class Quaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    val rotationMatrix: List<Double>
        get() = listOf(
            1 - 2 * (y * y + z * z),
            2 * (x * y - z * w),
            2 * (x * z + y * w),
            2 * (x * y + z * w),
            1 - 2 * (x * x + z * z),
            2 * (y * z - x * w),
            2 * (x * z - y * w),
            2 * (y * z + x * w),
            1 - 2 * (x * x + y * y),
        )
}
