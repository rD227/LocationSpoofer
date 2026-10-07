package com.vincenthzr.locationspoofer.xposed.diagnostics

import android.hardware.Sensor
import android.os.SystemClock
import android.util.Log
import com.vincenthzr.locationspoofer.xposed.BuildConfig
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Optional observations of the installed Alipay SDK; never change its arguments or results. */
internal object StepPipelineDiagnostics {
    private const val TAG = "LocationSpoofer"
    private val installed = ConcurrentHashMap<Class<*>, Boolean>()
    private val windows = ConcurrentHashMap<String, Window>()
    private val fields = ConcurrentHashMap<Class<*>, Map<String, Field>>()
    private val motionWindows = ConcurrentHashMap<Int, MotionWindow>()
    private val bridgeDepth = ThreadLocal<Int>()
    private val sportSyncDepth = ThreadLocal<Int>()
    private val motionBridgeDepth = ThreadLocal<Int>()
    private val motionBridges = ConcurrentHashMap<Class<*>, Boolean>()
    private val sourceObservers = ConcurrentHashMap<Class<*>, Boolean>()
    private val replyObservers = ConcurrentHashMap<Class<*>, Boolean>()
    private val replyDepth = ThreadLocal<Int>()

    private class Window {
        var lastReport = Long.MIN_VALUE
        var calls = 0L
        var rejected = 0L
        var firstSeen = Long.MIN_VALUE
    }

    private class MotionWindow {
        var started = 0L
        var events = 0L
        var changed = 0L
        var minimumDifference = Double.POSITIVE_INFINITY
        var maximumDifference = 0.0
    }

    fun install(loader: ClassLoader) {
        if (!BuildConfig.STEP_PIPELINE_DIAGNOSTICS) return
        installBridgeNames(loader)
        installMotionBridgeNames(loader)
        installSportState(loader)
        installSportSync(loader)
        installSportSources(loader)
        val targets = mapOf(
            "com.alibaba.health.pedometer.core.datasource.sensor.model.StepSensorEvent" to listOf("convert"),
            "com.alibaba.health.pedometer.core.datasource.sensor.core.SensorPedometer" to
                listOf("checkDirtyStepEvent", "onStepEventChanged", "readDailyStep", "getDailyStepInfoRecord"),
            "com.alibaba.health.pedometer.core.datasource.sensor.core.SensorEventFilterImpl" to listOf("filterSenorEvent"),
            "com.alibaba.health.pedometer.intergation.PushSensorPedometer" to listOf("readDailyStep"),
            "com.alipay.mobile.healthcommon.PedometerServiceImpl" to listOf("getTodayStepCount"),
            "com.alipay.mobile.healthcommon.H5Plugin.HealthPedometerBridgeExtension" to listOf("getRunData")
        )
        for ((name, methods) in targets) {
            val type = XposedHelpers.findClassIfExists(name, loader) ?: continue
            if (installed.putIfAbsent(type, true) != null) continue
            for (method in methods) {
                val handles = XposedHelpers.hookAllMethods(type, method) { chain, executable ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    // Diagnostics failures must not escape into the application's call stack.
                    runCatching {
                        val key = "${type.simpleName}.$method/${executable.parameterCount}"
                        val window = windows.getOrPut(key) { Window() }
                        synchronized(window) {
                            ++window.calls
                            if (method == "checkDirtyStepEvent" && result == true) ++window.rejected
                            val now = SystemClock.elapsedRealtime()
                            if (window.lastReport == Long.MIN_VALUE || now - window.lastReport >= 30_000L) {
                                window.lastReport = now
                                val args = chain.args.mapNotNull { snapshot(it) }.joinToString(" -> ")
                                Log.i(TAG, "[StepPipeline] $key calls=${window.calls} rejected=${window.rejected} args=[$args] result=${snapshot(result)}")
                                if (method == "getTodayStepCount" || method == "getRunData") {
                                    val callers = Throwable().stackTrace.filter {
                                        !it.className.startsWith("com.vincenthzr.locationspoofer.") &&
                                            !it.className.startsWith("io.github.libxposed.")
                                    }.take(10).joinToString(" <- ") { "${it.className}.${it.methodName}" }
                                    Log.i(TAG, "[StepPipeline] Caller $key $callers")
                                }
                            }
                        }
                    }
                    result
                }
                Log.i(TAG, "[StepPipeline] Observing $name#$method methods=${handles.size}")
            }
        }
    }

