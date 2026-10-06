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
import com.vincenthzr.locationspoofer.utils.GaitTemplate
import com.vincenthzr.locationspoofer.xposed.LocationHooker
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
        internal val schedule = StepEventSchedule()
        internal var lastCounter: Long = Long.MIN_VALUE
        internal var deliveryReported = false
        internal var failureReported = false
    }

    val capturedListeners = CopyOnWriteArrayList<CapturedSensorListener>()
    private val hookedListenerClasses = ConcurrentHashMap<Class<*>, Boolean>()
    private val syntheticDelivery = ThreadLocal<Boolean>()
    private var pumpThread: HandlerThread? = null
    private var pumpHandler: Handler? = null
    @Volatile private var pumpGeneration = 0L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // 真实计步器自开机以来单调递增：换会话 / 摇杆每次写配置导致 start_timestamp 变化时，
    // 把已累计的步数接续为新的起点，而不是回到初始值
    private const val INITIAL_BOOT_STEPS = 2350.0
    @Volatile private var stepBase: Double = INITIAL_BOOT_STEPS
    @Volatile private var lastStepsFloat: Double = INITIAL_BOOT_STEPS
    @Volatile private var lastStepInitTime: Long = 0L
    private var lastStepSampleTime: Long = 0L

    private val noiseRng = java.util.Random()
    @Volatile private var cachedTemplateSource: String? = null
    @Volatile private var cachedTemplate: GaitTemplate? = null

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
                        if (listener != null && result == true) {
                            val targetSensor = sensor
                            if (targetSensor != null && isSimulatedType(targetSensor.type)) {
                                val entry = CapturedSensorListener(listener, targetSensor, handler)
                                if (!capturedListeners.any { it.listener === listener && it.sensor?.type == targetSensor.type }) {
                                    capturedListeners.add(entry)
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
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    internal fun saveReloadState(): Array<Any?> = arrayOf(
        capturedListeners.map { arrayOf<Any?>(it.listener, it.sensor, it.handler) }.toTypedArray(),
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
            capturedListeners.add(CapturedSensorListener(it[0]!!, it[1] as? Sensor, it[2] as? Handler))
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
                    if (syntheticDelivery.get() == true) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    val event = chain.args.firstOrNull() as? SensorEvent
                    if (event != null && event.sensor != null) {
                        val config = currentSimulationConfig()
                        if (config != null) {
                            when (event.sensor.type) {
                                Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> return@hookAllMethods null
                                Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                                    val speed = motionSpeed(config)
                                    if (event.values.size >= 3 && speed > 0.05) {
                                        applySyntheticVibration(event.values, config, speed, event.sensor.type)
                                    }
                                }
                                Sensor.TYPE_GYROSCOPE,
                                Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                                    val speed = motionSpeed(config)

                                    if (event.values.size >= 3 && speed > 0.05) {
                                        applySyntheticGyroscope(
                                            event.values,
                                            config,
                                            speed,
                                            event.sensor.type
                                        )
                                    }
                                }
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
    }

    private fun applySyntheticGyroscope(
        values: FloatArray,
        config: JSONObject,
        speed: Double,
        type: Int
    ) {
        val now = System.currentTimeMillis()
        val steps = calculateCurrentStepsFloat(config, now)
        val session = RouteEngine.realismSession(config)
        val baseCadence = if (config.optBoolean("is_auto_cadence", true)) calculateAutoCadence(speed)
            else config.optInt("step_cadence_spm", 165).coerceIn(60, 240)
        val elapsed = (now - config.optLong("start_timestamp", now)).coerceAtLeast(0L) / 1000.0
        val synthetic = session.gyroscope(steps, speed, session.cadence(baseCadence.toDouble(), elapsed), gaitTemplate(config), noiseRng)
        synthetic.copyInto(values, endIndex = 3)
        if (type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
            // First three values include drift; the last three report our zero simulated drift.
            for (index in 3 until values.size) values[index] = 0f
        }
    }

    private fun applySyntheticVibration(values: FloatArray, config: JSONObject, speed: Double, type: Int) {
        val now = System.currentTimeMillis()
        val stepsFloat = calculateCurrentStepsFloat(config, now)
        val session = RouteEngine.realismSession(config)
        val synthetic = if (type == Sensor.TYPE_LINEAR_ACCELERATION)
            session.linearAcceleration(stepsFloat, speed, gaitTemplate(config), noiseRng)
        else session.accelerometer(stepsFloat, speed, gaitTemplate(config), noiseRng)
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
        val startTime = config.optLong("start_timestamp", now)
        if (lastStepInitTime != startTime) {
            lastStepInitTime = startTime
            stepBase = lastStepsFloat
            lastStepSampleTime = now
        }
        val previousTime = lastStepSampleTime
        lastStepSampleTime = now
        val speed = motionSpeed(config, now)
        if (speed <= 0.05 || previousTime == 0L || now <= previousTime) return lastStepsFloat
        val baseCadence = if (config.optBoolean("is_auto_cadence", true)) calculateAutoCadence(speed)
            else config.optInt("step_cadence_spm", 165).coerceIn(60, 240)
        val session = RouteEngine.realismSession(config, startTime)
        val elapsed = (now - startTime).coerceAtLeast(0L) / 1000.0
        val previousElapsed = (previousTime - startTime).coerceAtLeast(0L) / 1000.0
        lastStepsFloat += (session.steps(baseCadence.toDouble(), elapsed) -
            session.steps(baseCadence.toDouble(), previousElapsed)).coerceAtLeast(0.0)
        return lastStepsFloat
    }

    /**
     * 智能根据速度自动计算匹配的生理步频 (SPM, 步/分钟)
     */
    fun calculateAutoCadence(speedMs: Double): Int {
        return when {
            speedMs <= 0.8 -> 95 // 慢走
            speedMs <= 1.5 -> (100 + (speedMs - 0.8) / 0.7 * 25).toInt() // 快走 100~125 SPM
            speedMs <= 2.8 -> (135 + (speedMs - 1.5) / 1.3 * 25).toInt() // 慢跑 135~160 SPM
            speedMs <= 4.0 -> (160 + (speedMs - 2.8) / 1.2 * 20).toInt() // 匀速跑 160~180 SPM
            speedMs <= 5.5 -> (180 + (speedMs - 4.0) / 1.5 * 15).toInt() // 快跑 180~195 SPM
            else -> 200 // 冲刺 200 SPM
        }
    }

    private fun isSimulatedType(type: Int): Boolean = type == Sensor.TYPE_STEP_COUNTER ||
        type == Sensor.TYPE_STEP_DETECTOR ||
        type == Sensor.TYPE_ACCELEROMETER ||
        type == Sensor.TYPE_LINEAR_ACCELERATION ||
        type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ||
        type == Sensor.TYPE_GYROSCOPE ||
        type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED

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
        // A later enable must not count time spent with simulation disabled.
        lastStepSampleTime = 0L
    }

    private fun hasStepListeners(): Boolean = capturedListeners.any {
        it.sensor?.type == Sensor.TYPE_STEP_COUNTER || it.sensor?.type == Sensor.TYPE_STEP_DETECTOR
    }

    private fun deliver(entry: CapturedSensorListener, values: FloatArray, timestamp: Long, generation: Long) {
        val sensor = entry.sensor ?: return
        val event = createSensorEvent(sensor, values) ?: return
        event.timestamp = timestamp
        (entry.handler ?: mainHandler).post {
            if (generation != pumpGeneration || capturedListeners.none { it === entry } || currentSimulationConfig() == null) return@post
            syntheticDelivery.set(true)
            try {
                val listener = entry.listener
                if (listener is SensorEventListener) listener.onSensorChanged(event)
                else XposedHelpers.callMethod(listener, "onSensorChanged", event)
                if (!entry.deliveryReported) {
                    entry.deliveryReported = true
                    android.util.Log.i("LocationSpoofer", "[SensorStep] First delivery: type=${sensor.type}, listener=${listener.javaClass.name}, thread=${Thread.currentThread().name}")
                }
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
