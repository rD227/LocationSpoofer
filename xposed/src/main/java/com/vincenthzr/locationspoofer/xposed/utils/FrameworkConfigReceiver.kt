package com.vincenthzr.locationspoofer.xposed.utils

import android.content.SharedPreferences
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** All framework access and JSON decoding happen outside the hooked call path. */
internal class FrameworkConfigReceiver(
    private val getPreferences: () -> SharedPreferences,
    private val readFile: (String) -> String,
    private val onConfig: (FrameworkConfigSnapshot) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val reconcileFile: Boolean = false
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "LocationSpoofer-ConfigReceiver").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var preferences: SharedPreferences? = null
    private var lastSnapshot: String? = null
    private var lastPublishedAt = Long.MIN_VALUE
    private var lastSnapshotId: String? = null
    private var lastErrorAt = 0L
    // Keep a strong reference: SharedPreferences implementations may store weak listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == FrameworkConfigChannel.SNAPSHOT_KEY) {
            if (!closed) runCatching { executor.execute { refresh() } }
        }
    }

    fun start() {
        // Module lifecycle initialization precedes hook installation. Hook reads remain memory-only.
        refresh()
        // Retry early-boot failures and reconcile the latest snapshot if a notification was missed.
        executor.scheduleWithFixedDelay({ refresh() }, 1, 1, TimeUnit.SECONDS)
    }

    @Synchronized
    internal fun refresh() {
        if (closed) return
        try {
            val prefs = preferences ?: getPreferences().also {
                it.registerOnSharedPreferenceChangeListener(listener)
                preferences = it
            }
            prefs.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)?.let { accept(it) }
        } catch (error: Throwable) {
            // Leave the last valid memory snapshot intact; a failed blob read is retried next tick.
            preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
            preferences = null
            reportError(error)
        }
        if (reconcileFile && !closed) {
            try {
                // getString reads the framework's local cache; polling it cannot recover a lost
                // Binder notification. openRemoteFile makes a fresh IPC request instead.
                accept(readFile(FrameworkConfigChannel.CURRENT_SNAPSHOT_FILE), "libxposed:remote-file-reconciled")
            } catch (_: java.io.FileNotFoundException) {
                // Older module apps do not publish the reconciliation file.
            } catch (error: Throwable) {
                reportError(error)
            }
        }
    }

    private fun accept(text: String, source: String? = null) {
        if (text == lastSnapshot) return
        val snapshot = FrameworkConfigSnapshot.decode(text, readFile)
        if (closed || snapshot.id == lastSnapshotId || snapshot.publishedAt < lastPublishedAt) return
        onConfig(if (source == null) snapshot else snapshot.copy(source = source))
        lastSnapshot = text
        lastPublishedAt = snapshot.publishedAt
        lastSnapshotId = snapshot.id
    }

    private fun reportError(error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastErrorAt >= 60_000) {
            lastErrorAt = now
            runCatching { onError(error) }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        executor.shutdownNow()
        preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
        preferences = null
    }
}
