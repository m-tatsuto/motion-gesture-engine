package io.github.mtatsuto.motiongesture.recorder

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object MotionTraceV1 {
    const val SCHEMA_VERSION: String = "1.0.0-draft.1"
    const val CORE_SPEC_VERSION: String = "1.0.0-draft.1"
    const val MAXIMUM_SAFE_INTEGER: Long = 9_007_199_254_740_991
}

@Serializable
data class MotionTraceProducer(
    val libraryName: String,
    val libraryVersion: String,
    val platformAdapterName: String,
    val platformAdapterVersion: String,
)

@Serializable
enum class MotionTracePrivacyTier {
    @SerialName("synthetic")
    SYNTHETIC,

    @SerialName("privateSensitive")
    PRIVATE_SENSITIVE,

    @SerialName("reviewedSanitized")
    REVIEWED_SANITIZED,

    @SerialName("publicApproved")
    PUBLIC_APPROVED,
}

@Serializable
enum class MotionTraceDataClass {
    @SerialName("motionSensorData")
    MOTION_SENSOR_DATA,

    @SerialName("gestureAnnotation")
    GESTURE_ANNOTATION,

    @SerialName("userReport")
    USER_REPORT,

    @SerialName("exactDeviceModel")
    EXACT_DEVICE_MODEL,

    @SerialName("osMajorVersion")
    OS_MAJOR_VERSION,
}

@Serializable
data class MotionTracePrivacy(
    val tier: MotionTracePrivacyTier,
    val dataClasses: List<MotionTraceDataClass>,
    val reviewProtocolVersion: String? = null,
)

@Serializable
enum class AttitudeReferenceKind {
    @SerialName("gravityAlignedSessionLocal")
    GRAVITY_ALIGNED_SESSION_LOCAL,

    @SerialName("eastNorthUpMagnetic")
    EAST_NORTH_UP_MAGNETIC,

    @SerialName("eastNorthUpTrue")
    EAST_NORTH_UP_TRUE,

    @SerialName("platformDefined")
    PLATFORM_DEFINED,
}

@Serializable
enum class AttitudeReferenceScope {
    @SerialName("session")
    SESSION,

    @SerialName("global")
    GLOBAL,
}

@Serializable
data class MotionAttitudeReference(
    val kind: AttitudeReferenceKind,
    val scope: AttitudeReferenceScope,
    val referenceInstanceId: String? = null,
    val nativeReferenceId: String,
    val axisDefinition: String? = null,
)

@Serializable
data class MotionTraceSession(
    val displayRotationClockwiseAtStart: Int,
    val gestureFrameFromDeviceRowMajor: List<Double>,
    val gestureFrameFrozen: Boolean = true,
    val attitudeReference: MotionAttitudeReference? = null,
)

@Serializable
enum class MotionSignalKind {
    @SerialName("gravity")
    GRAVITY,

    @SerialName("userAcceleration")
    USER_ACCELERATION,

    @SerialName("rotationRate")
    ROTATION_RATE,

    @SerialName("attitude")
    ATTITUDE,
}

@Serializable
enum class MotionCapabilityRequirement {
    @SerialName("required")
    REQUIRED,

    @SerialName("optional")
    OPTIONAL,
}

@Serializable
enum class MotionBiasCorrection {
    @SerialName("raw")
    RAW,

    @SerialName("biasCorrected")
    BIAS_CORRECTED,

    @SerialName("notApplicable")
    NOT_APPLICABLE,
}