    /** Observe API names only; never inspect bridge parameters, identifiers or encrypted results. */
    private fun installBridgeNames(loader: ClassLoader) {
        val routes = listOf(
            Triple("com.alibaba.ariver.engine.common.bridge.dispatch.BridgeDispatcher", "dispatch", "getName"),
            Triple("com.alipay.mobile.nebulacore.bridge.H5BridgeImpl", "sendToNative", "getAction")
        )
        for ((name, method, accessor) in routes) {
            val type = XposedHelpers.findClassIfExists(name, loader) ?: continue
            if (installed.putIfAbsent(type, true) != null) continue
            val handles = XposedHelpers.hookAllMethods(type, method) { chain, _ ->
                val depth = bridgeDepth.get() ?: 0
                bridgeDepth.set(depth + 1)
                try {
                    if (depth == 0) runCatching {
                        val context = chain.args.firstOrNull { argument ->
                            argument != null && generateSequence(argument.javaClass) { it.superclass }.any {
                                it.name == "com.alibaba.ariver.engine.api.bridge.model.NativeCallContext" ||
                                    it.name == "com.alipay.mobile.h5container.api.H5Event"
                            }
                        }
                        val api = context?.let { XposedHelpers.callMethod(it, accessor) } as? String
                        if (api != null && api.matches(Regex("[A-Za-z][A-Za-z0-9_.]{0,100}")) &&
                            listOf("step", "sport", "rundata", "accelerometer", "gyroscope", "sensor", "fitness", "shake", "motion")
                                .any { api.contains(it, ignoreCase = true) }) {
                            reportWindow("bridge:$api") { window ->
                                "[StepPipeline] Bridge API=$api entries=${window.calls} source=${type.simpleName} names-only=true"
                            }
                        }
                    }
                    chain.proceed(chain.args.toTypedArray())
                } finally {
                    if (depth == 0) bridgeDepth.remove() else bridgeDepth.set(depth)
                }
            }
            Log.i(TAG, "[StepPipeline] Observing bridge ${type.simpleName}#$method methods=${handles.size} names-only=true")
        }
    }

    /** Counts motion event dispatches to the renderer, not successful JS callback executions. */
    private fun installMotionBridgeNames(loader: ClassLoader) {
        val routes = mapOf(
            "com.alibaba.ariver.engine.api.EngineUtils" to listOf("sendToRender"),
            "com.alipay.mobile.nebulacore.bridge.H5BridgeImpl" to listOf("sendToWeb")
        )
        val events = setOf("accelerometerChange", "gyroscopeChange", "deviceMotionChange")
        for ((name, methods) in routes) {
            val type = XposedHelpers.findClassIfExists(name, loader) ?: continue
            if (motionBridges.putIfAbsent(type, true) != null) continue
            for (method in methods) {
                val handles = XposedHelpers.hookAllMethods(type, method) { chain, _ ->
                    val depth = motionBridgeDepth.get() ?: 0
                    motionBridgeDepth.set(depth + 1)
                    try {
                        val result = chain.proceed(chain.args.toTypedArray())
                        if (depth == 0) runCatching {
                            val event = chain.args.filterIsInstance<String>().firstOrNull { it in events }
                            if (event != null) reportWindow("render:${type.simpleName}:$event") { window ->
                                val elapsed = SystemClock.elapsedRealtime() - window.firstSeen
                                val hz = if (elapsed > 0) (window.calls - 1) * 1000.0 / elapsed else 0.0
                                "[StepPipeline] MotionBridge event=$event dispatches=${window.calls} elapsed_ms=$elapsed hz=$hz source=${type.simpleName} names-only=true"
                            }
                        }
                        result
                    } finally {
                        if (depth == 0) motionBridgeDepth.remove() else motionBridgeDepth.set(depth)
                    }
                }
                Log.i(TAG, "[StepPipeline] Observing motion bridge ${type.simpleName}#$method methods=${handles.size} names-only=true")
            }
        }
    }

