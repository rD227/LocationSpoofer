package com.locationspoofer.android11probe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;
import android.widget.TextView;
import java.util.*;

/** Independent client: never enable this package in the module's app-process scope. */
public class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<LocationListener> listeners = new ArrayList<>();
    private LocationManager manager;
    private TextView view;
    private GnssStatus.Callback gnss;
    private OnNmeaMessageListener nmea;
    private PendingIntent pending;
    private int updates;
    private boolean removed;
    private int afterRemoval;

    private void report(String text) {
        Log.i("Android11Probe", text);
        view.append(text + "\n");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this);
        view.setTextSize(14);
        view.setPadding(20, 20, 20, 20);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(view);
        setContentView(scroll);
        manager = getSystemService(LocationManager.class);
        report("Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        report("Providers: " + manager.getAllProviders());
        for (String provider : Arrays.asList("gps", "network", "fused", "passive")) {
            try {
                report("LAST " + provider + "=" + manager.getLastKnownLocation(provider));
                LocationListener listener = location -> {
                    updates++;
                    if (removed) afterRemoval++;
                    report("UPDATE " + provider + "=" + location);
                };
                manager.requestLocationUpdates(provider, 1000, 0, listener, Looper.getMainLooper());
                listeners.add(listener);
            } catch (Exception error) { report("ERROR " + provider + ": " + error); }
        }
        try {
            manager.getCurrentLocation("gps", new CancellationSignal(), getMainExecutor(),
                location -> report("CURRENT gps=" + location));
        } catch (Exception error) { report("ERROR CURRENT: " + error); }
        gnss = new GnssStatus.Callback() {
            @Override public void onSatelliteStatusChanged(GnssStatus status) {
                int used = 0;
                for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
                report("GNSS count=" + status.getSatelliteCount() + " used=" + used);
            }
        };
        nmea = (sentence, timestamp) -> { if (sentence.startsWith("$GPGGA")) report("NMEA " + sentence.trim()); };
        try {
            report("GNSS registration=" + manager.registerGnssStatusCallback(gnss, handler));
            report("NMEA registration=" + manager.addNmeaListener(nmea, handler));
            pending = PendingIntent.getBroadcast(this, 1, new Intent(this, UpdatesReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
            manager.requestLocationUpdates("gps", 1000, 0, pending);
        } catch (Exception error) { report("ERROR registration: " + error); }
        try {
            WifiManager wifi = getSystemService(WifiManager.class);
            report("WIFI count=" + wifi.getScanResults().size());
        } catch (Exception error) { report("ERROR WIFI: " + error); }
        handler.postDelayed(() -> {
            cleanup();
            report("REMOVED listeners; updates=" + updates);
            handler.postDelayed(() -> report("AFTER_REMOVAL=" + afterRemoval), 3000);
        }, 12000);
    }

    private void cleanup() {
        removed = true;
        for (LocationListener listener : listeners) manager.removeUpdates(listener);
        listeners.clear();
        if (gnss != null) manager.unregisterGnssStatusCallback(gnss);
        if (nmea != null) manager.removeNmeaListener(nmea);
        if (pending != null) manager.removeUpdates(pending);
    }
    @Override public void onDestroy() { cleanup(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    public static class UpdatesReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            Location location = intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED);
            Log.i("Android11Probe", "PENDING=" + location);
        }
    }
}
