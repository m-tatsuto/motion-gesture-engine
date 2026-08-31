package io.github.mtatsuto.motiongesture.androidsensors

import android.hardware.Sensor
import android.view.Surface
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityAvailability
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityRequirement
import io.github.mtatsuto.motiongesture.recorder.MotionDetectorDescriptor
import io.github.mtatsuto.motiongesture.recorder.MotionDeviceMetadata
import io.github.mtatsuto.motiongesture.recorder.MotionSignalKind
import io.github.mtatsuto.motiongesture.recorder.MotionSourceKind
import io.github.mtatsuto.motiongesture.recorder.MotionTracePrivacy
import io.github.mtatsuto.motiongesture.recorder.MotionTracePrivacyTier

enum class AndroidDisplayRotation(val clockwiseDegrees: Int) {
    DEGREES_0(0),
    DEGREES_90(90),
    DEGREES_180(180),
    DEGREES_270(270),
    ;

    companion object {
        fun fromSurfaceRotation(surfaceRotation: Int): AndroidDisplayRotation = when (surfaceRotation) {
            Surface.ROTATION_0 -> DEGREES_0
            Surface.ROTATION_90 -> DEGREES_90
            Surface.ROTATION_180 -> DEGREES_180
            Surface.ROTATION_270 -> DEGREES_270
            else -> throw AndroidSensorRecorderAdapterException(
                AndroidSensorRecorderAdapterErrorCode.UNSUPPORTED_DISPLAY_ROTATION,
                "unsupported Surface rotation value $surfaceRotation",
            )
        }
    }
}

enum class AndroidSensorSignal(val signalKind: MotionSignalKind) {
    GRAVITY(MotionSignalKind.GRAVITY),
    USER_ACCELERATION(MotionSignalKind.USER_ACCELERATION),
    ROTATION_RATE(MotionSignalKind.ROTATION_RATE),
    ATTITUDE(MotionSignalKind.ATTITUDE),
}

enum class AndroidSensorType(
    val platformType: Int,
    val nativeSourceIdentifier: String,
    val signal: AndroidSensorSignal,
    val sourceKind: MotionSourceKind,
) {
    GRAVITY(
        Sensor.TYPE_GRAVITY,
        "android.sensor.type.gravity",
        AndroidSensorSignal.GRAVITY,
        MotionSourceKind.UNKNOWN,
    ),
    LINEAR_ACCELERATION(
        Sensor.TYPE_LINEAR_ACCELERATION,
        "android.sensor.type.linearAcceleration",
        AndroidSensorSignal.USER_ACCELERATION,
        MotionSourceKind.UNKNOWN,
    ),
    GYROSCOPE(
        Sensor.TYPE_GYROSCOPE,
        "android.sensor.type.gyroscope",
        AndroidSensorSignal.ROTATION_RATE,
        MotionSourceKind.HARDWARE,
    ),
    GYROSCOPE_UNCALIBRATED(
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        "android.sensor.type.gyroscopeUncalibrated",
        AndroidSensorSignal.ROTATION_RATE,
        MotionSourceKind.HARDWARE,
    ),
    ROTATION_VECTOR(
        Sensor.TYPE_ROTATION_VECTOR,
        "android.sensor.type.rotationVector",
        AndroidSensorSignal.ATTITUDE,
        MotionSourceKind.FUSED,
    ),
    GAME_ROTATION_VECTOR(
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        "android.sensor.type.gameRotationVector",
        AndroidSensorSignal.ATTITUDE,
        MotionSourceKind.FUSED,
    ),
    GEOMAGNETIC_ROTATION_VECTOR(
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        "android.sensor.type.geomagneticRotationVector",
        AndroidSensorSignal.ATTITUDE,
        MotionSourceKind.FUSED,
    ),
    ;

    companion object {
        fun fromPlatformType(platformType: Int): AndroidSensorType? = entries.firstOrNull {
            it.platformType == platformType
        }
    }
}

data class AndroidSensorDescriptor(
    val sensorType: AndroidSensorType,
    val availability: MotionCapabilityAvailability,
    val minimumDelayUs: Long? = null,
    val maximumDelayUs: Long? = null,
    val resolution: Double? = null,
    val vendor: String? = null,
    val version: Int? = null,
) {
    val sourceKind: MotionSourceKind get() = sensorType.sourceKind

    internal fun forRuntimePrivacy(privacy: MotionTracePrivacy): AndroidSensorDescriptor =
        if (privacy.tier.allowsPrivateSensorIdentity) {
            this
        } else {
            copy(vendor = null, version = null)
        }
}

