package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import android.os.Message
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import com.vincenthzr.locationspoofer.xposed.hooks.buildSystemWifiScanResults
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import java.lang.reflect.Array as ReflectArray

/** API 30 routes getSingleScanResults through Messenger rather than an IWifiScanner method. */
internal fun LocationHooker.installAndroid11WifiScannerHooks(service: Class<*>, loader: ClassLoader) {
    val scanner = XposedHelpers.findClass("android.net.wifi.WifiScanner", loader)
    val command = scanner.getDeclaredField("CMD_GET_SINGLE_SCAN_RESULTS").apply { isAccessible = true }.getInt(null)
    val wrapper = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ParcelableScanResults", loader)
    val scanResult = XposedHelpers.findClass("android.net.wifi.ScanResult", loader)
    XposedHelpers.hookAllMethods(service, "replySucceeded") { chain, _ ->
        val message = chain.args.firstOrNull() as? Message
        val config = readConfig()
        // Authorization already ran in ClientHandler. Use the carried UID, never the handler's UID.
        if (message != null && message.what == command && config != null &&
            SystemHookUtils.isTargetCaller(chain.thisObject, null, config, message.sendingUid)) {
            try {
                val results = buildSystemWifiScanResults(config, loader)
                val array = ReflectArray.newInstance(scanResult, results.size)
                results.forEachIndexed { index, result -> ReflectArray.set(array, index, result) }
                // Copy the reply, leaving physical cache and request message untouched.
                val reply = Message.obtain(message)
                try {
                    reply.obj = XposedHelpers.newInstance(wrapper, array)
                    return@hookAllMethods chain.proceed(arrayOf(reply))
                } finally { reply.recycle() }
            } catch (error: Throwable) {
                XposedBridge.log("[SysWifi] Android 11 scan reply failed: $error")
            }
        }
        chain.proceed(chain.args.toTypedArray())
    }
    XposedBridge.log("[SysWifi] Android 11 single scans hooked via replySucceeded(Message)")
}
