@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.vincenthzr.locationspoofer.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.content.Context
import com.vincenthzr.locationspoofer.utils.GaitTemplate
import com.vincenthzr.locationspoofer.utils.GaitAttitude
import com.vincenthzr.locationspoofer.utils.StepCounterClock
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.StepPipelineDiagnostics
import com.vincenthzr.locationspoofer.xposed.utils.RouteEngine
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SensorStepHooker {

    data class CapturedSensorListener(
        val listener: Any,
        val sensor: Sensor?,
        val handler: Handler?
    ) {
        @Volatile internal var frameworkQueue: Any? = null
        internal val schedule = StepEventSchedule()
        internal var lastCounter: Long = Long.MIN_VALUE
        internal var deliveryReported = false
        internal var failureReported = false
        internal var deliveryWindowStart = 0L
        internal var deliveryWindowFirstValue = 0f
        internal var deliveryWindowEvents = 0L
        internal var previousDeliveryTime = 0L
        internal var maxDeliveryGap = 0L
    }

    val capturedListeners = CopyOnWriteArrayList<CapturedSensorListener>()
    private val hookedListenerClasses = ConcurrentHashMap<Class<*>, Boolean>()
    private val syntheticDelivery = ThreadLocal<Boolean>()
    private val hardwareDelivery = ThreadLocal<Boolean>()
    private var pumpThread: HandlerThread? = null
    private var pumpHandler: Handler? = null
    @Volatile private var pumpGeneration = 0L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Keep the previous generation's saved-state slots for hot-reload compatibility.
    // The authoritative count now comes from the published StepCounterClock.
    private const val INITIAL_BOOT_STEPS = 2350.0
    @Volatile private var stepBase: Double = INITIAL_BOOT_STEPS
    @Volatile private var lastStepsFloat: Double = INITIAL_BOOT_STEPS
    @Volatile private var lastStepInitTime: Long = 0L
    private var lastStepSampleTime: Long = 0L

    private val noiseRng = java.util.Random()
    @Volatile private var cachedTemplateSource: String? = null
    @Volatile private var cachedTemplate: GaitTemplate? = null
    @Volatile private var cachedBootCount: Long? = null

    // 缓存虚拟 Sensor 实例
    private var mockStepCounterSensor: Sensor? = null
    private var mockStepDetectorSensor: Sensor? = null
    private var mockAccelerometerSensor: Sensor? = null

    // 跟踪 handle 到 sensor type 的映射
    private val handleToTypeMap = ConcurrentHashMap<Int, Int>()

    fun hookSensorStepSimulation(classLoader: ClassLoader) {
        val targetClasses = listOf(
            "android.hardware.SensorManager",
            "android.hardware.SystemSensorManager"
        )

        for (className in targetClasses) {
            val managerClass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue

            // 1. Hook getDefaultSensor: 当设备无物理计步传感器时注入虚拟 Sensor
            try {
                XposedHelpers.hookAllMethods(managerClass, "getDefaultSensor") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (result == null && (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR)) {
                        return@hookAllMethods getOrCreateMockSensor(type, classLoader)
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 2. Hook getSensorList
            try {
                XposedHelpers.hookAllMethods(managerClass, "getSensorList") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                        if (result is List<*> && result.isEmpty()) {
                            val mock = getOrCreateMockSensor(type, classLoader)
                            if (mock != null) return@hookAllMethods listOf(mock)
                        }
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 3. Hook registerListener / registerListenerImpl
            val regMethods = listOf("registerListener", "registerListenerImpl")
            for (methodName in regMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        var listener: Any? = null
                        var sensor: Sensor? = null
                        var handler: Handler? = null

                        for (arg in args) {
                            if (arg != null) {
                                if (arg is SensorEventListener || LocationHooker.hasTypeByName(arg.javaClass, "android.hardware.SensorEventListener")) {
                                    listener = arg
                                } else if (arg is Sensor) {
                                    sensor = arg
                                } else if (arg is Handler) {
                                    handler = arg
                                }
                            }
                        }

                        val virtual = sensor != null && (sensor === mockStepCounterSensor || sensor === mockStepDetectorSensor)
                        val result = if (virtual && listener != null) true else chain.proceed(chain.args.toTypedArray())
                        if (listener != null && sensor != null) {
                            runCatching { StepPipelineDiagnostics.registration(listener, sensor, result == true) }
                            runCatching { StepPipelineDiagnostics.install(listener.javaClass.classLoader ?: classLoader) }
                        }
                        if (listener != null && result == true) {
                            val targetSensor = sensor
                            if (targetSensor != null && isSimulatedType(targetSensor.type)) {
                                // SystemSensorManager reuses the first queue/Looper for a listener,
                                // even if later sensor registrations supply a different Handler.
                                val resolvedHandler = capturedListeners.firstOrNull {
                                    it.listener === listener
                                }?.handler ?: handler ?: frameworkHandler(chain.thisObject)
                                val queue = frameworkQueue(chain.thisObject, listener)
                                val existing = capturedListeners.firstOrNull {
                                    it.listener === listener && it.sensor?.type == targetSensor.type
                                }
                                val entry = existing ?: CapturedSensorListener(listener, targetSensor, resolvedHandler)
                                if (queue != null) entry.frameworkQueue = queue
                                if (existing == null) {
                                    capturedListeners.add(entry)
                                    if (targetSensor.type == Sensor.TYPE_STEP_COUNTER || targetSensor.type == Sensor.TYPE_STEP_DETECTOR) {
                                        android.util.Log.i("LocationSpoofer", "[SensorStep] Captured: type=${targetSensor.type}, listener=${listener.javaClass.name}, thread=${resolvedHandler?.looper?.thread?.name ?: "main"}")
                                    }
                                }

                                val handle = getSensorHandle(targetSensor)
                                if (handle != null) {
                                    handleToTypeMap[handle] = targetSensor.type
                                }

                                // 动态 Hook 该 Listener 的具体实现类中的 onSensorChanged 方法
                                hookConcreteListenerClass(listener.javaClass, classLoader)
                                startPumpIfNeeded()
                            }
                        }

                        return@hookAllMethods result
                    }
                } catch (_: Throwable) {}
            }

            // 4. Hook unregisterListener / unregisterListenerImpl
            val unregMethods = listOf("unregisterListener", "unregisterListenerImpl")
            for (methodName in unregMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        val listener = args.firstOrNull { it != null && (it is SensorEventListener || LocationHooker.hasTypeByName(it.javaClass, "android.hardware.SensorEventListener")) }
                        if (listener != null) {
                            val sensor = args.filterIsInstance<Sensor>().firstOrNull()
                            capturedListeners.removeAll { it.listener === listener && (sensor == null || it.sensor === sensor) }
                            if (!hasStepListeners()) stopPump()
                        }
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }
        }

        // 5. Hook SystemSensorManager$SensorEventQueue.dispatchSensorEvent 底层原生分发接口
        hookSensorEventQueue(classLoader)
    }

    private fun hookSensorEventQueue(classLoader: ClassLoader) {
        try {
            val eventQueueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
                ?: XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$BaseEventQueue", classLoader)
                ?: return

            XposedHelpers.hookAllMethods(eventQueueClass, "dispatchSensorEvent") { chain, _ ->
                // Synthetic events use the same framework path as native events, including
                // accuracy notifications, value-array layout, and queue registration checks.
                if (syntheticDelivery.get() == true) {
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("enable_step_simulation", true)) {
                    val handle = chain.args.firstOrNull() as? Int
                    val values = chain.args.getOrNull(1) as? FloatArray

                    if (handle != null && values != null) {
                        val sensorType = handleToTypeMap[handle]
                        if (sensorType == Sensor.TYPE_STEP_COUNTER || sensorType == Sensor.TYPE_STEP_DETECTOR) {
                            // The cadence pump owns these streams; hardware events would double count.
                            return@hookAllMethods null
                        }
                        if (sensorType != null && isSimulatedType(sensorType)) {
                            val timestamp = chain.args.getOrNull(3) as? Long ?: SystemClock.elapsedRealtimeNanos()
                            val now = sensorWallTime(timestamp)
                            rewriteMotionValues(values, sensorType, config, now)
                            // Transform at the framework queue even when a concrete callback is
                            // forwarded through a wrapper, optimized, or loaded from another dex.
                            hardwareDelivery.set(true)
                            try { return@hookAllMethods chain.proceed(chain.args.toTypedArray()) }
                            finally { hardwareDelivery.remove() }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    internal fun saveReloadState(): Array<Any?> = arrayOf(
        capturedListeners.map { arrayOf<Any?>(it.listener, it.sensor, it.handler, it.frameworkQueue) }.toTypedArray(),
        stepBase, lastStepsFloat, lastStepInitTime,
        mockStepCounterSensor, mockStepDetectorSensor, mockAccelerometerSensor,
        handleToTypeMap.map { arrayOf(it.key, it.value) }.toTypedArray(), lastStepSampleTime
    )

    internal fun clearReloadState() {
        stopPump()
        capturedListeners.clear()
        hookedListenerClasses.clear()
    }

    internal fun restoreReloadState(value: Any?) {
        val state = value as? Array<*> ?: return
        (state.getOrNull(0) as? Array<*>)?.filterIsInstance<Array<*>>()?.forEach {
            capturedListeners.add(CapturedSensorListener(it[0]!!, it[1] as? Sensor, it[2] as? Handler).also { entry ->
                entry.frameworkQueue = it.getOrNull(3)
            })
            hookConcreteListenerClass(it[0]!!.javaClass, it[0]!!.javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
        }
        lastStepSampleTime = state.getOrNull(8) as? Long ?: 0L
        stepBase = state[1] as Double
        lastStepsFloat = state[2] as Double
        lastStepInitTime = state[3] as Long
        mockStepCounterSensor = state[4] as? Sensor
        mockStepDetectorSensor = state[5] as? Sensor
        mockAccelerometerSensor = state[6] as? Sensor
        (state[7] as? Array<*>)?.filterIsInstance<Array<*>>()?.forEach {
            handleToTypeMap[it[0] as Int] = it[1] as Int
        }
    }

    /**
     * 动态 Hook 具体的 Listener 实现类（如 Keep 的 StepListener）
     */
    private fun hookConcreteListenerClass(clazz: Class<*>, classLoader: ClassLoader) {
        val targetClass = generateSequence(clazz) { it.superclass }.firstOrNull { type ->
            type.declaredMethods.any { it.name == "onSensorChanged" }
        } ?: return
        if (hookedListenerClasses.putIfAbsent(targetClass, true) == null) {
            try {
                XposedHelpers.hookAllMethods(targetClass, "onSensorChanged") { chain, _ ->
                    if (syntheticDelivery.get() == true || hardwareDelivery.get() == true) {
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                    val event = chain.args.firstOrNull() as? SensorEvent
                    if (event != null && event.sensor != null) {
                        // Only the framework queue knows whether an event originated from HAL.
                        // A callback-wide filter here also swallowed synthetic events relayed
                        // onto another thread, where syntheticDelivery is no longer set.
                        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER || event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                        }
                        val config = currentSimulationConfig()
                        if (config != null) {
                            rewriteMotionValues(event.values, event.sensor.type, config, sensorWallTime(event.timestamp))
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
    }

    private fun sensorWallTime(timestamp: Long): Long = System.currentTimeMillis() +
        (timestamp - SystemClock.elapsedRealtimeNanos()) / 1_000_000L

    private fun rewriteMotionValues(values: FloatArray, type: Int, config: JSONObject, now: Long) {
        if (values.size < 3 || !isSimulatedType(type)) return
        val motion = RouteEngine.calculateCurrentPosition(config, now)
        val speed = motion.speed.toDouble()
        if (speed <= 0.05) return
        val originalFirst = values[0]
        val originalSecond = values[1]
        val originalThird = values[2]
        when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ->
                applySyntheticVibration(values, config, speed, type, now)
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED ->
                applySyntheticGyroscope(values, config, speed, type, now)
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ORIENTATION, Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                val heading = if (type == Sensor.TYPE_GAME_ROTATION_VECTOR) 0.0 else motion.bearing.toDouble()
                GaitAttitude.writeValues(type, values, GaitAttitude.sample(
                    calculateCurrentStepsFloat(config, now), speed, gaitTemplate(config), heading))
            }
        }
        runCatching { StepPipelineDiagnostics.motionRewrite(type, originalFirst, originalSecond, originalThird, values) }
    }

    private fun applySyntheticGyroscope(
        values: FloatArray,
        config: JSONObject,
        speed: Double,
        type: Int,
        now: Long = System.currentTimeMillis()
    ) {
        val steps = calculateCurrentStepsFloat(config, now)
        val session = RouteEngine.realismSession(config)
        val clock = StepCounterClock.decode(config.optString(StepCounterClock.CONFIG_KEY))
        val baseCadence = clock?.parameters?.cadenceSpm ?: if (config.optBoolean("is_auto_cadence", true)) calculateAutoCadence(speed).toDouble()
            else config.optInt("step_cadence_spm", 165).coerceIn(80, 240).toDouble()
        val elapsed = (now - config.optLong("start_timestamp", now)).coerceAtLeast(0L) / 1000.0
        val synthetic = session.gyroscope(steps, speed, session.boundedCadence(baseCadence, elapsed), gaitTemplate(config), noiseRng)
        synthetic.copyInto(values, endIndex = 3)
        if (type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
            // First three values include drift; the last three report our zero simulated drift.
            for (index in 3 until values.size) values[index] = 0f
        }
    }

    private fun applySyntheticVibration(values: FloatArray, config: JSONObject, speed: Double, type: Int, now: Long = System.currentTimeMillis()) {
        val stepsFloat = calculateCurrentStepsFloat(config, now)
        val session = RouteEngine.realismSession(config)
        val synthetic = if (type == Sensor.TYPE_LINEAR_ACCELERATION)
            session.linearAcceleration(stepsFloat, speed, gaitTemplate(config), noiseRng)
        else session.orientedAccelerometer(stepsFloat, speed, gaitTemplate(config), noiseRng)
        values[0] = synthetic[0]
        values[1] = synthetic[1]
        values[2] = synthetic[2]
        if (type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
            for (index in 3 until values.size) values[index] = 0f
        }
    }

    /** 用户录制的步态模板（配置里的编码字符串），按字符串内容缓存解码结果 */
    private fun gaitTemplate(config: JSONObject): GaitTemplate? {
        val source = config.optString("gait_template", "")
        if (source != cachedTemplateSource) {
            cachedTemplate = GaitTemplate.decode(source)
            cachedTemplateSource = source
        }
        return cachedTemplate
    }

    /**
     * 计算当前仿真总步数
     */
    fun calculateCurrentSteps(config: JSONObject, now: Long = System.currentTimeMillis()): Long =
        calculateCurrentStepsFloat(config, now).toLong()

    /** 连续的累计步数：整数部分是步数，小数部分是当前这一步的相位，供加速度波形对齐 */
    @Synchronized
    private fun calculateCurrentStepsFloat(config: JSONObject, now: Long): Double {
        val clock = StepCounterClock.decode(config.optString(StepCounterClock.CONFIG_KEY))
            ?: legacyStepClock(config, now)
        val bootCount = currentBootCount()
        if (bootCount != null && clock.bootCount != bootCount) {
            // A boot-restored remote snapshot may arrive before the publisher starts.
            // Hold at zero until it publishes this boot's common anchor; do not let
            // independently starting target processes invent different count origins.
            return 0.0
        }
        // Absolute shared time, rather than time accumulated while this process is subscribed.
        lastStepsFloat = clock.countAt(now)
        return lastStepsFloat
    }

    private fun currentBootCount(): Long? {
        cachedBootCount?.let { return it }
        return runCatching {
            val thread = Class.forName("android.app.ActivityThread")
            val context = thread.getDeclaredMethod("currentApplication").invoke(null) as? Context
                ?: return null
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
                .takeIf { it >= 0 }?.also { cachedBootCount = it }
        }.getOrNull()
    }

    private fun legacyStepClock(config: JSONObject, now: Long): StepCounterClock.State {
        val epoch = config.optLong("start_timestamp", now).takeIf { it > 0 } ?: now
        val speed = motionSpeed(config, now)
        val baseCadence = if (config.optBoolean("is_auto_cadence", true))
            calculateAutoCadence(config.optDouble("speed_m_s", speed))
        else config.optInt("step_cadence_spm", 165).coerceIn(80, 240)
        return StepCounterClock.State(StepCounterClock.INITIAL_STEPS, epoch,
            StepCounterClock.Parameters(epoch, baseCadence.toDouble(), speed > 0.05,
                realismLevel = config.optInt("realism_level"),
                speedFluctuationPct = config.optInt("speed_fluctuation_pct")))
    }

    /**
     * 智能根据速度自动计算匹配的生理步频 (SPM, 步/分钟)
     */
    fun calculateAutoCadence(speedMs: Double): Int {
        return StepCounterClock.autoCadence(speedMs)
    }

    private fun isSimulatedType(type: Int): Boolean = type == Sensor.TYPE_STEP_COUNTER ||
        type == Sensor.TYPE_STEP_DETECTOR ||
        type == Sensor.TYPE_ACCELEROMETER ||
        type == Sensor.TYPE_LINEAR_ACCELERATION ||
        type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ||
        type == Sensor.TYPE_GYROSCOPE ||
        type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED ||
        type == Sensor.TYPE_GRAVITY ||
        type == Sensor.TYPE_ORIENTATION ||
        type == Sensor.TYPE_ROTATION_VECTOR ||
        type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
        type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR

    private fun currentSimulationConfig(): JSONObject? =
        (XposedHelpers.module as? LocationHooker)?.readConfig()?.takeIf {
            it.optBoolean("active", false) && it.optBoolean("enable_step_simulation", true)
        }

    private fun motionSpeed(config: JSONObject, now: Long = System.currentTimeMillis()): Double =
        RouteEngine.calculateCurrentPosition(config, now).speed.toDouble()

    /** The one-second config worker starts the pump; it does not clock individual steps. */
    fun dispatchStepEvents(config: JSONObject, classLoader: ClassLoader) {
        if (currentSimulationConfig() == null) stopPump() else startPumpIfNeeded()
    }

    @Synchronized
    private fun startPumpIfNeeded() {
        if (pumpHandler != null || !hasStepListeners() || currentSimulationConfig() == null) return
        val thread = HandlerThread("LocationSpoofer-steps").also { it.start() }
        val handler = Handler(thread.looper)
        pumpThread = thread
        pumpHandler = handler
        val generation = ++pumpGeneration
        handler.post(object : Runnable {
            override fun run() {
                if (generation != pumpGeneration) return
                val config = currentSimulationConfig()
                if (config == null || !hasStepListeners()) {
                    stopPump()
                    return
                }
                val steps = calculateCurrentStepsFloat(config, System.currentTimeMillis())
                val timestamp = SystemClock.elapsedRealtimeNanos()
                for (entry in capturedListeners) {
                    val sensor = entry.sensor ?: continue
                    if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
                        val count = steps.toLong()
                        if (entry.lastCounter != count) {
                            entry.lastCounter = count
                            deliver(entry, floatArrayOf(count.toFloat()), timestamp, generation)
                        }
                    } else if (sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                        for (stepTime in entry.schedule.advance(steps, timestamp)) {
                            deliver(entry, floatArrayOf(1f), stepTime, generation)
                        }
                    }
                }
                if (generation == pumpGeneration) handler.postDelayed(this, 20L)
            }
        })
    }

    @Synchronized
    private fun stopPump() {
        ++pumpGeneration
        pumpHandler?.removeCallbacksAndMessages(null)
        pumpHandler = null
        pumpThread?.quitSafely()
        pumpThread = null
        // Legacy saved-state slot; the shared clock does not depend on this pump's lifetime.
        lastStepSampleTime = 0L
    }

    private fun hasStepListeners(): Boolean = capturedListeners.any {
        it.sensor?.type == Sensor.TYPE_STEP_COUNTER || it.sensor?.type == Sensor.TYPE_STEP_DETECTOR
    }

    private fun deliver(entry: CapturedSensorListener, values: FloatArray, timestamp: Long, generation: Long) {
        val sensor = entry.sensor ?: return
        (entry.handler ?: mainHandler).post {
            if (generation != pumpGeneration || capturedListeners.none { it === entry } || currentSimulationConfig() == null) return@post
            syntheticDelivery.set(true)
            try {
                val listener = entry.listener
                val queue = entry.frameworkQueue
                val handle = getSensorHandle(sensor)
                if (queue != null && handle != null) {
                    // JNI normally supplies a 16-element scratch array. SensorEventQueue
                    // copies the sensor-specific length rather than our one counter value.
                    val nativeValues = FloatArray(16)
                    values.copyInto(nativeValues)
                    XposedHelpers.callMethod(queue, "dispatchSensorEvent", handle, nativeValues, 3, timestamp)
                } else {
                    // Virtual sensors have no HAL queue; preserve the listener contract here.
                    val event = createSensorEvent(sensor, values) ?: return@post
                    event.timestamp = timestamp
                    if (!entry.deliveryReported) {
                        if (listener is SensorEventListener) listener.onAccuracyChanged(sensor, 3)
                        else XposedHelpers.callMethod(listener, "onAccuracyChanged", sensor, 3)
                    }
                    if (listener is SensorEventListener) listener.onSensorChanged(event)
                    else XposedHelpers.callMethod(listener, "onSensorChanged", event)
                }
                if (!entry.deliveryReported) {
                    entry.deliveryReported = true
                    android.util.Log.i("LocationSpoofer", "[SensorStep] First delivery: type=${sensor.type}, listener=${listener.javaClass.name}, thread=${Thread.currentThread().name}, path=${if (queue != null) "framework" else "virtual"}")
                }
                reportDeliveryWindow(entry, values[0])
            } catch (error: Throwable) {
                if (!entry.failureReported) {
                    entry.failureReported = true
                    android.util.Log.e("LocationSpoofer", "[SensorStep] Callback failed: type=${sensor.type}, listener=${entry.listener.javaClass.name}", error)
                }
            } finally {
                syntheticDelivery.remove()
            }
        }
    }

    private fun frameworkQueue(manager: Any?, listener: Any): Any? = runCatching {
        val queues = XposedHelpers.getObjectField(manager ?: return null, "mSensorListeners") as? Map<*,*>
            ?: return null
        synchronized(queues) { queues[listener] }
    }.getOrNull()

    private fun frameworkHandler(manager: Any?): Handler? = runCatching {
        val looper = XposedHelpers.getObjectField(manager ?: return null, "mMainLooper") as? Looper
            ?: return null
        Handler(looper)
    }.getOrNull()

    /** Measure completed callbacks, including Handler delays, rather than scheduled events. */
    private fun reportDeliveryWindow(entry: CapturedSensorListener, value: Float) {
        val now = SystemClock.elapsedRealtime()
        if (entry.deliveryWindowStart == 0L) {
            entry.deliveryWindowStart = now
            entry.deliveryWindowFirstValue = value
        }
        if (entry.previousDeliveryTime != 0L) {
            entry.maxDeliveryGap = maxOf(entry.maxDeliveryGap, now - entry.previousDeliveryTime)
        }
        entry.previousDeliveryTime = now
        ++entry.deliveryWindowEvents
        val elapsed = now - entry.deliveryWindowStart
        if (elapsed < 30_000L) return
        val steps = if (entry.sensor?.type == Sensor.TYPE_STEP_COUNTER)
            (value - entry.deliveryWindowFirstValue).toDouble()
        else (entry.deliveryWindowEvents - 1).toDouble()
        val cadence = steps * 60_000.0 / elapsed
        android.util.Log.i("LocationSpoofer", "[SensorStep] Delivered: type=${entry.sensor?.type}, listener=${entry.listener.javaClass.name}, events=${entry.deliveryWindowEvents}, cadence_spm=${cadence.toInt()}, max_gap_ms=${entry.maxDeliveryGap}, value=$value, thread=${Thread.currentThread().name}")
        entry.deliveryWindowStart = now
        entry.deliveryWindowFirstValue = value
        entry.deliveryWindowEvents = 1L
        entry.maxDeliveryGap = 0L
    }

    private fun getSensorHandle(sensor: Sensor): Int? {
        return try {
            val handleField = Sensor::class.java.getDeclaredField("mHandle")
            handleField.isAccessible = true
            handleField.getInt(sensor)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateMockSensor(type: Int, classLoader: ClassLoader): Sensor? {
        if (type == Sensor.TYPE_STEP_COUNTER && mockStepCounterSensor != null) return mockStepCounterSensor
        if (type == Sensor.TYPE_STEP_DETECTOR && mockStepDetectorSensor != null) return mockStepDetectorSensor
        if (type == Sensor.TYPE_ACCELEROMETER && mockAccelerometerSensor != null) return mockAccelerometerSensor

        try {
            val sensorClass = Class.forName("android.hardware.Sensor", false, classLoader)
            val constructor: Constructor<*> = sensorClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val sensor = constructor.newInstance() as Sensor

            setSensorField(sensor, "mType", type)
            setSensorField(sensor, "mHandle", -10000 - type)
            setSensorField(sensor, "mFlags", if (type == Sensor.TYPE_STEP_COUNTER) 2 else 6)
            setSensorField(sensor, "mMaxRange", if (type == Sensor.TYPE_STEP_COUNTER) 16777216f else 1f)
            setSensorField(sensor, "mName", when(type) {
                Sensor.TYPE_STEP_COUNTER -> "Step Counter Sensor"
                Sensor.TYPE_STEP_DETECTOR -> "Step Detector Sensor"
                else -> "Accelerometer Sensor"
            })
            setSensorField(sensor, "mVendor", "Android")
            setSensorField(sensor, "mVersion", 1)
            setSensorField(sensor, "mResolution", 1.0f)
            setSensorField(sensor, "mPower", 0.05f)

            when (type) {
                Sensor.TYPE_STEP_COUNTER -> mockStepCounterSensor = sensor
                Sensor.TYPE_STEP_DETECTOR -> mockStepDetectorSensor = sensor
                Sensor.TYPE_ACCELEROMETER -> mockAccelerometerSensor = sensor
            }
            return sensor
        } catch (_: Throwable) {
            return null
        }
    }

    private fun setSensorField(sensor: Sensor, fieldName: String, value: Any) {
        try {
            val field: Field = Sensor::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(sensor, value)
        } catch (_: Throwable) {}
    }

    private fun createSensorEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        try {
            val eventConstructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            eventConstructor.isAccessible = true
            val event = eventConstructor.newInstance(values.size) as SensorEvent
            System.arraycopy(values, 0, event.values, 0, values.size)

            val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
            sensorField.isAccessible = true
            sensorField.set(event, sensor)

            val timestampField = SensorEvent::class.java.getDeclaredField("timestamp")
            timestampField.isAccessible = true
            timestampField.setLong(event, SystemClock.elapsedRealtimeNanos())

            val accuracyField = SensorEvent::class.java.getDeclaredField("accuracy")
            accuracyField.isAccessible = true
            accuracyField.setInt(event, 3) // SENSOR_STATUS_ACCURACY_HIGH

            return event
        } catch (_: Throwable) {
            try {
                // Fallback 1: search any constructors
                val constructors = SensorEvent::class.java.declaredConstructors
                for (ctor in constructors) {
                    ctor.isAccessible = true
                    val paramTypes = ctor.parameterTypes
                    val args = arrayOfNulls<Any>(paramTypes.size)
                    for (i in args.indices) {
                        if (paramTypes[i] == Int::class.javaPrimitiveType) args[i] = values.size
                    }
                    val event = ctor.newInstance(*args) as SensorEvent
                    System.arraycopy(values, 0, event.values, 0, values.size)
                    try {
                        val sf = SensorEvent::class.java.getDeclaredField("sensor")
                        sf.isAccessible = true
                        sf.set(event, sensor)
                        val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                        tf.isAccessible = true
                        tf.setLong(event, SystemClock.elapsedRealtimeNanos())
                        val af = SensorEvent::class.java.getDeclaredField("accuracy")
                        af.isAccessible = true
                        af.setInt(event, 3)
                    } catch (_: Throwable) {}
                    return event
                }
            } catch (_: Throwable) {}

            try {
                // Fallback 2: sun.misc.Unsafe.allocateInstance
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                val unsafe = theUnsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val event = allocateMethod.invoke(unsafe, SensorEvent::class.java) as SensorEvent

                val valuesField = SensorEvent::class.java.getDeclaredField("values")
                valuesField.isAccessible = true
                val arr = FloatArray(values.size)
                System.arraycopy(values, 0, arr, 0, values.size)
                valuesField.set(event, arr)

                val sf = SensorEvent::class.java.getDeclaredField("sensor")
                sf.isAccessible = true
                sf.set(event, sensor)

                val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                tf.isAccessible = true
                tf.setLong(event, SystemClock.elapsedRealtimeNanos())

                val af = SensorEvent::class.java.getDeclaredField("accuracy")
                af.isAccessible = true
                af.setInt(event, 3)
                return event
            } catch (_: Throwable) {}

            return null
        }
    }
}