@Serializable
enum class MotionCapabilityAvailability {
    @SerialName("available")
    AVAILABLE,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("restricted")
    RESTRICTED,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class MotionSourceKind {
    @SerialName("hardware")
    HARDWARE,

    @SerialName("software")
    SOFTWARE,

    @SerialName("fused")
    FUSED,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class MotionNativeUnit {
    @SerialName("standardGravity")
    STANDARD_GRAVITY,

    @SerialName("meterPerSecondSquared")
    METER_PER_SECOND_SQUARED,

    @SerialName("radianPerSecond")
    RADIAN_PER_SECOND,

    @SerialName("unitQuaternion")
    UNIT_QUATERNION,

    @SerialName("platformDefined")
    PLATFORM_DEFINED,
}

@Serializable
enum class MotionNativeSignConvention {
    @SerialName("physicalGravity")
    PHYSICAL_GRAVITY,

    @SerialName("specificForce")
    SPECIFIC_FORCE,

    @SerialName("gravityRemovedAcceleration")
    GRAVITY_REMOVED_ACCELERATION,

    @SerialName("rightHandAngularVelocity")
    RIGHT_HAND_ANGULAR_VELOCITY,

    @SerialName("referenceFromDevice")
    REFERENCE_FROM_DEVICE,

    @SerialName("deviceFromReference")
    DEVICE_FROM_REFERENCE,

    @SerialName("platformDefined")
    PLATFORM_DEFINED,
}

@Serializable
enum class MotionConversion {
    @SerialName("none")
    NONE,

    @SerialName("negate")
    NEGATE,

    @SerialName("divideByStandardGravity")
    DIVIDE_BY_STANDARD_GRAVITY,

    @SerialName("axisTransform")
    AXIS_TRANSFORM,

    @SerialName("quaternionReorder")
    QUATERNION_REORDER,

    @SerialName("quaternionInvert")
    QUATERNION_INVERT,

    @SerialName("referenceBasisTransform")
    REFERENCE_BASIS_TRANSFORM,
}

@Serializable
enum class MotionAccuracyLevel {
    @SerialName("unreliable")
    UNRELIABLE,

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class MotionAccuracy(
    val level: MotionAccuracyLevel,
    val nativeValue: Int? = null,
)

@Serializable
data class MotionRequestedTiming(
    val intervalNs: Long? = null,
    val nativeModeIdentifier: String? = null,
)

@Serializable
data class MotionSensorProperties(
    val minimumDelayUs: Long? = null,
    val maximumDelayUs: Long? = null,
    val resolution: Double? = null,
)

@Serializable
data class MotionCapability(
    val capabilityId: String,
    val signalKind: MotionSignalKind,
    val requirement: MotionCapabilityRequirement,
    val biasCorrection: MotionBiasCorrection,
    val availability: MotionCapabilityAvailability,
    val sourceKind: MotionSourceKind,
    val nativeSourceIdentifier: String,
    val nativeUnit: MotionNativeUnit,
    val nativeUnitIdentifier: String? = null,
    val nativeSignConvention: MotionNativeSignConvention,
    val nativeSignConventionIdentifier: String? = null,
    val conversions: List<MotionConversion>,
    val requestedTiming: MotionRequestedTiming? = null,
    val sensorProperties: MotionSensorProperties? = null,
    val initialAccuracy: MotionAccuracy? = null,
)

@Serializable
data class MotionDetectorDescriptor(
    val detectorStreamId: String,
    val detectorId: String,
    val detectorVersion: String,
    val configurationIdentity: String,
)

@Serializable
enum class MotionPlatformFamily {
    @SerialName("ios")
    IOS,

    @SerialName("android")
    ANDROID,

    @SerialName("other")
    OTHER,
}

@Serializable
data class ExactDeviceModel(
    val value: String,
    val privacyClass: String = "quasiIdentifier.exactDeviceModel",
)

@Serializable
data class MotionDeviceMetadata(
    val platformFamily: MotionPlatformFamily,
    val osMajorVersion: Int? = null,
    val exactModel: ExactDeviceModel? = null,
)

@Serializable
data class MotionTraceMetadata(
    val traceId: String,
    val producer: MotionTraceProducer,
    val privacy: MotionTracePrivacy,
    val session: MotionTraceSession,
    val capabilities: List<MotionCapability>,
    val detectors: List<MotionDetectorDescriptor>? = null,
    val device: MotionDeviceMetadata? = null,
)

@Serializable(with = MotionVector3Serializer::class)
data class MotionVector3(val x: Double, val y: Double, val z: Double) {
    internal val values: List<Double> get() = listOf(x, y, z)
}

object MotionVector3Serializer : KSerializer<MotionVector3> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: MotionVector3) {
        encoder.encodeSerializableValue(delegate, value.values)
    }

    override fun deserialize(decoder: Decoder): MotionVector3 {
        val values = decoder.decodeSerializableValue(delegate)
        require(values.size == 3) { "Expected 3 values" }
        return MotionVector3(values[0], values[1], values[2])
    }
}

@Serializable(with = MotionQuaternionSerializer::class)
data class MotionQuaternion(val x: Double, val y: Double, val z: Double, val w: Double) {
    internal val values: List<Double> get() = listOf(x, y, z, w)
}

object MotionQuaternionSerializer : KSerializer<MotionQuaternion> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: MotionQuaternion) {
        encoder.encodeSerializableValue(delegate, value.values)
    }

    override fun deserialize(decoder: Decoder): MotionQuaternion {
        val values = decoder.decodeSerializableValue(delegate)
        require(values.size == 4) { "Expected 4 values" }
        return MotionQuaternion(values[0], values[1], values[2], values[3])
    }
}

