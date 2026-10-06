package com.vincenthzr.locationspoofer.xposed.hooks

import kotlin.math.floor

/** One detector event for each integer step crossing, on the elapsed-realtime clock. */
internal class StepEventSchedule {
    private var previousSteps = Double.NaN
    private var previousTime = 0L

    fun advance(steps: Double, timestamp: Long): LongArray {
        val oldSteps = previousSteps
        val oldTime = previousTime
        previousSteps = steps
        previousTime = timestamp
        if (!oldSteps.isFinite() || steps <= oldSteps || timestamp <= oldTime) return longArrayOf()
        val first = maxOf(floor(oldSteps).toLong() + 1, floor(steps).toLong() - 15)
        val last = floor(steps).toLong()
        if (first > last) return longArrayOf()
        // Bound catch-up after a suspended process rather than delivering minutes of stale steps.
        return LongArray((last - first + 1).toInt()) { index ->
            val fraction = ((first + index - oldSteps) / (steps - oldSteps)).coerceIn(0.0, 1.0)
            oldTime + ((timestamp - oldTime) * fraction).toLong()
        }
    }
}
