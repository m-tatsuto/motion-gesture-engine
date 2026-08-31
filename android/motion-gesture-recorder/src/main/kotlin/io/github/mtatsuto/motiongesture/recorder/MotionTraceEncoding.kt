package io.github.mtatsuto.motiongesture.recorder

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class MotionTraceConventions(
    val storedVectorFrame: String = "deviceD",
    val gravityUnit: String = "standardGravity",
    val userAccelerationUnit: String = "standardGravity",
    val rotationRateUnit: String = "radianPerSecond",
    val attitudeQuaternion: String = "xyzwReferenceFromDevice",
    val attitudeQuaternionNormTolerance: Double = 0.001,
    val frameOrthonormalTolerance: Double = 0.000_001,
    val standardGravityMps2: Double = 9.80665,
    val timestampUnit: String = "nanosecond",
    val timestampOrigin: String = "sessionMonotonicOrigin",
    val sampleOrdering: String = "timestampThenSequence",
)

@Serializable
internal data class MotionTraceSampleReordering(val kind: String = "none")

@Serializable
internal data class MotionTraceOrderingPolicy(
    val sampleReordering: MotionTraceSampleReordering = MotionTraceSampleReordering(),
)

@Serializable
internal data class MotionTraceHeader(
    val recordType: String = "traceHeader",
    val schemaVersion: String = MotionTraceV1.SCHEMA_VERSION,
    val coreSpecVersion: String = MotionTraceV1.CORE_SPEC_VERSION,
    val traceId: String,
    val producer: MotionTraceProducer,
    val privacy: MotionTracePrivacy,
    val conventions: MotionTraceConventions = MotionTraceConventions(),
    val orderingPolicy: MotionTraceOrderingPolicy = MotionTraceOrderingPolicy(),
    val recorderLimits: MotionTraceRecorderLimits,
    val session: MotionTraceSession,
    val capabilities: List<MotionCapability>,
    val detectors: List<MotionDetectorDescriptor>? = null,
    val device: MotionDeviceMetadata? = null,
) {
    constructor(metadata: MotionTraceMetadata, limits: MotionTraceRecorderLimits) : this(
        traceId = metadata.traceId,
        producer = metadata.producer,
        privacy = metadata.privacy,
        recorderLimits = limits,
        session = metadata.session,
        capabilities = metadata.capabilities,
        detectors = metadata.detectors,
        device = metadata.device,
    )
}

internal class MotionTraceRecordEncoder {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun <T> line(serializer: SerializationStrategy<T>, value: T): ByteArray =
        (json.encodeToString(serializer, value) + "\n").toByteArray(Charsets.UTF_8)
}