@Serializable
data class MotionVectorObservation(
    val capabilityId: String,
    val value: MotionVector3,
    val accuracy: MotionAccuracy? = null,
)

@Serializable
data class QuaternionNormalization(
    val method: String = "normalizedToUnit",
    val originalNorm: Double,
)

@Serializable
data class MotionQuaternionObservation(
    val capabilityId: String,
    val value: MotionQuaternion,
    val accuracy: MotionAccuracy? = null,
    val normalization: QuaternionNormalization? = null,
)

@Serializable
data class MotionSignals(
    val gravity: MotionVectorObservation? = null,
    val userAcceleration: MotionVectorObservation? = null,
    val rotationRate: MotionVectorObservation? = null,
    val attitude: MotionQuaternionObservation? = null,
) {
    internal val observations: List<Triple<MotionSignalKind, String, List<Double>>>
        get() = buildList {
            gravity?.let { add(Triple(MotionSignalKind.GRAVITY, it.capabilityId, it.value.values)) }
            userAcceleration?.let {
                add(Triple(MotionSignalKind.USER_ACCELERATION, it.capabilityId, it.value.values))
            }
            rotationRate?.let {
                add(Triple(MotionSignalKind.ROTATION_RATE, it.capabilityId, it.value.values))
            }
            attitude?.let { add(Triple(MotionSignalKind.ATTITUDE, it.capabilityId, it.value.values)) }
        }
}

@Serializable
data class MotionSample(
    val recordType: String = "sample",
    val timestampNs: Long,
    val sequence: Long,
    val signals: MotionSignals,
)

@Serializable
data class MotionDisplayRotationChange(
    val recordType: String = "displayRotationChange",
    val timestampNs: Long,
    val changeSequence: Long,
    val displayRotationClockwise: Int,
)

@Serializable
data class MotionCapabilityChange(
    val recordType: String = "capabilityChange",
    val timestampNs: Long,
    val changeSequence: Long,
    val capabilityId: String,
    val availability: MotionCapabilityAvailability? = null,
    val accuracy: MotionAccuracy? = null,
)

@Serializable
data class MotionPredictedEventRecord(
    val recordType: String = "predictedEvent",
    val eventId: String,
    val detectorStreamId: String,
    val eventSequence: Long,
    val timestampNs: Long,
    val gesture: MotionTraceGesture,
    val sourceSampleSequence: Long? = null,
)

@Serializable
enum class MotionAnnotationKind {
    @SerialName("gestureIntent")
    GESTURE_INTENT,

    @SerialName("gestureOnset")
    GESTURE_ONSET,

    @SerialName("gestureCommit")
    GESTURE_COMMIT,

    @SerialName("gestureEnd")
    GESTURE_END,

    @SerialName("neutralInterval")
    NEUTRAL_INTERVAL,

    @SerialName("negativeWindow")
    NEGATIVE_WINDOW,

    @SerialName("userReportedProblem")
    USER_REPORTED_PROBLEM,
}

@Serializable
enum class MotionAnnotationProvenanceKind {
    @SerialName("synthetic")
    SYNTHETIC,

    @SerialName("contributor")
    CONTRIBUTOR,

    @SerialName("userReport")
    USER_REPORT,

    @SerialName("reviewedGroundTruth")
    REVIEWED_GROUND_TRUTH,
}