    /** In this APK the detector consumes STEP_MOVE to update last movement time, not final cadence. */
    private fun installSportState(loader: ClassLoader) {
        val type = XposedHelpers.findClassIfExists("com.alipay.android.phone.wallet.sportbiz.detector.f", loader) ?: return
        if (installed.putIfAbsent(type, true) != null) return
        val handles = XposedHelpers.hookAllMethods(type, "a") { chain, method ->
            val result = chain.proceed(chain.args.toTypedArray())
            if (method.parameterCount == 1 &&
                method.parameterTypes[0].name == "com.alipay.android.phone.wallet.sportbiz.a.a.a") runCatching {
                val event = chain.args[0] ?: return@runCatching
                val eventName = event.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(event)
                if (eventName == "STEP_MOVE") {
                    val eventTime = event.javaClass.getDeclaredField("b").apply { isAccessible = true }.getLong(event)
                    val lastMotion = type.getDeclaredField("e").apply { isAccessible = true }.getLong(chain.thisObject)
                    reportWindow("sport:STEP_MOVE") { window ->
                        val now = System.currentTimeMillis()
                        "[StepPipeline] SportState STEP_MOVE consumed=${window.calls} event_age_ms=${now - eventTime} last_motion_age_ms=${now - lastMotion} read-only=true"
                    }
                }
            }
            result
        }
        Log.i(TAG, "[StepPipeline] Observing SportState#STEP_MOVE methods=${handles.size} read-only=true")
    }

    private inline fun reportWindow(key: String, message: (Window) -> String) {
        val window = windows.getOrPut(key) { Window() }
        synchronized(window) {
            ++window.calls
            val now = SystemClock.elapsedRealtime()
            if (window.firstSeen == Long.MIN_VALUE) window.firstSeen = now
            if (window.lastReport == Long.MIN_VALUE || now - window.lastReport >= 30_000L) {
                window.lastReport = now
                Log.i(TAG, message(window))
            }
        }
    }

    /** Compare this APK's local source counts with its sync result, without altering either. */
    private fun installSportSync(loader: ClassLoader) {
        val bridge = XposedHelpers.findClassIfExists(
            "com.alipay.mobile.healthcommon.jsapi.PedometerKitBridgeExtension", loader) ?: return
        if (installed.putIfAbsent(bridge, true) == null) {
            val handles = XposedHelpers.hookAllMethods(bridge, "syncStepListData") { chain, _ ->
                val depth = sportSyncDepth.get() ?: 0
                sportSyncDepth.set(depth + 1)
                try {
                    runCatching {
                        observeSportReply(chain.args.getOrNull(4))
                        val sources = chain.args.firstOrNull { it is List<*> } as? List<*>
                        reportWindow("sport:sync-input") { window ->
                            val counts = sources?.take(16)?.mapNotNull { snapshot(it) }?.joinToString()
                            "[StepPipeline] SportSync input calls=${window.calls} sources=${sources?.size} counts=[$counts] read-only=true"
                        }
                    }
                    chain.proceed(chain.args.toTypedArray())
                } finally {
                    if (depth == 0) sportSyncDepth.remove() else sportSyncDepth.set(depth)
                }
            }
            Log.i(TAG, "[StepPipeline] Observing SportSync#syncStepListData methods=${handles.size} numeric-only=true")
        }
        val rpc = XposedHelpers.findClassIfExists(
            "com.alibaba.health.pedometer.intergation.rpc.RpcClient", loader) ?: return
        if (installed.putIfAbsent(rpc, true) != null) return
        val handles = XposedHelpers.hookAllMethods(rpc, "a") { chain, method ->
            val scoped = (sportSyncDepth.get() ?: 0) > 0 &&
                (method as? Method)?.returnType?.name == "com.alibaba.health.pedometer.intergation.rpcPB.StepCounterSyncResultPB"
            val result = chain.proceed(chain.args.toTypedArray())
            if (method.parameterCount == 0 && (method as? Method)?.returnType == List::class.java) runCatching {
                reportWindow("sport:source-list") { window ->
                    val sources = result as? List<*>
                    val counts = sources?.take(16)?.mapNotNull { snapshot(it) }?.joinToString()
                    "[StepPipeline] SportSources collected calls=${window.calls} sources=${sources?.size} counts=[$counts] read-only=true"
                }
            }
            if (scoped) runCatching {
                reportWindow("sport:sync-result") { window ->
                    "[StepPipeline] SportSync result calls=${window.calls} metadata=${snapshot(result)} read-only=true"
                }
            }
            result
        }
        Log.i(TAG, "[StepPipeline] Observing SportSync#result methods=${handles.size} numeric-only=true")
    }

