package io.github.mtatsuto.motiongesture.recorder

import kotlin.math.abs
import kotlin.math.sqrt

internal sealed interface SampleValidationResult {
    data object Valid : SampleValidationResult
    data class Malformed(val diagnostic: String) : SampleValidationResult
    data class Unsupported(val diagnostic: String) : SampleValidationResult
}

internal object MotionTraceValidation {
    private val identifier = Regex("^[A-Za-z][A-Za-z0-9._:-]{0,127}$")
    private val semanticVersion = Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$",
    )
    private val uuid = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
    )

    fun validate(limits: MotionTraceRecorderLimits) {
        requireConfig(isPositiveSafe(limits.maximumDurationNs)) {
            "maximumDurationNs must be a positive wire-safe integer"
        }
        requireConfig(isPositiveSafe(limits.maximumSamples)) {
            "maximumSamples must be a positive wire-safe integer"
        }
        requireConfig(isPositiveSafe(limits.maximumBytes)) {
            "maximumBytes must be a positive wire-safe integer"
        }
    }

    fun validate(metadata: MotionTraceMetadata) {
        requireConfig(uuid.matches(metadata.traceId)) { "traceId is not a v1 UUID" }
        validateIdentifier(metadata.producer.libraryName, "producer.libraryName")
        validateVersion(metadata.producer.libraryVersion, "producer.libraryVersion")
        validateIdentifier(metadata.producer.platformAdapterName, "producer.platformAdapterName")
        validateVersion(metadata.producer.platformAdapterVersion, "producer.platformAdapterVersion")

        val dataClasses = metadata.privacy.dataClasses.toSet()
        requireConfig(
            dataClasses.size == metadata.privacy.dataClasses.size &&
                dataClasses.size in 1..5 &&
                MotionTraceDataClass.MOTION_SENSOR_DATA in dataClasses,
        ) { "privacy.dataClasses must be unique and include motionSensorData" }
        when (metadata.privacy.tier) {
            MotionTracePrivacyTier.REVIEWED_SANITIZED,
            MotionTracePrivacyTier.PUBLIC_APPROVED,
            -> validateIdentifier(
                requireNotNullConfig(metadata.privacy.reviewProtocolVersion) {
                    "reviewed privacy tiers require reviewProtocolVersion"
                },
                "privacy.reviewProtocolVersion",
            )
            MotionTracePrivacyTier.SYNTHETIC,
            MotionTracePrivacyTier.PRIVATE_SENSITIVE,
            -> requireConfig(metadata.privacy.reviewProtocolVersion == null) {
                "reviewProtocolVersion is allowed only for reviewed privacy tiers"
            }
        }

        requireConfig(isOrthonormal(metadata.session.gestureFrameFromDeviceRowMajor)) {
            "gestureFrameFromDeviceRowMajor must be right-handed and orthonormal"
        }
        requireConfig(metadata.session.displayRotationClockwiseAtStart in setOf(0, 90, 180, 270)) {
            "display rotation must be 0, 90, 180, or 270"
        }
        metadata.session.attitudeReference?.let(::validate)

        requireConfig(metadata.capabilities.size in 1..64) {
            "capabilities must contain 1 through 64 entries"
        }
        val capabilityIds = mutableSetOf<String>()
        metadata.capabilities.forEach { capability ->
            validate(capability)
            requireConfig(capabilityIds.add(capability.capabilityId)) {
                "duplicate capabilityId ${capability.capabilityId}"
            }
        }

        metadata.detectors?.let { detectors ->
            requireConfig(detectors.size in 1..64) {
                "detectors must contain 1 through 64 entries when present"
            }
            val streamIds = mutableSetOf<String>()
            detectors.forEach { detector ->
                validateIdentifier(detector.detectorStreamId, "detectorStreamId")
                validateIdentifier(detector.detectorId, "detectorId")
                validateVersion(detector.detectorVersion, "detectorVersion")
                validateIdentifier(detector.configurationIdentity, "configurationIdentity")
                requireConfig(streamIds.add(detector.detectorStreamId)) {
                    "duplicate detectorStreamId ${detector.detectorStreamId}"
                }
            }
        }

        metadata.device?.let { device ->
            device.osMajorVersion?.let {
                requireConfig(it in 1..999 && MotionTraceDataClass.OS_MAJOR_VERSION in dataClasses) {
                    "osMajorVersion must be declared in privacy.dataClasses"
                }
            }
            device.exactModel?.let {
                requireConfig(
                    it.value.length in 1..64 && MotionTraceDataClass.EXACT_DEVICE_MODEL in dataClasses,
                ) { "exactModel must be declared in privacy.dataClasses" }
            }
        }
    }

    fun validate(
        sample: MotionSample,
        capabilities: Map<String, MotionCapability>,
        attitudeReferencePresent: Boolean,
    ): SampleValidationResult {
        if (!isSafe(sample.timestampNs) || !isSafe(sample.sequence)) {
            return SampleValidationResult.Malformed("sample time and sequence must be wire-safe integers")
        }
        if (sample.signals.observations.isEmpty()) {
            return SampleValidationResult.Malformed("sample must contain at least one signal")
        }
        sample.signals.observations.forEach { (signalKind, capabilityId, values) ->
            if (!values.all(Double::isFinite)) {
                return SampleValidationResult.Malformed("sample contains a non-finite signal value")
            }
            val capability = capabilities[capabilityId]
                ?: return SampleValidationResult.Unsupported("unknown capability $capabilityId")
            if (capability.signalKind != signalKind || capability.availability != MotionCapabilityAvailability.AVAILABLE) {
                return SampleValidationResult.Unsupported("capability $capabilityId cannot provide $signalKind")
            }
        }
        sample.signals.attitude?.let { attitude ->
            if (!attitudeReferencePresent) {
                return SampleValidationResult.Unsupported("attitude requires a session attitude reference")
            }
            val norm = sqrt(attitude.value.values.sumOf { it * it })
            if (abs(norm - 1) > 0.001) {
                return SampleValidationResult.Malformed("attitude quaternion is outside tolerance")
            }
            attitude.normalization?.let {
                if (!it.originalNorm.isFinite() || it.originalNorm <= 0) {
                    return SampleValidationResult.Malformed("normalization originalNorm must be positive")
                }
            }
        }
        return SampleValidationResult.Valid
    }

    fun validate(annotation: MotionAnnotation, privacy: MotionTracePrivacy) {
        requireSample(uuid.matches(annotation.annotationId)) { "annotationId is not a v1 UUID" }
        requireSample(isSafe(annotation.timestampNs)) { "annotation timestamp is not wire-safe" }
        annotation.endTimestampNs?.let {
            requireSample(isSafe(it) && it >= annotation.timestampNs) {
                "annotation interval end precedes its start"
            }
        }
        requireSample(MotionTraceDataClass.GESTURE_ANNOTATION in privacy.dataClasses) {
            "gestureAnnotation is not declared in privacy.dataClasses"
        }
        when (annotation.annotationKind) {
            MotionAnnotationKind.GESTURE_INTENT,
            MotionAnnotationKind.GESTURE_ONSET,
            MotionAnnotationKind.GESTURE_COMMIT,
            MotionAnnotationKind.GESTURE_END,
            -> requireSample(annotation.gesture != null && annotation.endTimestampNs == null) {
                "gesture annotations require gesture and no interval end"
            }
            MotionAnnotationKind.NEUTRAL_INTERVAL,
            MotionAnnotationKind.NEGATIVE_WINDOW,
            -> requireSample(annotation.endTimestampNs != null && annotation.gesture == null) {
                "interval annotations require endTimestampNs and no gesture"
            }
            MotionAnnotationKind.USER_REPORTED_PROBLEM -> requireSample(
                annotation.report != null &&
                    annotation.provenance.kind == MotionAnnotationProvenanceKind.USER_REPORT &&
                    MotionTraceDataClass.USER_REPORT in privacy.dataClasses,
            ) { "user report fields or privacy declaration are incomplete" }
        }
        if (annotation.annotationKind != MotionAnnotationKind.USER_REPORTED_PROBLEM) {
            requireSample(annotation.report == null) { "report is allowed only for userReportedProblem" }
        }
        validate(annotation.provenance)
    }

    fun isSafe(value: Long): Boolean = value in 0..MotionTraceV1.MAXIMUM_SAFE_INTEGER

    private fun isPositiveSafe(value: Long): Boolean = value in 1..MotionTraceV1.MAXIMUM_SAFE_INTEGER

    private fun validate(capability: MotionCapability) {
        validateIdentifier(capability.capabilityId, "capabilityId")
        validateIdentifier(capability.nativeSourceIdentifier, "nativeSourceIdentifier")
        requireConfig(
            capability.requirement != MotionCapabilityRequirement.REQUIRED ||
                capability.availability == MotionCapabilityAvailability.AVAILABLE,
        ) { "required capability ${capability.capabilityId} is not available" }
        requireConfig(
            (capability.signalKind == MotionSignalKind.ROTATION_RATE) ==
                (capability.biasCorrection != MotionBiasCorrection.NOT_APPLICABLE),
        ) { "biasCorrection does not match ${capability.signalKind}" }
        requireConfig(
            capability.conversions.size in 1..7 &&
                capability.conversions.toSet().size == capability.conversions.size &&
                (MotionConversion.NONE !in capability.conversions || capability.conversions.size == 1),
        ) { "capability conversions are empty, duplicated, or combine none" }
        if (capability.nativeUnit == MotionNativeUnit.PLATFORM_DEFINED) {
            validateIdentifier(
                requireNotNullConfig(capability.nativeUnitIdentifier) {
                    "platformDefined nativeUnit requires an identifier"
                },
                "nativeUnitIdentifier",
            )
        } else {
            requireConfig(capability.nativeUnitIdentifier == null) {
                "nativeUnitIdentifier requires platformDefined nativeUnit"
            }
        }
        if (capability.nativeSignConvention == MotionNativeSignConvention.PLATFORM_DEFINED) {
            validateIdentifier(
                requireNotNullConfig(capability.nativeSignConventionIdentifier) {
                    "platformDefined sign convention requires an identifier"
                },
                "nativeSignConventionIdentifier",
            )
        } else {
            requireConfig(capability.nativeSignConventionIdentifier == null) {
                "nativeSignConventionIdentifier requires platformDefined convention"
            }
        }
        capability.requestedTiming?.let { timing ->
            requireConfig(timing.intervalNs != null || timing.nativeModeIdentifier != null) {
                "requestedTiming cannot be empty"
            }
            timing.intervalNs?.let {
                requireConfig(isPositiveSafe(it)) { "requested interval must be positive and wire-safe" }
            }
            timing.nativeModeIdentifier?.let { validateIdentifier(it, "nativeModeIdentifier") }
        }
        capability.sensorProperties?.let { properties ->
            requireConfig(
                properties.minimumDelayUs != null ||
                    properties.maximumDelayUs != null ||
                    properties.resolution != null,
            ) { "sensorProperties cannot be empty" }
            properties.minimumDelayUs?.let {
                requireConfig(isSafe(it)) { "minimumDelayUs must be wire-safe" }
            }
            properties.maximumDelayUs?.let {
                requireConfig(isSafe(it)) { "maximumDelayUs must be wire-safe" }
            }
            properties.resolution?.let {
                requireConfig(it.isFinite() && it >= 0) { "resolution must be finite and non-negative" }
            }
        }
    }

    private fun validate(reference: MotionAttitudeReference) {
        validateIdentifier(reference.nativeReferenceId, "nativeReferenceId")
        when (reference.kind) {
            AttitudeReferenceKind.GRAVITY_ALIGNED_SESSION_LOCAL -> {
                requireConfig(
                    reference.scope == AttitudeReferenceScope.SESSION &&
                        reference.referenceInstanceId != null &&
                        reference.axisDefinition == null,
                ) { "gravityAlignedSessionLocal reference fields are invalid" }
                validateUuid(requireNotNull(reference.referenceInstanceId), "referenceInstanceId")
            }
            AttitudeReferenceKind.EAST_NORTH_UP_MAGNETIC,
            AttitudeReferenceKind.EAST_NORTH_UP_TRUE,
            -> requireConfig(
                reference.scope == AttitudeReferenceScope.GLOBAL &&
                    reference.referenceInstanceId == null &&
                    reference.axisDefinition == null,
            ) { "global east/north/up reference fields are invalid" }
            AttitudeReferenceKind.PLATFORM_DEFINED -> {
                requireConfig(reference.axisDefinition?.length in 1..256) {
                    "platformDefined reference requires axisDefinition"
                }
                if (reference.scope == AttitudeReferenceScope.SESSION) {
                    validateUuid(
                        requireNotNullConfig(reference.referenceInstanceId) {
                            "session platformDefined reference requires referenceInstanceId"
                        },
                        "referenceInstanceId",
                    )
                } else {
                    requireConfig(reference.referenceInstanceId == null) {
                        "global platformDefined reference cannot have referenceInstanceId"
                    }
                }
            }
        }
    }

    private fun validate(provenance: MotionAnnotationProvenance) {
        when (provenance.kind) {
            MotionAnnotationProvenanceKind.SYNTHETIC -> {
                requireSample(
                    provenance.generatorId != null && provenance.generatorVersion != null &&
                        provenance.collectionProtocolVersion == null &&
                        provenance.reviewProtocolVersion == null &&
                        provenance.sourceAnnotationIds == null,
                ) { "synthetic provenance fields are invalid" }
                validateIdentifier(requireNotNull(provenance.generatorId), "generatorId", sample = true)
                validateVersion(requireNotNull(provenance.generatorVersion), "generatorVersion", sample = true)
            }
            MotionAnnotationProvenanceKind.CONTRIBUTOR,
            MotionAnnotationProvenanceKind.USER_REPORT,
            -> {
                requireSample(
                    provenance.collectionProtocolVersion != null &&
                        provenance.generatorId == null && provenance.generatorVersion == null &&
                        provenance.reviewProtocolVersion == null && provenance.sourceAnnotationIds == null,
                ) { "collection provenance fields are invalid" }
                validateIdentifier(
                    requireNotNull(provenance.collectionProtocolVersion),
                    "collectionProtocolVersion",
                    sample = true,
                )
            }
            MotionAnnotationProvenanceKind.REVIEWED_GROUND_TRUTH -> {
                val ids = requireNotNullSample(provenance.sourceAnnotationIds) {
                    "reviewed provenance requires sourceAnnotationIds"
                }
                requireSample(
                    provenance.reviewProtocolVersion != null && ids.size in 1..64 &&
                        ids.toSet().size == ids.size && provenance.generatorId == null &&
                        provenance.generatorVersion == null && provenance.collectionProtocolVersion == null,
                ) { "reviewed provenance fields are invalid" }
                validateIdentifier(
                    requireNotNull(provenance.reviewProtocolVersion),
                    "reviewProtocolVersion",
                    sample = true,
                )
                ids.forEach { validateUuid(it, "sourceAnnotationIds", sample = true) }
            }
        }
    }

    private fun validateIdentifier(value: String, field: String, sample: Boolean = false) {
        if (!identifier.matches(value)) throw if (sample) sampleError("$field is not a v1 identifier")
        else configurationError("$field is not a v1 identifier")
    }

    private fun validateVersion(value: String, field: String, sample: Boolean = false) {
        if (value.length > 128 || !semanticVersion.matches(value)) {
            throw if (sample) sampleError("$field is not semantic version")
            else configurationError("$field is not semantic version")
        }
    }

    private fun validateUuid(value: String, field: String, sample: Boolean = false) {
        if (!uuid.matches(value)) throw if (sample) sampleError("$field is not a v1 UUID")
        else configurationError("$field is not a v1 UUID")
    }

    private fun isOrthonormal(matrix: List<Double>): Boolean {
        if (matrix.size != 9 || !matrix.all(Double::isFinite)) return false
        val rows = listOf(matrix.subList(0, 3), matrix.subList(3, 6), matrix.subList(6, 9))
        fun dot(left: List<Double>, right: List<Double>) = left.zip(right).sumOf { (a, b) -> a * b }
        if (rows.any { abs(dot(it, it) - 1) > 0.000_001 }) return false
        if (abs(dot(rows[0], rows[1])) > 0.000_001 ||
            abs(dot(rows[0], rows[2])) > 0.000_001 ||
            abs(dot(rows[1], rows[2])) > 0.000_001
        ) return false
        val cross = listOf(
            rows[1][1] * rows[2][2] - rows[1][2] * rows[2][1],
            rows[1][2] * rows[2][0] - rows[1][0] * rows[2][2],
            rows[1][0] * rows[2][1] - rows[1][1] * rows[2][0],
        )
        return abs(dot(rows[0], cross) - 1) <= 0.000_001
    }

    private fun configurationError(message: String) = MotionTraceRecorderException(
        MotionTraceRecorderErrorCode.INVALID_CONFIGURATION,
        MotionTraceRecorderStage.START,
        message,
    )

    private fun sampleError(message: String) = MotionTraceRecorderException(
        MotionTraceRecorderErrorCode.INVALID_SAMPLE,
        MotionTraceRecorderStage.APPEND,
        message,
    )

    private inline fun requireConfig(condition: Boolean, message: () -> String) {
        if (!condition) throw configurationError(message())
    }

    private inline fun requireSample(condition: Boolean, message: () -> String) {
        if (!condition) throw sampleError(message())
    }

    private inline fun <T : Any> requireNotNullConfig(value: T?, message: () -> String): T =
        value ?: throw configurationError(message())

    private inline fun <T : Any> requireNotNullSample(value: T?, message: () -> String): T =
        value ?: throw sampleError(message())
}
