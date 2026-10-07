package com.vincenthzr.locationspoofer.xposed.diagnostics

import android.util.Log
import com.vincenthzr.locationspoofer.xposed.BuildConfig

/** Opt-in observation of NDK sensor clients; never substitutes events or SDK results. */
object NativeSensorTrace {
    @Volatile var loaded = false
        private set

    @Synchronized
    fun install(hostPackage: String) {
        if (!BuildConfig.STEP_PIPELINE_DIAGNOSTICS || loaded) return
        if (hostPackage != "com.eg.android.AlipayGphone" && hostPackage != "com.locationspoofer.sensorprobe") return
        try {
            System.loadLibrary("locationspoofer_sensor_trace")
            loaded = true
            val hooked = startObservation()
            Log.i("LocationSpoofer", "[NativeSensorTrace] host=$hostPackage active=$hooked read-only=true")
        } catch (error: Throwable) {
            Log.w("LocationSpoofer", "[NativeSensorTrace] unavailable: ${error.javaClass.simpleName}")
        }
    }

    private external fun startObservation(): Boolean
}
