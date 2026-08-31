package io.github.mtatsuto.motiongesture.androidsensors

import io.github.mtatsuto.motiongesture.recorder.AttitudeReferenceKind
import io.github.mtatsuto.motiongesture.recorder.AttitudeReferenceScope
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracy
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracyLevel
import io.github.mtatsuto.motiongesture.recorder.MotionAttitudeReference
import io.github.mtatsuto.motiongesture.recorder.MotionBiasCorrection
import io.github.mtatsuto.motiongesture.recorder.MotionCapability
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityAvailability
import io.github.mtatsuto.motiongesture.recorder.MotionConversion
import io.github.mtatsuto.motiongesture.recorder.MotionNativeSignConvention
import io.github.mtatsuto.motiongesture.recorder.MotionNativeUnit
import io.github.mtatsuto.motiongesture.recorder.MotionRequestedTiming
import io.github.mtatsuto.motiongesture.recorder.MotionSensorProperties
import io.github.mtatsuto.motiongesture.recorder.MotionTracePrivacy
import io.github.mtatsuto.motiongesture.recorder.MotionTraceV1
import java.util.UUID

internal data class AndroidSensorSelection(
    val capabilities: List<MotionCapability>,
    val attitudeReference: MotionAttitudeReference?,
    val selectedBySignal: Map<AndroidSensorSignal, AndroidSensorDescriptor>,
    val selectedByType: Map<AndroidSensorType, AndroidSensorSignal>,
    val sensorSnapshot: List<AndroidSensorDescriptor>,
)

internal object AndroidSensorCapabilities {
    const val GRAVITY_ID = "androidSensors.gravity"
    const val USER_ACCELERATION_ID = "androidSensors.userAcceleration"
    const val ROTATION_RATE_ID = "androidSensors.rotationRate"
    const val ATTITUDE_ID = "androidSensors.attitude"

    fun select(
        traceId: String,
        privacy: MotionTracePrivacy,
        configuration: AndroidSensorRecorderConfiguration,
        driver: AndroidSensorDriver,
    ): AndroidSensorSelection {
        validate(configuration)
        val candidates = buildMap {
            if (AndroidSensorSignal.GRAVITY in configuration.enabledSignals) {
                put(AndroidSensorSignal.GRAVITY, listOf(AndroidSensorType.GRAVITY))
            }
            if (AndroidSensorSignal.USER_ACCELERATION in configuration.enabledSignals) {
                put(AndroidSensorSignal.USER_ACCELERATION, listOf(AndroidSensorType.LINEAR_ACCELERATION))
            }
            if (AndroidSensorSignal.ROTATION_RATE in configuration.enabledSignals) {
                put(AndroidSensorSignal.ROTATION_RATE, configuration.rotationRatePreference)
            }
            if (AndroidSensorSignal.ATTITUDE in configuration.enabledSignals) {
                put(AndroidSensorSignal.ATTITUDE, configuration.attitudePreference)
            }
        }
        val descriptorByType = candidates.values.flatten().distinct().associateWith(driver::descriptor)
        val selected = candidates.mapValues { (_, sensorTypes) ->
            sensorTypes.firstNotNullOfOrNull { sensorType ->
                descriptorByType.getValue(sensorType).takeIf {
                    it.availability == MotionCapabilityAvailability.AVAILABLE
                }
            } ?: descriptorByType.getValue(sensorTypes.first())
        }
        val intervalNs = Math.multiplyExact(configuration.requestedSamplingPeriodUs.toLong(), 1_000L)
        if (intervalNs !in 1..MotionTraceV1.MAXIMUM_SAFE_INTEGER) invalid(
            "requestedSamplingPeriodUs is outside the wire-safe range",
        )
        val capabilities = AndroidSensorSignal.entries.mapNotNull { signal ->
            selected[signal]?.let { descriptor ->
                capability(signal, descriptor, configuration, intervalNs)
            }
        }
        val availableByType = selected.mapNotNull { (signal, descriptor) ->
            descriptor.takeIf { it.availability == MotionCapabilityAvailability.AVAILABLE }
                ?.let { it.sensorType to signal }
        }.toMap()
        return AndroidSensorSelection(
            capabilities = capabilities,
            attitudeReference = attitudeReference(
                traceId,
                configuration,
                selected[AndroidSensorSignal.ATTITUDE],
            ),
            selectedBySignal = selected,
            selectedByType = availableByType,
            sensorSnapshot = descriptorByType.values.map { it.forRuntimePrivacy(privacy) },
        )
    }

    fun capabilityId(signal: AndroidSensorSignal): String = when (signal) {
        AndroidSensorSignal.GRAVITY -> GRAVITY_ID
        AndroidSensorSignal.USER_ACCELERATION -> USER_ACCELERATION_ID
        AndroidSensorSignal.ROTATION_RATE -> ROTATION_RATE_ID
        AndroidSensorSignal.ATTITUDE -> ATTITUDE_ID
    }