    /** Observe the SDK's own source registration and permission decisions; never initialize it. */
    private fun installSportSources(loader: ClassLoader) {
        val targets = mapOf(
            "com.alibaba.health.pedometer.core.datasource.PedometerAgent" to listOf("getPedometers"),
            "com.alibaba.health.pedometer.core.datasource.sdk.DefaultPedometer" to
                listOf("checkPermission", "getPedometerStatus"),
            "com.alibaba.health.pedometer.intergation.rpc.CommonUtil" to listOf("getUserId")
        )
        for ((name, methods) in targets) {
            val type = XposedHelpers.findClassIfExists(name, loader) ?: continue
            if (sourceObservers.putIfAbsent(type, true) != null) continue
            for (method in methods) {
                val handles = XposedHelpers.hookAllMethods(type, method) { chain, _ ->
                    val scoped = (sportSyncDepth.get() ?: 0) > 0
                    val result = chain.proceed(chain.args.toTypedArray())
                    if (method != "getUserId" || scoped) runCatching {
                        reportWindow("sport:source:$name:$method") { window ->
                            val metadata = when (method) {
                                "getPedometers" -> {
                                    val sources = result as? List<*>
                                    val classes = sources?.take(16)?.map { it?.javaClass?.name }
                                    "registered=${sources?.size} classes=$classes"
                                }
                                // Only record presence, never the identifier or any of its characters.
                                "getUserId" -> "session_present=${(result as? String)?.isNotEmpty() == true}"
                                else -> "result=${snapshot(result)}"
                            }
                            "[StepPipeline] SportSources ${type.simpleName}.$method calls=${window.calls} $metadata read-only=true"
                        }
                    }
                    result
                }
                Log.i(TAG, "[StepPipeline] Observing SportSources ${type.simpleName}#$method methods=${handles.size} read-only=true")
            }
        }
    }

    /** Whitelist the actual sync reply's numeric fields, rather than assuming an RPC was sent. */
    private fun observeSportReply(callback: Any?) {
        if (callback == null) return
        for (type in generateSequence(callback.javaClass) { it.superclass }) {
            if (type == Any::class.java || replyObservers.putIfAbsent(type, true) != null) continue
            XposedHelpers.hookAllMethods(type, "sendJSONResponse") { chain, _ ->
                if ((sportSyncDepth.get() ?: 0) == 0) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                val depth = replyDepth.get() ?: 0
                replyDepth.set(depth + 1)
                try {
                    if (depth == 0) runCatching {
                        val response = chain.args.firstOrNull() as? Map<*, *> ?: return@runCatching
                        val info = response["stepInfo"] as? Map<*, *>
                        fun numeric(value: Any?): Any? = when (value) {
                            is Number, is Boolean -> value
                            is String -> value.takeIf { it.matches(Regex("[0-9]{1,8}")) }
                            else -> null
                        }
                        reportWindow("sport:sync-reply") { window ->
                            "[StepPipeline] SportSync reply calls=${window.calls} success=${numeric(response["success"])} " +
                                "bizError=${numeric(response["bizError"])} error=${numeric(response["error"])} " +
                                "step=${numeric(info?.get("step"))} sources=${(info?.get("sourceList") as? List<*>)?.size} read-only=true"
                        }
                    }
                    chain.proceed(chain.args.toTypedArray())
                } finally {
                    if (depth == 0) replyDepth.remove() else replyDepth.set(depth)
                }
            }
        }
    }