@Serializable
data class MotionAnnotationProvenance(
    val kind: MotionAnnotationProvenanceKind,
    val generatorId: String? = null,
    val generatorVersion: String? = null,
    val collectionProtocolVersion: String? = null,
    val reviewProtocolVersion: String? = null,
    val sourceAnnotationIds: List<String>? = null,
)

@Serializable
enum class MotionTraceGesture {
    @SerialName("tiltForward")
    TILT_FORWARD,

    @SerialName("tiltBackward")
    TILT_BACKWARD,
}

@Serializable
enum class MotionUserProblemCode {
    @SerialName("missedGesture")
    MISSED_GESTURE,

    @SerialName("unexpectedGesture")
    UNEXPECTED_GESTURE,

    @SerialName("wrongDirection")
    WRONG_DIRECTION,

    @SerialName("timing")
    TIMING,

    @SerialName("other")
    OTHER,
}

@Serializable
data class MotionUserReport(
    val problemCode: MotionUserProblemCode,
    val expectedGesture: MotionTraceGesture? = null,
    val observedGesture: MotionTraceGesture? = null,
)

@Serializable
data class MotionAnnotation(
    val recordType: String = "annotation",
    val annotationId: String,
    val annotationKind: MotionAnnotationKind,
    val timestampNs: Long,
    val endTimestampNs: Long? = null,
    val gesture: MotionTraceGesture? = null,
    val provenance: MotionAnnotationProvenance,
    val report: MotionUserReport? = null,
)

@Serializable
data class MotionTraceRecorderLimits(
    val maximumDurationNs: Long,
    val maximumSamples: Long,
    val maximumBytes: Long,
)

@Serializable
enum class MotionTraceFinalizationStatus {
    @SerialName("complete")
    COMPLETE,

    @SerialName("bounded")
    BOUNDED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("failed")
    FAILED,
}

@Serializable
enum class MotionTraceTerminationReason {
    @SerialName("requestedStop")
    REQUESTED_STOP,

    @SerialName("durationLimit")
    DURATION_LIMIT,

    @SerialName("sampleLimit")
    SAMPLE_LIMIT,

    @SerialName("byteLimit")
    BYTE_LIMIT,

    @SerialName("callerCancelled")
    CALLER_CANCELLED,

    @SerialName("sourceFailure")
    SOURCE_FAILURE,

    @SerialName("ioFailure")
    IO_FAILURE,
}

@Serializable
enum class DroppedSampleReason {
    @SerialName("malformed")
    MALFORMED,

    @SerialName("nonMonotonicTimestamp")
    NON_MONOTONIC_TIMESTAMP,

    @SerialName("bufferOverflow")
    BUFFER_OVERFLOW,

    @SerialName("writerBackpressure")
    WRITER_BACKPRESSURE,

    @SerialName("limitReached")
    LIMIT_REACHED,

    @SerialName("sourceFailure")
    SOURCE_FAILURE,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("other")
    OTHER,
}

@Serializable
data class MotionTraceRecordCounts(
    var samples: Long = 0,
    var annotations: Long = 0,
    var predictedEvents: Long = 0,
    var displayRotationChanges: Long = 0,
    var capabilityChanges: Long = 0,
)

@Serializable
data class DroppedSampleCount(
    val reason: DroppedSampleReason,
    val count: Long,
    val capabilityId: String? = null,
)

@Serializable
data class DroppedSampleSummary(val total: Long, val byReason: List<DroppedSampleCount>)

@Serializable
data class MotionObservedTiming(
    val capabilityId: String,
    val acceptedObservationCount: Long,
    val minimumIntervalNs: Long? = null,
    val maximumIntervalNs: Long? = null,
)

@Serializable
data class MotionTraceFooter(
    val recordType: String = "traceFooter",
    val schemaVersion: String = MotionTraceV1.SCHEMA_VERSION,
    val finalizationStatus: MotionTraceFinalizationStatus,
    val terminationReason: MotionTraceTerminationReason,
    val failureCode: String? = null,
    val durationNs: Long,
    val recordCounts: MotionTraceRecordCounts,
    val reorderedSamples: Long = 0,
    val droppedSamples: DroppedSampleSummary,
    val observedTiming: List<MotionObservedTiming>,
)
