package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import android.os.Build
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject

/** Some LineageOS 18.1 installations have no network location backend. */
internal object Android11LocationSupport {
    fun installProviderAliases(module: LocationHooker, service: Class<*>) {
        if (Build.VERSION.SDK_INT != 30 || service.declaredMethods.none { it.name == "getLocationProviderManager" }) return
        fun missingNetwork(owner: Any?, config: JSONObject?): Boolean = owner != null && config != null &&
            SystemHookUtils.isTargetCaller(owner, null, config) &&
            XposedHelpers.callMethod(owner, "getLocationProviderManager", "network") == null &&
            XposedHelpers.callMethod(owner, "getLocationProviderManager", "gps") != null
        for (name in listOf("getProviders", "getAllProviders")) {
            XposedHelpers.hookAllMethods(service, name) { chain, _ ->
                val original = chain.proceed(chain.args.toTypedArray())
                if (missingNetwork(chain.thisObject, module.readConfig())) {
                    val providers = (original as? List<*>)?.filterIsInstance<String>() ?: return@hookAllMethods original
                    return@hookAllMethods (providers + "network").distinct()
                }
                original
            }
        }
        XposedHelpers.hookAllMethods(service, "getProviderProperties") { chain, _ ->
            val args = chain.args.toTypedArray()
            if (args.firstOrNull() == "network" && missingNetwork(chain.thisObject, module.readConfig())) args[0] = "gps"
            chain.proceed(args)
        }
    }

    fun registrationArgs(service: Any?, args: List<Any?>, config: JSONObject?): Array<Any?> {
        val original = args.toTypedArray()
        if (Build.VERSION.SDK_INT != 30 || service == null || config == null ||
            SystemHookUtils.extractProvider(args) != "network" ||
            !SystemHookUtils.isTargetCaller(service, SystemHookUtils.extractPackageName(args), config)) return original
        return try {
            if (XposedHelpers.callMethod(service, "getLocationProviderManager", "network") != null) return original
            if (XposedHelpers.callMethod(service, "getLocationProviderManager", "gps") == null) return original
            val index = args.indexOfFirst { it?.javaClass?.name == "android.location.LocationRequest" }
            if (index < 0) return original
            val request = args[index]!!
            val copy = request.javaClass.getConstructor(request.javaClass).newInstance(request)
            XposedHelpers.callMethod(copy, "setProvider", "gps")
            original[index] = copy
            original
        } catch (_: Throwable) { original }
    }
}
