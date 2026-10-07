package com.vincenthzr.locationspoofer.utils

import org.json.JSONObject

/** Enrich only the sensor clock; the simulation's location settings remain its inputs. */
internal fun publishStepCounterClock(json: JSONObject, previous: JSONObject?, now: Long,
                                     bootCount: Long? = null) {
    val old = previous ?: json
    val prior = StepCounterClock.decode(old.optString(StepCounterClock.CONFIG_KEY))
        ?: StepCounterClock.State(StepCounterClock.INITIAL_STEPS,
            old.optLong("start_timestamp", now).takeIf { it > 0 } ?: now,
            stepCounterParameters(old, now))
    val current = if (bootCount == null) prior else prior.inBoot(bootCount, now)
    json.put(StepCounterClock.CONFIG_KEY,
        StepCounterClock.transition(current, stepCounterParameters(json, now), now).encode())
}

private fun stepCounterParameters(json: JSONObject, now: Long): StepCounterClock.Parameters {
    val epoch = json.optLong("start_timestamp", now).takeIf { it > 0 } ?: now
    val points = json.optJSONArray("route_points")
    val route = json.optBoolean("is_route_mode") && points != null && points.length() >= 2
    val joystick = !route && json.optString("sim_mode") == "JOYSTICK"
    val speed = if (route) json.optDouble("speed_m_s", 3.0).coerceAtLeast(0.1)
        else json.optDouble("speed_m_s", 0.0)
    var end = if (joystick) epoch + 3000L else Long.MAX_VALUE
    var validRoute = route
    if (route) {
        val path = RoutePath((0 until points!!.length()).mapNotNull { index ->
            points.optJSONObject(index)?.let { CoordinateUtils.LatLng(it.optDouble("lat"), it.optDouble("lng")) }
        })
        validRoute = path.isValid
        if (validRoute && json.optBoolean("stop_at_destination")) {
            val remaining = (path.totalDistance - json.optDouble("route_distance_offset", 0.0)).coerceAtLeast(0.0)
            val session = MotionRealism.session(epoch, json.optInt("realism_level"), json.optInt("speed_fluctuation_pct"))
            var low = 0.0
            var high = remaining / (speed * (1 - session.speedFluctuationPct / 100.0)) + 1.0
            repeat(40) {
                val middle = (low + high) / 2
                if (session.distance(speed, middle) >= remaining) high = middle else low = middle
            }
            end = epoch + (high * 1000).toLong()
        }
    }
    val cadence = if (json.optBoolean("is_auto_cadence", false)) StepCounterClock.autoCadence(speed)
        else json.optInt("step_cadence_spm", 130).coerceIn(80, 240)
    return StepCounterClock.Parameters(epoch, cadence.toDouble(),
        json.optBoolean("active") && json.optBoolean("enable_step_simulation", true) &&
            (validRoute || joystick) && speed > 0.05,
        end, json.optInt("realism_level"), json.optInt("speed_fluctuation_pct"))
}