    /** Low-frequency proof of motion rewriting in the actual target process, not just the probe. */
    fun motionRewrite(type: Int, first: Float, second: Float, third: Float, values: FloatArray) {
        if (!BuildConfig.STEP_PIPELINE_DIAGNOSTICS || values.size < 3) return
        val window = motionWindows.getOrPut(type) { MotionWindow() }
        synchronized(window) {
            val now = SystemClock.elapsedRealtime()
            if (window.started == 0L) window.started = now
            val difference = kotlin.math.sqrt(
                (values[0] - first).toDouble().let { it * it } +
                    (values[1] - second).toDouble().let { it * it } +
                    (values[2] - third).toDouble().let { it * it })
            ++window.events
            if (difference > 1e-5) ++window.changed
            window.minimumDifference = minOf(window.minimumDifference, difference)
            window.maximumDifference = maxOf(window.maximumDifference, difference)
            if (window.events == 1L || now - window.started >= 30_000L) {
                Log.i(TAG, "[StepPipeline] Motion rewrite type=$type events=${window.events} changed=${window.changed} difference=${window.minimumDifference}..${window.maximumDifference} elapsed_ms=${now - window.started}")
                if (now - window.started >= 30_000L) {
                    window.started = now
                    window.events = 0
                    window.changed = 0
                    window.minimumDifference = Double.POSITIVE_INFINITY
                    window.maximumDifference = 0.0
                }
            }
        }
    }

    fun registration(listener: Any, sensor: Sensor, accepted: Boolean) {
        if (!BuildConfig.STEP_PIPELINE_DIAGNOSTICS) return
        val key = "register:${listener.javaClass.name}:${sensor.type}"
        val window = windows.getOrPut(key) { Window() }
        synchronized(window) {
            ++window.calls
            if (!accepted) ++window.rejected
            val now = SystemClock.elapsedRealtime()
            if (window.lastReport == Long.MIN_VALUE || now - window.lastReport >= 30_000L) {
                window.lastReport = now
                Log.i(TAG, "[StepPipeline] Registration type=${sensor.type} sensor=${sensor.name} listener=${listener.javaClass.name} accepted=$accepted calls=${window.calls}")
            }
        }
    }

    /** Whitelist numeric step metadata; never log arbitrary SDK objects or application data. */
    private fun snapshot(value: Any?): String? {
        if (value == null) return null
        if (value is Number || value is Boolean) return value.toString()
        if (value is Enum<*>) return value.name
        val type = value.javaClass
        if (type.name != "com.alibaba.health.pedometer.core.datasource.sensor.model.StepSensorEvent" &&
            type.name != "com.alibaba.health.pedometer.core.datasource.sensor.model.StepInfoRecord" &&
            type.name != "com.alibaba.health.pedometer.core.datasource.feature.PedometerStatus" &&
            type.name != "com.alibaba.health.pedometer.intergation.rpcPB.StepDataPB" &&
            type.name != "com.alibaba.health.pedometer.intergation.rpcPB.StepCounterSyncResultPB") return null
        val available = fields.getOrPut(type) {
            type.declaredFields.filter { it.name in setOf("count", "timestamp", "timeInMillis", "receiveTimeMillis",
                "dailyCount", "dailyCountOffset", "finalDailyCount", "uploadedDailyCount", "baseStep", "lastStep",
                "stepCount", "accuracy", "success", "statusCode", "userDailyCount", "code") }
                .onEach { it.isAccessible = true }.associateBy { it.name }
        }
        return available.entries.joinToString(",", prefix = "${type.simpleName}{", postfix = "}") { (name, field) ->
            "$name=${snapshot(field.get(value))}"
        }
    }
}
