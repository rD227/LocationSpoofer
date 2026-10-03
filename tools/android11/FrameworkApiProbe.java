package com.locationspoofer.android11probe;

import android.location.GnssStatus;
import android.net.wifi.ScanResult;
import android.os.SystemClock;
import dalvik.system.PathClassLoader;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.*;

/** Read-only runtime checks against a supplied APK; does not install or enable an Xposed module. */
public class FrameworkApiProbe {
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }
    public static void main(String[] args) throws Exception {
        check(android.os.Build.VERSION.SDK_INT == 30, "Android API 30");
        ClassLoader loader = new PathClassLoader(args[0], FrameworkApiProbe.class.getClassLoader());
        GnssStatus status = new GnssStatus.Builder().addSatellite(5, 21, 38, 55, 120,
            true, true, true, true, 1561098000f, true, 35.5f).build();
        Class<?> payloadClass = loader.loadClass("com.vincenthzr.locationspoofer.xposed.hooks.GnssCallbackPayload");
        Object payloadObject = payloadClass.getField("INSTANCE").get(null);
        Object[] payload = (Object[]) payloadClass.getMethod("fromStatus", Object.class, int.class)
            .invoke(payloadObject, status, 7);
        Method wrap = GnssStatus.class.getDeclaredMethod("wrap", int.class, int[].class,
            float[].class, float[].class, float[].class, float[].class, float[].class);
        wrap.setAccessible(true);
        GnssStatus restored = (GnssStatus) wrap.invoke(null, payload);
        check(restored.getSatelliteCount() == 1 && restored.getSvid(0) == 21 &&
            restored.getConstellationType(0) == 5 && restored.usedInFix(0) &&
            restored.getBasebandCn0DbHz(0) == 35.5f, "7-argument GNSS round trip on real Android 11");
        check(loader.loadClass("com.vincenthzr.locationspoofer.xposed.hooks.GnssCallbackPayload")
            .getMethod("fromStatus", Object.class, int.class).invoke(payloadObject, status, 1) instanceof Object[],
            "modern GNSS object signature retained");
        Class<?> builder = loader.loadClass("com.vincenthzr.locationspoofer.xposed.hooks.SystemWifiScanResultsKt");
        Method build = builder.getMethod("buildSystemWifiScanResults", JSONObject.class, ClassLoader.class);
        JSONObject config = new JSONObject("{\"mock_wifi\":true,\"lat\":27,\"lng\":113,\"wifi_json\":{\"nearbyWifi\":[{\"ssid\":\"Android11-Test\",\"bssid\":\"02:11:22:33:44:55\",\"level\":-60,\"frequency\":2412}]}}");
        List<?> scans = (List<?>) build.invoke(null, config, loader);
        ScanResult scan = (ScanResult) scans.get(0);
        check(scans.size() == 1 && "Android11-Test".equals(scan.SSID), "configured Wi-Fi results");
        check(ScanResult.class.getField("wifiSsid").get(scan) != null, "Android 11 WifiSsid fallback");
        check(Math.abs(SystemClock.elapsedRealtimeNanos() / 1000L - scan.timestamp) < 1000000,
            "Wi-Fi scan timestamp uses microseconds");
        Class<?> wrapper = loader.loadClass("android.net.wifi.WifiScanner$ParcelableScanResults");
        Constructor<?> constructor = wrapper.getDeclaredConstructor(ScanResult[].class);
        constructor.setAccessible(true);
        check(constructor.newInstance((Object) new ScanResult[]{scan}) != null, "legacy scanner reply wrapper");
        config.put("mock_wifi", false);
        check(((List<?>) build.invoke(null, config, loader)).isEmpty(), "Wi-Fi simulation off returns empty results");
        ClassLoader services = new PathClassLoader("/system/framework/services.jar:/apex/com.android.wifi/javalib/service-wifi.jar:/system/framework/wifi-service.jar", loader);
        Class<?> manager = services.loadClass("com.android.server.location.LocationManagerService$LocationProviderManager");
        check(Arrays.stream(manager.getDeclaredMethods()).anyMatch(m -> m.getName().equals("getLastLocation")),
            "nested Android 11 provider manager exists");
        Class<?> service = services.loadClass("com.android.server.wifi.scanner.WifiScanningServiceImpl");
        check(Arrays.stream(service.getDeclaredMethods()).anyMatch(m -> m.getName().equals("replySucceeded") &&
            m.getParameterTypes()[0] == android.os.Message.class), "Wi-Fi scanner replySucceeded(Message) exists");
        check(Arrays.stream(service.getDeclaredMethods()).noneMatch(m -> m.getName().equals("getSingleScanResults")),
            "Wi-Fi scanner requires legacy message route");
        Class<?> request = loader.loadClass("android.location.LocationRequest");
        Object original = request.getConstructor().newInstance();
        request.getMethod("setProvider", String.class).invoke(original, "network");
        Object copy = request.getConstructor(request).newInstance(original);
        request.getMethod("setProvider", String.class).invoke(copy, "gps");
        check("network".equals(request.getMethod("getProvider").invoke(original)) &&
            "gps".equals(request.getMethod("getProvider").invoke(copy)), "provider redirect copies the LocationRequest");
        System.out.println("FRAMEWORK_API_RESULT=PASS");
    }
}