    private fun capability(
        signal: AndroidSensorSignal,
        descriptor: AndroidSensorDescriptor,
        configuration: AndroidSensorRecorderConfiguration,
        intervalNs: Long,
    ): MotionCapability {
        val properties = MotionSensorProperties(
            minimumDelayUs = descriptor.minimumDelayUs,
            maximumDelayUs = descriptor.maximumDelayUs,
            resolution = descriptor.resolution,
        ).takeIf {
            it.minimumDelayUs != null || it.maximumDelayUs != null || it.resolution != null
        }
        val biasCorrection = when (descriptor.sensorType) {
            AndroidSensorType.GYROSCOPE -> MotionBiasCorrection.BIAS_CORRECTED
            AndroidSensorType.GYROSCOPE_UNCALIBRATED -> MotionBiasCorrection.RAW
            else -> MotionBiasCorrection.NOT_APPLICABLE
        }
        val nativeUnit = when (signal) {
            AndroidSensorSignal.GRAVITY,
            AndroidSensorSignal.USER_ACCELERATION,
            -> MotionNativeUnit.METER_PER_SECOND_SQUARED
            AndroidSensorSignal.ROTATION_RATE -> MotionNativeUnit.RADIAN_PER_SECOND
            AndroidSensorSignal.ATTITUDE -> MotionNativeUnit.UNIT_QUATERNION
        }
        val signConvention = when (signal) {
            AndroidSensorSignal.GRAVITY -> MotionNativeSignConvention.SPECIFIC_FORCE
            AndroidSensorSignal.USER_ACCELERATION -> MotionNativeSignConvention.GRAVITY_REMOVED_ACCELERATION
            AndroidSensorSignal.ROTATION_RATE -> MotionNativeSignConvention.RIGHT_HAND_ANGULAR_VELOCITY
            AndroidSensorSignal.ATTITUDE -> MotionNativeSignConvention.REFERENCE_FROM_DEVICE
        }
        val conversions = when (signal) {
            AndroidSensorSignal.GRAVITY -> listOf(
                MotionConversion.NEGATE,
                MotionConversion.DIVIDE_BY_STANDARD_GRAVITY,
            )
            AndroidSensorSignal.USER_ACCELERATION -> listOf(MotionConversion.DIVIDE_BY_STANDARD_GRAVITY)
            AndroidSensorSignal.ROTATION_RATE -> listOf(MotionConversion.NONE)
            AndroidSensorSignal.ATTITUDE -> listOf(MotionConversion.QUATERNION_REORDER)
        }
        return MotionCapability(
            capabilityId = capabilityId(signal),
            signalKind = signal.signalKind,
            requirement = signal.requirement(configuration),
            biasCorrection = biasCorrection,
            availability = descriptor.availability,
            sourceKind = descriptor.sourceKind,
            nativeSourceIdentifier = descriptor.sensorType.nativeSourceIdentifier,
            nativeUnit = nativeUnit,
            nativeSignConvention = signConvention,
            conversions = conversions,
            requestedTiming = MotionRequestedTiming(
                intervalNs = intervalNs,
                nativeModeIdentifier = "android.sensor.registerListener.samplingPeriodUs",
            ),
            sensorProperties = properties,
            initialAccuracy = MotionAccuracy(MotionAccuracyLevel.UNKNOWN),
        )
    }

    private fun attitudeReference(
        traceId: String,
        configuration: AndroidSensorRecorderConfiguration,
        descriptor: AndroidSensorDescriptor?,
    ): MotionAttitudeReference? {
        if (descriptor?.availability != MotionCapabilityAvailability.AVAILABLE) return null
        return when (descriptor.sensorType) {
            AndroidSensorType.GAME_ROTATION_VECTOR -> {
                val referenceId = configuration.localAttitudeReferenceInstanceId ?: UUID.randomUUID().toString()
                if (referenceId == traceId) invalid(
                    "localAttitudeReferenceInstanceId must be distinct from traceId",
                )
                MotionAttitudeReference(
                    kind = AttitudeReferenceKind.GRAVITY_ALIGNED_SESSION_LOCAL,
                    scope = AttitudeReferenceScope.SESSION,
                    referenceInstanceId = referenceId,
                    nativeReferenceId = descriptor.sensorType.nativeSourceIdentifier,
                )
            }
            AndroidSensorType.ROTATION_VECTOR,
            AndroidSensorType.GEOMAGNETIC_ROTATION_VECTOR,
            -> MotionAttitudeReference(
                kind = AttitudeReferenceKind.EAST_NORTH_UP_MAGNETIC,
                scope = AttitudeReferenceScope.GLOBAL,
                nativeReferenceId = descriptor.sensorType.nativeSourceIdentifier,
            )
            else -> invalid("selected attitude sensor type is invalid")
        }
    }

    private fun validate(configuration: AndroidSensorRecorderConfiguration) {
        if (configuration.requestedSamplingPeriodUs <= 0) invalid(
            "requestedSamplingPeriodUs must be positive",
        )
        if (configuration.enabledSignals.isEmpty()) invalid("enabledSignals cannot be empty")
        if (!configuration.enabledSignals.containsAll(configuration.requiredSignals)) invalid(
            "requiredSignals must be a subset of enabledSignals",
        )
        if (AndroidSensorSignal.ROTATION_RATE in configuration.enabledSignals) {
            validatePreference(
                configuration.rotationRatePreference,
                AndroidSensorSignal.ROTATION_RATE,
                "rotationRatePreference",
            )
        }
        if (AndroidSensorSignal.ATTITUDE in configuration.enabledSignals) {
            validatePreference(
                configuration.attitudePreference,
                AndroidSensorSignal.ATTITUDE,
                "attitudePreference",
            )
        }
    }

    private fun validatePreference(
        preference: List<AndroidSensorType>,
        signal: AndroidSensorSignal,
        field: String,
    ) {
        if (preference.isEmpty() || preference.toSet().size != preference.size ||
            preference.any { it.signal != signal }
        ) invalid("$field must be non-empty, unique, and contain only $signal sensors")
    }

    private fun invalid(diagnostic: String): Nothing = throw AndroidSensorRecorderAdapterException(
        AndroidSensorRecorderAdapterErrorCode.INVALID_CONFIGURATION,
        diagnostic,
    )
}
