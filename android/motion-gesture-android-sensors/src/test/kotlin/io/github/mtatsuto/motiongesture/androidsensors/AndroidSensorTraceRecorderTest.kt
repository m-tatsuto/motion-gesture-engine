package io.github.mtatsuto.motiongesture.androidsensors

import io.github.mtatsuto.motiongesture.recorder.AttitudeReferenceKind
import io.github.mtatsuto.motiongesture.recorder.DroppedSampleReason
import io.github.mtatsuto.motiongesture.recorder.MotionBiasCorrection
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityAvailability
import io.github.mtatsuto.motiongesture.recorder.MotionTraceDataClass
import io.github.mtatsuto.motiongesture.recorder.MotionTraceOutput
import io.github.mtatsuto.motiongesture.recorder.MotionTracePrivacy
import io.github.mtatsuto.motiongesture.recorder.MotionTracePrivacyTier
import io.github.mtatsuto.motiongesture.recorder.MotionTraceRecorderLimits
import io.github.mtatsuto.motiongesture.recorder.MotionTraceWriteDisposition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class AndroidSensorTraceRecorderTest {
    @Test
    fun gravityTraceSeparatesRequestedAndObservedTimingAndWritesDisplayChange() {
        val origin = 9_000_000_000L
        val driver = FakeAndroidSensorDriver(
            origin,
            descriptors(AndroidSensorType.GRAVITY),
        )
        val output = MemoryTraceOutput()
        val adapter = adapter(
            driver = driver,
            output = output,
            configuration = configuration(
                enabled = setOf(AndroidSensorSignal.GRAVITY),
                required = setOf(AndroidSensorSignal.GRAVITY),
                requestedSamplingPeriodUs = 20_000,
            ),
        )

        adapter.start()
        driver.advanceClockTo(origin + 260_000_000)
        adapter.updateDisplayRotation(AndroidDisplayRotation.DEGREES_90)
        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 250_000_000, listOf(0.0, 5.883990, -7.845320)))
        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 280_000_000, listOf(0.0, 5.883990, -7.845320)))
        val result = adapter.finish()
        System.getenv("MGE_ANDROID_TRACE_OUTPUT")?.let { exportPath ->
            Files.write(Path.of(exportPath), output.data.toByteArray())
        }

        assertEquals(20_000, driver.requestedSamplingPeriodUs)
        assertEquals(2, result.footer.recordCounts.samples)
        val observed = result.footer.observedTiming.single()
        assertEquals(30_000_000, observed.minimumIntervalNs)
        assertEquals(30_000_000, observed.maximumIntervalNs)

        val records = records(output)
        val header = records.first()
        assertEquals(
            20_000_000,
            header["capabilities"]!!.jsonArray.single().jsonObject["requestedTiming"]!!
                .jsonObject["intervalNs"]!!.jsonPrimitive.long,
        )
        val samples = records.filter { it.recordType == "sample" }
        val firstSample = samples.first()
        val gravity = firstSample["signals"]!!.jsonObject["gravity"]!!.jsonObject["value"]!!.jsonArray
        assertEquals(0.0, gravity[0].jsonPrimitive.double, absoluteTolerance = 0.000_000_1)
        assertEquals(-0.6, gravity[1].jsonPrimitive.double, absoluteTolerance = 0.000_000_1)
        assertEquals(0.8, gravity[2].jsonPrimitive.double, absoluteTolerance = 0.000_000_1)
        val displayChangeIndex = records.indexOfFirst { it.recordType == "displayRotationChange" }
        assertTrue(displayChangeIndex > records.indexOf(samples.first()))
        assertTrue(displayChangeIndex < records.indexOf(samples.last()))
        assertEquals(1, result.footer.recordCounts.displayRotationChanges)
    }

    @Test
    fun duplicateAndLateCallbacksAreRejectedButEqualCrossSourceTimestampsAreAccepted() {
        val origin = 1_000L
        val driver = FakeAndroidSensorDriver(
            origin,
            descriptors(
                AndroidSensorType.GRAVITY,
                AndroidSensorType.LINEAR_ACCELERATION,
                AndroidSensorType.GYROSCOPE,
            ),
        )
        val output = MemoryTraceOutput()
        val adapter = adapter(
            driver,
            output,
            configuration(
                enabled = setOf(
                    AndroidSensorSignal.GRAVITY,
                    AndroidSensorSignal.USER_ACCELERATION,
                    AndroidSensorSignal.ROTATION_RATE,
                ),
                required = setOf(AndroidSensorSignal.GRAVITY),
            ),
        )
        adapter.start()

        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 100, listOf(0.0, 0.0, 9.80665)))
        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 100, listOf(0.0, 0.0, 9.80665)))
        driver.emit(observation(AndroidSensorType.LINEAR_ACCELERATION, origin + 100, listOf(0.980665, 0.0, 0.0)))
        driver.emit(observation(AndroidSensorType.GYROSCOPE, origin + 90, listOf(0.2, 0.0, 0.0)))
        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 110, listOf(0.0, 0.0, 9.80665)))
        val result = adapter.finish()

        assertEquals(3, result.footer.recordCounts.samples)
        assertEquals(2, result.footer.droppedSamples.total)
        assertEquals(
            2,
            result.footer.droppedSamples.byReason.single {
                it.reason == DroppedSampleReason.NON_MONOTONIC_TIMESTAMP
            }.count,
        )
        assertEquals(
            listOf(0L, 2L, 4L),
            records(output).filter { it.recordType == "sample" }
                .map { it["sequence"]!!.jsonPrimitive.long },
        )
    }

    @Test
    fun accuracyTransitionsAndOptionalUnavailabilityAreExplicit() {
        val origin = 10_000L
        val driver = FakeAndroidSensorDriver(
            origin,
            descriptors(AndroidSensorType.GRAVITY, AndroidSensorType.GYROSCOPE),
        )
        val output = MemoryTraceOutput()
        val adapter = adapter(
            driver,
            output,
            configuration(
                enabled = setOf(AndroidSensorSignal.GRAVITY, AndroidSensorSignal.ROTATION_RATE),
                required = setOf(AndroidSensorSignal.GRAVITY),
            ),
        )
        adapter.start()

        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 10, listOf(0.0, 0.0, 9.80665), accuracy = 3))
        driver.emit(
            AndroidSensorDriverEvent.AccuracyChanged(
                AndroidSensorType.GRAVITY,
                nativeAccuracy = 1,
                callbackElapsedRealtimeNs = origin + 25,
            ),
        )
        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 20, listOf(0.0, 0.0, 9.80665), accuracy = 3))
        driver.emit(observation(AndroidSensorType.GRAVITY, origin + 30, listOf(0.0, 0.0, 9.80665), accuracy = 3))
        driver.emit(AndroidSensorDriverEvent.Unavailable(AndroidSensorType.GYROSCOPE, origin + 35))
        driver.emit(observation(AndroidSensorType.GYROSCOPE, origin + 40, listOf(0.2, 0.0, 0.0), accuracy = 3))
        val result = adapter.finish()

        assertEquals(AndroidSensorRecorderAdapterState.FINISHED, adapter.state)
        assertEquals(3, result.footer.recordCounts.samples)
        assertEquals(3, result.footer.recordCounts.capabilityChanges)
        assertEquals(1, result.footer.droppedSamples.total)
        assertEquals(DroppedSampleReason.UNSUPPORTED, result.footer.droppedSamples.byReason.single().reason)

        val changes = records(output).filter { it.recordType == "capabilityChange" }
        assertEquals(listOf("high", "low"), changes.take(2).map { change ->
            change["accuracy"]!!.jsonObject["level"]!!.jsonPrimitive.content
        })
        val unavailable = changes.last()
        assertEquals(AndroidSensorCapabilities.ROTATION_RATE_ID, unavailable["capabilityId"]!!.jsonPrimitive.content)
        assertEquals("unavailable", unavailable["availability"]!!.jsonPrimitive.content)
    }

    @Test
    fun independentCallbacksRemainIndependentPartialSamples() {
        val origin = 100_000L
        val driver = FakeAndroidSensorDriver(
            origin,
            descriptors(
                AndroidSensorType.GRAVITY,
                AndroidSensorType.LINEAR_ACCELERATION,
                AndroidSensorType.GYROSCOPE,
                AndroidSensorType.GAME_ROTATION_VECTOR,
            ),
        )
        val output = MemoryTraceOutput()
        val adapter = adapter(
            driver,
            output,
            configuration(
                enabled = AndroidSensorSignal.entries.toSet(),
                required = setOf(AndroidSensorSignal.GRAVITY),
                localReferenceId = "20000000-0000-4000-8000-000000000001",
            ),
        )
        adapter.start()
        val timestamp = origin + 100
        driver.emit(observation(AndroidSensorType.GRAVITY, timestamp, listOf(0.0, 0.0, 9.80665)))
        driver.emit(observation(AndroidSensorType.LINEAR_ACCELERATION, timestamp, listOf(0.980665, 0.0, 0.0)))
        driver.emit(observation(AndroidSensorType.GYROSCOPE, timestamp, listOf(0.2, 0.0, 0.0)))
        val half = sqrt(0.5)
        driver.emit(
            observation(
                AndroidSensorType.GAME_ROTATION_VECTOR,
                timestamp,
                values = listOf(half, 0.0, 0.0, half),
                quaternionWxyz = listOf(half, half, 0.0, 0.0),
                rotationMatrix = listOf(
                    1.0, 0.0, 0.0,
                    0.0, 0.0, -1.0,
                    0.0, 1.0, 0.0,
                ),
            ),
        )
        val result = adapter.finish()

        assertEquals(4, result.footer.recordCounts.samples)
        val samples = records(output).filter { it.recordType == "sample" }
        assertEquals(listOf(1, 1, 1, 1), samples.map { it["signals"]!!.jsonObject.size })
        assertEquals(
            listOf("gravity", "userAcceleration", "rotationRate", "attitude"),
            samples.map { it["signals"]!!.jsonObject.keys.single() },
        )
    }

    @Test
    fun fallbackSelectionIsDeclaredAndPrivateSensorIdentityIsNotPutInPublicSnapshots() {
        val descriptors = descriptors(
            AndroidSensorType.GRAVITY,
            AndroidSensorType.GYROSCOPE_UNCALIBRATED,
            AndroidSensorType.ROTATION_VECTOR,
        )
        val privateAdapter = adapter(
            FakeAndroidSensorDriver(0, descriptors),
            MemoryTraceOutput(),
            configuration(
                enabled = setOf(AndroidSensorSignal.ROTATION_RATE, AndroidSensorSignal.ATTITUDE),
                required = emptySet(),
            ),
            privacyTier = MotionTracePrivacyTier.PRIVATE_SENSITIVE,
        )
        val rotation = privateAdapter.metadata.capabilities.first {
            it.capabilityId == AndroidSensorCapabilities.ROTATION_RATE_ID
        }
        val attitude = privateAdapter.metadata.capabilities.first {
            it.capabilityId == AndroidSensorCapabilities.ATTITUDE_ID
        }

        assertEquals(AndroidSensorType.GYROSCOPE_UNCALIBRATED.nativeSourceIdentifier, rotation.nativeSourceIdentifier)
        assertEquals(MotionBiasCorrection.RAW, rotation.biasCorrection)
        assertEquals(AndroidSensorType.ROTATION_VECTOR.nativeSourceIdentifier, attitude.nativeSourceIdentifier)
        assertEquals(AttitudeReferenceKind.EAST_NORTH_UP_MAGNETIC, privateAdapter.metadata.session.attitudeReference?.kind)
        assertNotNull(privateAdapter.sensorSnapshot.first { it.availability == MotionCapabilityAvailability.AVAILABLE }.vendor)

        val publicAdapter = adapter(
            FakeAndroidSensorDriver(0, descriptors),
            MemoryTraceOutput(),
            configuration(
                enabled = setOf(AndroidSensorSignal.ROTATION_RATE),
                required = emptySet(),
            ),
        )
        assertTrue(publicAdapter.sensorSnapshot.all { it.vendor == null && it.version == null })
    }

    @Test
    fun missingRequiredSensorFailsBeforeStartingTheTrace() {
        val driver = FakeAndroidSensorDriver(originNs = 0, descriptors = emptyMap())
        val output = MemoryTraceOutput()
        val adapter = adapter(
            driver,
            output,
            configuration(
                enabled = setOf(AndroidSensorSignal.GRAVITY),
                required = setOf(AndroidSensorSignal.GRAVITY),
            ),
        )

        val error = assertFailsWith<AndroidSensorRecorderAdapterException> { adapter.start() }

        assertEquals(AndroidSensorRecorderAdapterErrorCode.REQUIRED_CAPABILITY_UNAVAILABLE, error.code)
        assertEquals(AndroidSensorRecorderAdapterState.FAILED, adapter.state)
        assertFalse(driver.started)
        assertTrue(output.data.isEmpty())
    }

    @Test
    fun requiredSensorLossWritesAvailabilityChangeAndFinalizesFailed() {
        val origin = 5_000L
        val driver = FakeAndroidSensorDriver(origin, descriptors(AndroidSensorType.GRAVITY))
        val output = MemoryTraceOutput()
        val adapter = adapter(
            driver,
            output,
            configuration(
                enabled = setOf(AndroidSensorSignal.GRAVITY),
                required = setOf(AndroidSensorSignal.GRAVITY),
            ),
        )
        adapter.start()

        driver.emit(AndroidSensorDriverEvent.Unavailable(AndroidSensorType.GRAVITY, origin + 10))

        assertEquals(AndroidSensorRecorderAdapterState.FAILED, adapter.state)
        assertEquals(
            AndroidSensorRecorderAdapterErrorCode.REQUIRED_CAPABILITY_UNAVAILABLE,
            adapter.terminalError?.code,
        )
        assertTrue(driver.stopped)
        val result = assertNotNull(adapter.result)
        assertEquals(1, result.footer.recordCounts.capabilityChanges)
        assertEquals("androidSensors.requiredCapabilityUnavailable", result.footer.failureCode)
        val records = records(output)
        val change = records.first { it.recordType == "capabilityChange" }
        assertEquals("unavailable", change["availability"]!!.jsonPrimitive.content)
        assertEquals("traceFooter", records.last().recordType)
    }

    @Test
    fun synchronousStartCallbackCanReachABoundWithoutLeavingTheDriverRunning() {
        val origin = 8_000L
        val driver = FakeAndroidSensorDriver(
            originNs = origin,
            descriptors = descriptors(AndroidSensorType.GRAVITY),
            eventsOnStart = listOf(
                observation(
                    AndroidSensorType.GRAVITY,
                    origin + 10,
                    listOf(0.0, 0.0, 9.80665),
                ),
            ),
        )
        val adapter = adapter(
            driver,
            MemoryTraceOutput(),
            configuration(
                enabled = setOf(AndroidSensorSignal.GRAVITY),
                required = setOf(AndroidSensorSignal.GRAVITY),
            ),
            maximumSamples = 1,
        )

        adapter.start()

        assertEquals(AndroidSensorRecorderAdapterState.FINISHED, adapter.state)
        assertTrue(driver.stopped)
        assertEquals(1, adapter.result?.footer?.recordCounts?.samples)
    }

    private fun adapter(
        driver: FakeAndroidSensorDriver,
        output: MemoryTraceOutput,
        configuration: AndroidSensorRecorderConfiguration,
        privacyTier: MotionTracePrivacyTier = MotionTracePrivacyTier.SYNTHETIC,
        maximumSamples: Long = 1_000,
    ) = AndroidSensorTraceRecorder(
        trace = AndroidSensorTraceContext(
            traceId = "00000000-0000-4000-8000-000000000701",
            libraryName = "motionGestureAndroidTests",
            libraryVersion = "0.1.0",
            privacy = MotionTracePrivacy(
                tier = privacyTier,
                dataClasses = listOf(MotionTraceDataClass.MOTION_SENSOR_DATA),
            ),
        ),
        configuration = configuration,
        limits = MotionTraceRecorderLimits(
            maximumDurationNs = 10_000_000_000,
            maximumSamples = maximumSamples,
            maximumBytes = 262_144,
        ),
        output = output,
        driver = driver,
    )

    private fun configuration(
        enabled: Set<AndroidSensorSignal>,
        required: Set<AndroidSensorSignal>,
        requestedSamplingPeriodUs: Int = 20_000,
        localReferenceId: String? = null,
    ) = AndroidSensorRecorderConfiguration(
        requestedSamplingPeriodUs = requestedSamplingPeriodUs,
        initialDisplayRotation = AndroidDisplayRotation.DEGREES_0,
        enabledSignals = enabled,
        requiredSignals = required,
        localAttitudeReferenceInstanceId = localReferenceId,
    )

    private fun descriptors(vararg available: AndroidSensorType): Map<AndroidSensorType, AndroidSensorDescriptor> =
        available.associateWith { sensorType ->
            AndroidSensorDescriptor(
                sensorType = sensorType,
                availability = MotionCapabilityAvailability.AVAILABLE,
                minimumDelayUs = 5_000,
                maximumDelayUs = 1_000_000,
                resolution = 0.001,
                vendor = "Test Vendor",
                version = 7,
            )
        }

    private fun observation(
        sensorType: AndroidSensorType,
        timestamp: Long,
        values: List<Double>,
        accuracy: Int = 3,
        quaternionWxyz: List<Double>? = null,
        rotationMatrix: List<Double>? = null,
    ) = AndroidSensorDriverEvent.Observation(
        AndroidRawSensorObservation(
            sensorType = sensorType,
            timestampElapsedRealtimeNs = timestamp,
            values = values,
            nativeAccuracy = accuracy,
            attitudeQuaternionWxyz = quaternionWxyz,
            attitudeRotationMatrixRowMajor = rotationMatrix,
        ),
    )

    private fun records(output: MemoryTraceOutput): List<JsonObject> = output.data.toByteArray()
        .toString(Charsets.UTF_8)
        .lineSequence()
        .filter(String::isNotEmpty)
        .map { Json.parseToJsonElement(it).jsonObject }
        .toList()
}

