package com.vincenthzr.locationspoofer.xposed.hooks

import android.os.SystemClock
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject
import java.lang.reflect.Array as ReflectArray
import java.util.Locale
import java.util.Random

/** Both Binder and Messenger scanner paths must expose the same configured environment. */
internal fun buildSystemWifiScanResults(config: JSONObject, loader: ClassLoader): ArrayList<Any> {
    val results = ArrayList<Any>()
    if (!config.optBoolean("mock_wifi", true)) return results
    val clazz = XposedHelpers.findClass("android.net.wifi.ScanResult", loader)
    val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
    fun add(wifi: JSONObject) {
        val result = XposedHelpers.newInstance(clazz)
        val bssid = wifi.optString("bssid", "")
        val rawSsid = wifi.optString("ssid", "")
        val ssid = rawSsid.takeUnless { it.isEmpty() || it == "<unknown ssid>" }
            ?: "WIFI_${bssid.takeLast(5).replace(":", "")}"
        XposedHelpers.setObjectField(result, "SSID", ssid)
        XposedHelpers.setObjectField(result, "BSSID", bssid)
        XposedHelpers.setObjectField(result, "capabilities", wifi.optString("capabilities", "[WPA2-PSK-CCMP][ESS]"))
        XposedHelpers.setIntField(result, "level", wifi.optInt("level", -65))
        XposedHelpers.setIntField(result, "frequency", wifi.optInt("frequency", 2412))
        XposedHelpers.setLongField(result, "timestamp", nowUs)
        val wifiSsid = XposedHelpers.findClassIfExists("android.net.wifi.WifiSsid", loader)
        if (wifiSsid != null) runCatching {
            val value = runCatching { XposedHelpers.callStaticMethod(wifiSsid, "fromBytes", ssid.toByteArray(Charsets.UTF_8)) }
                .getOrElse { XposedHelpers.callStaticMethod(wifiSsid, "createFromAsciiEncoded", ssid) }
            XposedHelpers.setObjectField(result, "wifiSsid", value)
        }
        for ((field, type) in listOf("informationElements" to "InformationElement", "radioChainInfos" to "RadioChainInfo")) {
            runCatching {
                val item = XposedHelpers.findClass("android.net.wifi.ScanResult\$$type", loader)
                XposedHelpers.setObjectField(result, field, ReflectArray.newInstance(item, 0))
            }
        }
        results.add(result)
    }
    val environment = config.optJSONObject("wifi_json")
    if (environment?.optBoolean("isConnected", false) == true) environment.optJSONObject("connectedWifi")?.let(::add)
    environment?.optJSONArray("nearbyWifi")?.let { array ->
        for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add)
    }
    if (results.isEmpty()) {
        val seed = (config.optDouble("lat", 0.0) * 100000).toLong() xor (config.optDouble("lng", 0.0) * 100000).toLong()
        val rng = Random(seed)
        repeat(5) {
            val wifi = JSONObject()
                .put("ssid", "WIFI_${rng.nextInt(9000) + 1000}")
                .put("bssid", String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x", *Array<Any>(6) { rng.nextInt(256) }))
                .put("level", -40 - rng.nextInt(50))
                .put("frequency", if (rng.nextBoolean()) 2412 else 5180)
            add(wifi)
        }
    }
    return results
}