data class AndroidRawSensorObservation(
    val sensorType: AndroidSensorType,
    val timestampElapsedRealtimeNs: Long,
    val values: List<Double>,
    val nativeAccuracy: Int,
    val attitudeQuaternionWxyz: List<Double>? = null,
    val attitudeRotationMatrixRowMajor: List<Double>? = null,
)

data class AndroidSensorDriverFailure(
    val code: String,
    val diagnostic: String,
    val cause: Throwable? = null,
)

sealed interface AndroidSensorDriverEvent {
    data class Observation(val observation: AndroidRawSensorObservation) : AndroidSensorDriverEvent

    data class AccuracyChanged(
        val sensorType: AndroidSensorType,
        val nativeAccuracy: Int,
        val callbackElapsedRealtimeNs: Long,
    ) : AndroidSensorDriverEvent

    data class Unavailable(
        val sensorType: AndroidSensorType,
        val callbackElapsedRealtimeNs: Long,
    ) : AndroidSensorDriverEvent

    data class Failure(val failure: AndroidSensorDriverFailure) : AndroidSensorDriverEvent
}

/** Single-session acquisition boundary used by the production SensorManager driver and JVM fakes. */
interface AndroidSensorDriver {
    fun descriptor(sensorType: AndroidSensorType): AndroidSensorDescriptor

    fun elapsedRealtimeNanos(): Long

    fun start(
        sensorTypes: Set<AndroidSensorType>,
        requestedSamplingPeriodUs: Int,
        listener: (AndroidSensorDriverEvent) -> Unit,
    )

    fun stop()
}

data class AndroidSensorRecorderConfiguration(
    val requestedSamplingPeriodUs: Int = 20_000,
    val initialDisplayRotation: AndroidDisplayRotation,
    val enabledSignals: Set<AndroidSensorSignal> = AndroidSensorSignal.entries.toSet(),
    val requiredSignals: Set<AndroidSensorSignal> = setOf(AndroidSensorSignal.GRAVITY),
    val rotationRatePreference: List<AndroidSensorType> = listOf(
        AndroidSensorType.GYROSCOPE,
        AndroidSensorType.GYROSCOPE_UNCALIBRATED,
    ),
    val attitudePreference: List<AndroidSensorType> = listOf(
        AndroidSensorType.GAME_ROTATION_VECTOR,
        AndroidSensorType.ROTATION_VECTOR,
        AndroidSensorType.GEOMAGNETIC_ROTATION_VECTOR,
    ),
    val localAttitudeReferenceInstanceId: String? = null,
    val gestureFrameFromDeviceRowMajor: List<Double>? = null,
)

data class AndroidSensorTraceContext(
    val traceId: String,
    val libraryName: String,
    val libraryVersion: String,
    val privacy: MotionTracePrivacy,
    val detectors: List<MotionDetectorDescriptor>? = null,
    val device: MotionDeviceMetadata? = null,
)

enum class AndroidSensorRecorderAdapterState {
    IDLE,
    STARTING,
    RUNNING,
    FINALIZING,
    FINISHED,
    CANCELLED,
    FAILED,
}

enum class AndroidSensorRecorderAdapterErrorCode {
    INVALID_CONFIGURATION,
    INVALID_STATE,
    REQUIRED_CAPABILITY_UNAVAILABLE,
    UNSUPPORTED_DISPLAY_ROTATION,
    DRIVER_FAILURE,
    RECORDER_FAILURE,
}

class AndroidSensorRecorderAdapterException(
    val code: AndroidSensorRecorderAdapterErrorCode,
    val diagnostic: String,
    cause: Throwable? = null,
) : Exception("${code.name.lowercase()}: $diagnostic", cause)

internal val MotionTracePrivacyTier.allowsPrivateSensorIdentity: Boolean
    get() = this == MotionTracePrivacyTier.PRIVATE_SENSITIVE

internal fun AndroidSensorSignal.requirement(
    configuration: AndroidSensorRecorderConfiguration,
): MotionCapabilityRequirement = if (this in configuration.requiredSignals) {
    MotionCapabilityRequirement.REQUIRED
} else {
    MotionCapabilityRequirement.OPTIONAL
}