private val JsonObject.recordType: String
    get() = get("recordType")!!.jsonPrimitive.content

private class FakeAndroidSensorDriver(
    private val originNs: Long,
    private val descriptors: Map<AndroidSensorType, AndroidSensorDescriptor>,
    private val eventsOnStart: List<AndroidSensorDriverEvent> = emptyList(),
) : AndroidSensorDriver {
    private var currentTimeNs = originNs
    private var listener: ((AndroidSensorDriverEvent) -> Unit)? = null
    var started = false
        private set
    var stopped = false
        private set
    var requestedSamplingPeriodUs: Int? = null
        private set

    override fun descriptor(sensorType: AndroidSensorType): AndroidSensorDescriptor =
        descriptors[sensorType] ?: AndroidSensorDescriptor(
            sensorType = sensorType,
            availability = MotionCapabilityAvailability.UNAVAILABLE,
        )

    override fun elapsedRealtimeNanos(): Long = currentTimeNs

    override fun start(
        sensorTypes: Set<AndroidSensorType>,
        requestedSamplingPeriodUs: Int,
        listener: (AndroidSensorDriverEvent) -> Unit,
    ) {
        started = true
        this.requestedSamplingPeriodUs = requestedSamplingPeriodUs
        this.listener = listener
        eventsOnStart.forEach(listener)
    }

    override fun stop() {
        stopped = true
    }

    fun emit(event: AndroidSensorDriverEvent) {
        check(started && !stopped)
        requireNotNull(listener).invoke(event)
    }

    fun advanceClockTo(timestampNs: Long) {
        require(timestampNs >= currentTimeNs)
        currentTimeNs = timestampNs
    }
}

private class MemoryTraceOutput : MotionTraceOutput {
    override val temporaryPath: Path? = null
    override val destinationPath: Path? = null
    val data = mutableListOf<Byte>()

    override fun start() = Unit

    override fun write(data: ByteArray): MotionTraceWriteDisposition {
        data.forEach(this.data::add)
        return MotionTraceWriteDisposition.WRITTEN
    }

    override fun commit(): Path? = null

    override fun abortPreservingPartial() = Unit
}

private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
