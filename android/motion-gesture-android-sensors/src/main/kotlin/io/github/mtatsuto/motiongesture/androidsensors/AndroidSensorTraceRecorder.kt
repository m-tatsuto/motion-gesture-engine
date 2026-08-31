package io.github.mtatsuto.motiongesture.androidsensors

import android.content.Context
import io.github.mtatsuto.motiongesture.recorder.AtomicFileMotionTraceOutput
import io.github.mtatsuto.motiongesture.recorder.DroppedSampleReason
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracy
import io.github.mtatsuto.motiongesture.recorder.MotionAccuracyLevel
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityAvailability
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityChange
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityRequirement
import io.github.mtatsuto.motiongesture.recorder.MotionDisplayRotationChange
import io.github.mtatsuto.motiongesture.recorder.MotionPlatformFamily
import io.github.mtatsuto.motiongesture.recorder.MotionSample
import io.github.mtatsuto.motiongesture.recorder.MotionSignals
import io.github.mtatsuto.motiongesture.recorder.MotionTraceFinalizationStatus
import io.github.mtatsuto.motiongesture.recorder.MotionTraceMetadata
import io.github.mtatsuto.motiongesture.recorder.MotionTraceOutput
import io.github.mtatsuto.motiongesture.recorder.MotionTraceProducer
import io.github.mtatsuto.motiongesture.recorder.MotionTraceRecorder
import io.github.mtatsuto.motiongesture.recorder.MotionTraceRecorderException
import io.github.mtatsuto.motiongesture.recorder.MotionTraceRecorderLimits
import io.github.mtatsuto.motiongesture.recorder.MotionTraceRecordingResult
import io.github.mtatsuto.motiongesture.recorder.MotionTraceSession
import io.github.mtatsuto.motiongesture.recorder.MotionTraceV1
import io.github.mtatsuto.motiongesture.recorder.MotionVectorObservation
import java.nio.file.Path

