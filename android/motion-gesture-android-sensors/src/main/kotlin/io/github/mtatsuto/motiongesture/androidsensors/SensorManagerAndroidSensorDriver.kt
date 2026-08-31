package io.github.mtatsuto.motiongesture.androidsensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import io.github.mtatsuto.motiongesture.recorder.MotionCapabilityAvailability

/** Activity-free SensorManager driver that serializes callbacks on a private HandlerThread. */
class SensorManagerAndroidSensorDriver(
    private val sensorManager: SensorManager,
    private val monotonicClockNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AndroidSensorDriver {
    constructor(context: Context) : this(
        requireNotNull(
            (context.applicationContext ?: context).getSystemService(Context.SENSOR_SERVICE) as? SensorManager,
        ) { "SensorManager is unavailable" },
    )

    private val lifecycleLock = Any()
    private var handlerThread: HandlerThread? = null
    private var platformListener: SensorEventListener? = null
    private var started = false
    private var stopped = false

    override fun descriptor(sensorType: AndroidSensorType): AndroidSensorDescriptor {
        val sensor = sensorManager.getDefaultSensor(sensorType.platformType)
            ?: return AndroidSensorDescriptor(
                sensorType = sensorType,
                availability = MotionCapabilityAvailability.UNAVAILABLE,
            )
        return AndroidSensorDescriptor(
            sensorType = sensorType,
            availability = MotionCapabilityAvailability.AVAILABLE,
            minimumDelayUs = sensor.minDelay.toLong().takeIf { it >= 0 },
            maximumDelayUs = sensor.maxDelay.toLong().takeIf { it >= 0 },
            resolution = sensor.resolution.toDouble().takeIf(Double::isFinite),
            vendor = sensor.vendor.takeIf(String::isNotBlank),
            version = sensor.version,
        )
    }

    override fun elapsedRealtimeNanos(): Long = monotonicClockNs()

    override fun start(
        sensorTypes: Set<AndroidSensorType>,
        requestedSamplingPeriodUs: Int,
        listener: (AndroidSensorDriverEvent) -> Unit,
    ) {
        synchronized(lifecycleLock) {
            check(!started && !stopped) { "Android sensor driver instances are single-use" }
            started = true
            if (sensorTypes.isEmpty()) return

            val thread = HandlerThread(
                "motion-gesture-android-sensors",
                Process.THREAD_PRIORITY_MORE_FAVORABLE,
            )
            thread.start()
            handlerThread = thread
            val handler = Handler(thread.looper)
            val eventListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val sensorType = AndroidSensorType.fromPlatformType(event.sensor.type) ?: return
                    val values = event.values.map(Float::toDouble)
                    val quaternionWxyz: List<Double>?
                    val rotationMatrix: List<Double>?
                    if (sensorType.signal == AndroidSensorSignal.ATTITUDE) {
                        val converted = rotationArtifacts(event.values)
                        quaternionWxyz = converted?.first
                        rotationMatrix = converted?.second
                    } else {
                        quaternionWxyz = null
                        rotationMatrix = null
                    }
                    listener(
                        AndroidSensorDriverEvent.Observation(
                            AndroidRawSensorObservation(
                                sensorType = sensorType,
                                timestampElapsedRealtimeNs = event.timestamp,
                                values = values,
                                nativeAccuracy = event.accuracy,
                                attitudeQuaternionWxyz = quaternionWxyz,
                                attitudeRotationMatrixRowMajor = rotationMatrix,
                            ),
                        ),
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    val sensorType = sensor?.type?.let(AndroidSensorType::fromPlatformType) ?: return
                    listener(
                        AndroidSensorDriverEvent.AccuracyChanged(
                            sensorType = sensorType,
                            nativeAccuracy = accuracy,
                            callbackElapsedRealtimeNs = monotonicClockNs(),
                        ),
                    )
                }
            }
            platformListener = eventListener

            try {
                sensorTypes.forEach { sensorType ->
                    val sensor = sensorManager.getDefaultSensor(sensorType.platformType)
                    val registered = sensor != null && sensorManager.registerListener(
                        eventListener,
                        sensor,
                        requestedSamplingPeriodUs,
                        handler,
                    )
                    if (!registered) {
                        listener(
                            AndroidSensorDriverEvent.Unavailable(
                                sensorType = sensorType,
                                callbackElapsedRealtimeNs = monotonicClockNs(),
                            ),
                        )
                    }
                }
            } catch (error: Throwable) {
                sensorManager.unregisterListener(eventListener)
                thread.quitSafely()
                platformListener = null
                handlerThread = null
                stopped = true
                throw error
            }
        }
    }

    override fun stop() {
        val listener: SensorEventListener?
        val thread: HandlerThread?
        synchronized(lifecycleLock) {
            if (stopped) return
            stopped = true
            listener = platformListener
            thread = handlerThread
            platformListener = null
            handlerThread = null
        }
        listener?.let(sensorManager::unregisterListener)
        thread?.quitSafely()
    }

    private fun rotationArtifacts(rotationVector: FloatArray): Pair<List<Double>, List<Double>>? = try {
        val quaternion = FloatArray(4)
        val matrix = FloatArray(9)
        SensorManager.getQuaternionFromVector(quaternion, rotationVector)
        SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
        quaternion.map(Float::toDouble) to matrix.map(Float::toDouble)
    } catch (_: RuntimeException) {
        null
    }
}