/** Converts independent Android sensor callbacks into one bounded, transport-free Motion Trace. */
class AndroidSensorTraceRecorder(
    private val trace: AndroidSensorTraceContext,
    private val configuration: AndroidSensorRecorderConfiguration,
    limits: MotionTraceRecorderLimits,
    output: MotionTraceOutput,
    private val driver: AndroidSensorDriver,
) {
    companion object {
        const val ADAPTER_NAME = "androidSensors"
        const val ADAPTER_VERSION = "0.1.0"
    }

    val metadata: MotionTraceMetadata

    /** Candidate descriptors filtered by the trace privacy tier; never serialized into the trace. */
    val sensorSnapshot: List<AndroidSensorDescriptor>

    private val lock = Any()
    private val selection: AndroidSensorSelection
    private val recorder: MotionTraceRecorder
    private val currentAvailability = mutableMapOf<String, MotionCapabilityAvailability>()
    private val currentAccuracy = mutableMapOf<String, MotionAccuracy>()
    private val pendingAccuracy = mutableMapOf<AndroidSensorType, PendingAccuracy>()
    private val lastAcceptedTimestampByType = mutableMapOf<AndroidSensorType, Long>()

    @Volatile
    var state: AndroidSensorRecorderAdapterState = AndroidSensorRecorderAdapterState.IDLE
        private set

    @Volatile
    var terminalError: AndroidSensorRecorderAdapterException? = null
        private set

    val result: MotionTraceRecordingResult? get() = recorder.result

    private var originElapsedRealtimeNs: Long? = null
    private var lastTimelineTimestampNs: Long = 0
    private var lastCommittedTimestampNs: Long = 0
    private var nextSampleSequence: Long = 0
    private var nextDisplayRotationChangeSequence: Long = 0
    private var nextCapabilityChangeSequence: Long = 0
    private var currentDisplayRotation = configuration.initialDisplayRotation
    private var pendingDisplayRotation: PendingDisplayRotation? = null

    init {
        if (trace.device?.platformFamily?.let { it != MotionPlatformFamily.ANDROID } == true) {
            throw adapterError(
                AndroidSensorRecorderAdapterErrorCode.INVALID_CONFIGURATION,
                "Android sensor trace device metadata must use the Android platform family",
            )
        }
        selection = try {
            AndroidSensorCapabilities.select(trace.traceId, trace.privacy, configuration, driver)
        } catch (error: AndroidSensorRecorderAdapterException) {
            throw error
        } catch (error: Throwable) {
            throw adapterError(
                AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                "sensor capability discovery failed",
                error,
            )
        }
        sensorSnapshot = selection.sensorSnapshot
        metadata = MotionTraceMetadata(
            traceId = trace.traceId,
            producer = MotionTraceProducer(
                libraryName = trace.libraryName,
                libraryVersion = trace.libraryVersion,
                platformAdapterName = ADAPTER_NAME,
                platformAdapterVersion = ADAPTER_VERSION,
            ),
            privacy = trace.privacy,
            session = MotionTraceSession(
                displayRotationClockwiseAtStart = configuration.initialDisplayRotation.clockwiseDegrees,
                gestureFrameFromDeviceRowMajor = configuration.gestureFrameFromDeviceRowMajor
                    ?: AndroidSensorTransforms.gestureFrameFromDevice(configuration.initialDisplayRotation),
                attitudeReference = selection.attitudeReference,
            ),
            capabilities = selection.capabilities,
            detectors = trace.detectors,
            device = trace.device,
        )
        metadata.capabilities.forEach { capability ->
            currentAvailability[capability.capabilityId] = capability.availability
            currentAccuracy[capability.capabilityId] = capability.initialAccuracy
                ?: MotionAccuracy(MotionAccuracyLevel.UNKNOWN)
        }
        recorder = MotionTraceRecorder(metadata, limits, output)
    }

    constructor(
        context: Context,
        trace: AndroidSensorTraceContext,
        configuration: AndroidSensorRecorderConfiguration,
        limits: MotionTraceRecorderLimits,
        destinationPath: Path,
    ) : this(
        trace = trace,
        configuration = configuration,
        limits = limits,
        output = AtomicFileMotionTraceOutput(destinationPath),
        driver = SensorManagerAndroidSensorDriver(context),
    )

    fun start() {
        synchronized(lock) {
            if (state != AndroidSensorRecorderAdapterState.IDLE) invalidState("start")
            val missing = metadata.capabilities.filter {
                it.requirement == MotionCapabilityRequirement.REQUIRED &&
                    it.availability != MotionCapabilityAvailability.AVAILABLE
            }
            if (missing.isNotEmpty()) {
                val error = adapterError(
                    AndroidSensorRecorderAdapterErrorCode.REQUIRED_CAPABILITY_UNAVAILABLE,
                    "required capabilities are unavailable: ${missing.joinToString { it.capabilityId }}",
                )
                state = AndroidSensorRecorderAdapterState.FAILED
                terminalError = error
                throw error
            }
            state = AndroidSensorRecorderAdapterState.STARTING
        }

        try {
            recorder.start()
        } catch (error: Throwable) {
            val adapterError = recorderError(error)
            synchronized(lock) {
                state = AndroidSensorRecorderAdapterState.FAILED
                terminalError = adapterError
            }
            throw adapterError
        }

        try {
            val origin = driver.elapsedRealtimeNanos()
            synchronized(lock) { originElapsedRealtimeNs = origin }
            driver.start(
                sensorTypes = selection.selectedByType.keys,
                requestedSamplingPeriodUs = configuration.requestedSamplingPeriodUs,
                listener = ::receive,
            )
        } catch (error: Throwable) {
            val failure = adapterError(
                AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                "failed to start Android sensor delivery",
                error,
            )
            synchronized(lock) {
                if (state == AndroidSensorRecorderAdapterState.STARTING) {
                    failSourceLocked(failure, "androidSensors.driverFailure")
                }
            }
            driver.stop()
            throw terminalError ?: failure
        }

        val terminalDuringStart = synchronized(lock) {
            if (state == AndroidSensorRecorderAdapterState.STARTING) {
                state = AndroidSensorRecorderAdapterState.RUNNING
                false
            } else {
                true
            }
        }
        if (terminalDuringStart) {
            driver.stop()
            terminalError?.let { throw it }
        }
    }

    fun updateDisplayRotation(rotation: AndroidDisplayRotation) {
        synchronized(lock) {
            if (state != AndroidSensorRecorderAdapterState.RUNNING) {
                invalidState("update display rotation")
            }
            pendingDisplayRotation = if (rotation == currentDisplayRotation) {
                null
            } else {
                val effectiveElapsedRealtimeNs = try {
                    driver.elapsedRealtimeNanos().also { timestamp ->
                        AndroidSensorTransforms.timestampNs(
                            timestamp,
                            requireNotNull(originElapsedRealtimeNs),
                        )
                    }
                } catch (error: Throwable) {
                    throw adapterError(
                        AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                        "could not timestamp the display rotation update",
                        error,
                    )
                }
                PendingDisplayRotation(rotation, effectiveElapsedRealtimeNs)
            }
        }
    }

    fun finish(): MotionTraceRecordingResult = finalize(cancelled = false)

    fun cancel(): MotionTraceRecordingResult = finalize(cancelled = true)

    private fun receive(event: AndroidSensorDriverEvent) {
        val stopDriver = synchronized(lock) {
            if (state != AndroidSensorRecorderAdapterState.STARTING &&
                state != AndroidSensorRecorderAdapterState.RUNNING
            ) return@synchronized false
            val wasStarting = state == AndroidSensorRecorderAdapterState.STARTING
            try {
                when (event) {
                    is AndroidSensorDriverEvent.Observation -> receiveObservationLocked(event.observation)
                    is AndroidSensorDriverEvent.AccuracyChanged -> receiveAccuracyLocked(event)
                    is AndroidSensorDriverEvent.Unavailable -> receiveUnavailableLocked(event)
                    is AndroidSensorDriverEvent.Failure -> failSourceLocked(
                        adapterError(
                            AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                            "${event.failure.code}: ${event.failure.diagnostic}",
                            event.failure.cause,
                        ),
                        "androidSensors.driverFailure",
                    )
                }
            } catch (error: Throwable) {
                handleRecorderFailureLocked(error)
            }
            !wasStarting && state.isTerminal
        }
        if (stopDriver) driver.stop()
    }

    private fun receiveObservationLocked(observation: AndroidRawSensorObservation) {
        val reservedSequence = reserveSampleSequenceLocked() ?: return
        val timestampNs = try {
            AndroidSensorTransforms.timestampNs(
                observation.timestampElapsedRealtimeNs,
                requireNotNull(originElapsedRealtimeNs),
            )
        } catch (error: AndroidSensorTransformException) {
            reportDropLocked(
                if (error.transformError == AndroidSensorTransformError.NON_MONOTONIC_TIMESTAMP) {
                    DroppedSampleReason.NON_MONOTONIC_TIMESTAMP
                } else {
                    DroppedSampleReason.MALFORMED
                },
            )
            return
        }
        lastTimelineTimestampNs = maxOf(lastTimelineTimestampNs, timestampNs)
        val signal = selection.selectedByType[observation.sensorType]
        if (signal == null) {
            reportDropLocked(DroppedSampleReason.UNSUPPORTED)
            return
        }
        val capabilityId = AndroidSensorCapabilities.capabilityId(signal)
        if (currentAvailability[capabilityId] != MotionCapabilityAvailability.AVAILABLE) {
            reportDropLocked(DroppedSampleReason.UNSUPPORTED)
            return
        }
        if (timestampNs < lastCommittedTimestampNs ||
            lastAcceptedTimestampByType[observation.sensorType] == timestampNs
        ) {
            reportDropLocked(DroppedSampleReason.NON_MONOTONIC_TIMESTAMP)
            return
        }

        val pending = pendingAccuracy[observation.sensorType]
        val accuracy = if (pending != null &&
            observation.timestampElapsedRealtimeNs >= pending.effectiveElapsedRealtimeNs
        ) {
            pendingAccuracy.remove(observation.sensorType)
            pending.accuracy
        } else {
            AndroidSensorTransforms.accuracy(observation.nativeAccuracy)
        }
        val signals = try {
            signals(observation, signal, capabilityId, accuracy)
        } catch (_: AndroidSensorTransformException) {
            reportDropLocked(DroppedSampleReason.MALFORMED)
            return
        }

        if (currentAccuracy[capabilityId] != accuracy) {
            if (!appendCapabilityChangeLocked(
                    timestampNs = timestampNs,
                    capabilityId = capabilityId,
                    accuracy = accuracy,
                )
            ) return
        }
        val pendingRotation = pendingDisplayRotation
        if (pendingRotation != null &&
            observation.timestampElapsedRealtimeNs >= pendingRotation.effectiveElapsedRealtimeNs &&
            !appendDisplayRotationChangeLocked(timestampNs, pendingRotation)
        ) {
            return
        }

        val outcome = recorder.append(
            MotionSample(
                timestampNs = timestampNs,
                sequence = reservedSequence,
                signals = signals,
            ),
        )
        if (outcome.accepted) {
            lastAcceptedTimestampByType[observation.sensorType] = timestampNs
            lastCommittedTimestampNs = maxOf(lastCommittedTimestampNs, timestampNs)
        }
        outcome.recordingResult?.let(::transitionToTerminalLocked)
    }

    private fun receiveAccuracyLocked(event: AndroidSensorDriverEvent.AccuracyChanged) {
        if (event.sensorType !in selection.selectedByType) return
        pendingAccuracy[event.sensorType] = PendingAccuracy(
            AndroidSensorTransforms.accuracy(event.nativeAccuracy),
            event.callbackElapsedRealtimeNs,
        )
    }

    private fun receiveUnavailableLocked(event: AndroidSensorDriverEvent.Unavailable) {
        val signal = selection.selectedByType[event.sensorType] ?: return
        val capabilityId = AndroidSensorCapabilities.capabilityId(signal)
        if (currentAvailability[capabilityId] == MotionCapabilityAvailability.UNAVAILABLE) return
        val timestampNs = try {
            AndroidSensorTransforms.timestampNs(
                event.callbackElapsedRealtimeNs,
                requireNotNull(originElapsedRealtimeNs),
            )
        } catch (error: AndroidSensorTransformException) {
            failSourceLocked(
                adapterError(
                    AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                    "sensor unavailability timestamp could not be represented",
                    error,
                ),
                "androidSensors.driverFailure",
            )
            return
        }
        lastTimelineTimestampNs = maxOf(lastTimelineTimestampNs, timestampNs)
        if (timestampNs < lastCommittedTimestampNs) {
            failSourceLocked(
                adapterError(
                    AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                    "sensor unavailability arrived behind the committed timeline",
                ),
                "androidSensors.driverFailure",
            )
            return
        }
        if (!appendCapabilityChangeLocked(
                timestampNs = timestampNs,
                capabilityId = capabilityId,
                availability = MotionCapabilityAvailability.UNAVAILABLE,
            )
        ) return
        if (signal in configuration.requiredSignals) {
            failSourceLocked(
                adapterError(
                    AndroidSensorRecorderAdapterErrorCode.REQUIRED_CAPABILITY_UNAVAILABLE,
                    "required capability became unavailable: $capabilityId",
                ),
                "androidSensors.requiredCapabilityUnavailable",
            )
        }
    }

    private fun signals(
        observation: AndroidRawSensorObservation,
        signal: AndroidSensorSignal,
        capabilityId: String,
        accuracy: MotionAccuracy,
    ): MotionSignals {
        val vector by lazy { AndroidSensorTransforms.vector(observation.sensorType, observation.values) }
        return when (signal) {
            AndroidSensorSignal.GRAVITY -> MotionSignals(
                gravity = MotionVectorObservation(capabilityId, vector, accuracy),
            )
            AndroidSensorSignal.USER_ACCELERATION -> MotionSignals(
                userAcceleration = MotionVectorObservation(capabilityId, vector, accuracy),
            )
            AndroidSensorSignal.ROTATION_RATE -> MotionSignals(
                rotationRate = MotionVectorObservation(capabilityId, vector, accuracy),
            )
            AndroidSensorSignal.ATTITUDE -> MotionSignals(
                attitude = AndroidSensorTransforms.attitude(
                    capabilityId,
                    observation.attitudeQuaternionWxyz,
                    observation.attitudeRotationMatrixRowMajor,
                    accuracy,
                ),
            )
        }
    }

    private fun appendDisplayRotationChangeLocked(
        timestampNs: Long,
        pending: PendingDisplayRotation,
    ): Boolean {
        if (nextDisplayRotationChangeSequence > MotionTraceV1.MAXIMUM_SAFE_INTEGER) {
            failSourceLocked(
                adapterError(
                    AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                    "display rotation change sequence exceeded the wire-safe range",
                ),
                "androidSensors.sequenceOverflow",
            )
            return false
        }
        val outcome = recorder.append(
            MotionDisplayRotationChange(
                timestampNs = timestampNs,
                changeSequence = nextDisplayRotationChangeSequence,
                displayRotationClockwise = pending.rotation.clockwiseDegrees,
            ),
        )
        if (outcome.accepted) {
            nextDisplayRotationChangeSequence += 1
            currentDisplayRotation = pending.rotation
            if (pendingDisplayRotation == pending) pendingDisplayRotation = null
            lastCommittedTimestampNs = maxOf(lastCommittedTimestampNs, timestampNs)
        }
        outcome.recordingResult?.let(::transitionToTerminalLocked)
        return outcome.accepted && outcome.recordingResult == null
    }

    private fun appendCapabilityChangeLocked(
        timestampNs: Long,
        capabilityId: String,
        availability: MotionCapabilityAvailability? = null,
        accuracy: MotionAccuracy? = null,
    ): Boolean {
        if (nextCapabilityChangeSequence > MotionTraceV1.MAXIMUM_SAFE_INTEGER) {
            failSourceLocked(
                adapterError(
                    AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                    "capability change sequence exceeded the wire-safe range",
                ),
                "androidSensors.sequenceOverflow",
            )
            return false
        }
        val outcome = recorder.append(
            MotionCapabilityChange(
                timestampNs = timestampNs,
                changeSequence = nextCapabilityChangeSequence,
                capabilityId = capabilityId,
                availability = availability,
                accuracy = accuracy,
            ),
        )
        if (outcome.accepted) {
            nextCapabilityChangeSequence += 1
            availability?.let { currentAvailability[capabilityId] = it }
            accuracy?.let { currentAccuracy[capabilityId] = it }
            lastCommittedTimestampNs = maxOf(lastCommittedTimestampNs, timestampNs)
        }
        outcome.recordingResult?.let(::transitionToTerminalLocked)
        return outcome.accepted && outcome.recordingResult == null
    }

    private fun reserveSampleSequenceLocked(): Long? {
        if (nextSampleSequence > MotionTraceV1.MAXIMUM_SAFE_INTEGER) {
            failSourceLocked(
                adapterError(
                    AndroidSensorRecorderAdapterErrorCode.DRIVER_FAILURE,
                    "sample sequence exceeded the wire-safe range",
                ),
                "androidSensors.sequenceOverflow",
            )
            return null
        }
        return nextSampleSequence++
    }

    private fun reportDropLocked(reason: DroppedSampleReason) {
        recorder.reportDroppedSample(reason)
    }

    private fun failSourceLocked(
        error: AndroidSensorRecorderAdapterException,
        failureCode: String,
    ) {
        if (state.isTerminal) return
        try {
            recorder.failSource(failureCode, lastTimelineTimestampNs)
        } catch (recorderFailure: Throwable) {
            state = AndroidSensorRecorderAdapterState.FAILED
            terminalError = recorderError(recorderFailure)
            return
        }
        state = AndroidSensorRecorderAdapterState.FAILED
        terminalError = error
    }

    private fun handleRecorderFailureLocked(error: Throwable): AndroidSensorRecorderAdapterException {
        val failure = recorderError(error)
        state = AndroidSensorRecorderAdapterState.FAILED
        terminalError = failure
        return failure
    }

    private fun transitionToTerminalLocked(result: MotionTraceRecordingResult) {
        state = when (result.footer.finalizationStatus) {
            MotionTraceFinalizationStatus.COMPLETE,
            MotionTraceFinalizationStatus.BOUNDED,
            -> AndroidSensorRecorderAdapterState.FINISHED
            MotionTraceFinalizationStatus.CANCELLED -> AndroidSensorRecorderAdapterState.CANCELLED
            MotionTraceFinalizationStatus.FAILED -> AndroidSensorRecorderAdapterState.FAILED
        }
    }

    private fun finalize(cancelled: Boolean): MotionTraceRecordingResult {
        synchronized(lock) {
            recorder.result?.let { existing ->
                terminalError?.let { throw it }
                return existing
            }
            terminalError?.let { throw it }
            if (state != AndroidSensorRecorderAdapterState.RUNNING) {
                invalidState(if (cancelled) "cancel" else "finish")
            }
            state = AndroidSensorRecorderAdapterState.FINALIZING
        }
        driver.stop()
        return synchronized(lock) {
            try {
                val completed = if (cancelled) {
                    recorder.cancel(lastTimelineTimestampNs)
                } else {
                    recorder.finish(lastTimelineTimestampNs)
                }
                transitionToTerminalLocked(completed)
                completed
            } catch (error: Throwable) {
                throw handleRecorderFailureLocked(error)
            }
        }
    }

    private fun invalidState(operation: String): Nothing = throw adapterError(
        AndroidSensorRecorderAdapterErrorCode.INVALID_STATE,
        "cannot $operation while adapter is ${state.name.lowercase()}",
    )

    private fun recorderError(error: Throwable): AndroidSensorRecorderAdapterException = adapterError(
        AndroidSensorRecorderAdapterErrorCode.RECORDER_FAILURE,
        (error as? MotionTraceRecorderException)?.diagnostic ?: "motion trace recorder failed",
        error,
    )

    private fun adapterError(
        code: AndroidSensorRecorderAdapterErrorCode,
        diagnostic: String,
        cause: Throwable? = null,
    ) = AndroidSensorRecorderAdapterException(code, diagnostic, cause)
}

private val AndroidSensorRecorderAdapterState.isTerminal: Boolean
    get() = this == AndroidSensorRecorderAdapterState.FINISHED ||
        this == AndroidSensorRecorderAdapterState.CANCELLED ||
        this == AndroidSensorRecorderAdapterState.FAILED

private data class PendingAccuracy(
    val accuracy: MotionAccuracy,
    val effectiveElapsedRealtimeNs: Long,
)

private data class PendingDisplayRotation(
    val rotation: AndroidDisplayRotation,
    val effectiveElapsedRealtimeNs: Long,
)
