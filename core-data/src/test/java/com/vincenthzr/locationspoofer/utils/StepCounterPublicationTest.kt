package com.vincenthzr.locationspoofer.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StepCounterPublicationTest {
    private val epoch = 1_791_270_000_000L
    private fun route() = JSONObject().put("active", true).put("is_route_mode", true)
        .put("start_timestamp", epoch).put("speed_m_s", 3.0).put("is_auto_cadence", false)
        .put("step_cadence_spm", 165).put("route_points", JSONArray()
            .put(JSONObject().put("lat", 30.0).put("lng", 110.0))
            .put(JSONObject().put("lat", 30.01).put("lng", 110.0)))
    private fun clock(json: JSONObject) = StepCounterClock.decode(json.getString(StepCounterClock.CONFIG_KEY))!!

    @Test fun migrationIncludesAlreadyElapsedRouteTimeAndSurvivesSerializedReconnect() {
        val json = route()
        publishStepCounterClock(json, null, epoch + 60_000)
        assertEquals(2515.0, clock(json).countAt(epoch + 60_000), 1e-8)
        val reconnected = JSONObject(json.toString())
        publishStepCounterClock(reconnected, json, epoch + 120_000)
        assertEquals(2680.0, clock(reconnected).countAt(epoch + 120_000), 1e-8)
        assertEquals(json.getString(StepCounterClock.CONFIG_KEY), reconnected.getString(StepCounterClock.CONFIG_KEY))
    }

    @Test fun pausedRouteAndDisabledSimulationDoNotAccumulateOfflineSteps() {
        val active = route().also { publishStepCounterClock(it, null, epoch) }
        val paused = JSONObject(active.toString()).put("is_route_mode", false)
        publishStepCounterClock(paused, active, epoch + 60_000)
        assertEquals(2515.0, clock(paused).countAt(epoch + 600_000), 1e-8)
        val resumed = JSONObject(paused.toString()).put("is_route_mode", true).put("start_timestamp", epoch + 600_000)
        publishStepCounterClock(resumed, paused, epoch + 600_000)
        assertEquals(2680.0, clock(resumed).countAt(epoch + 660_000), 1e-8)
        val disabled = JSONObject(resumed.toString()).put("enable_step_simulation", false)
        publishStepCounterClock(disabled, resumed, epoch + 660_000)
        assertEquals(2680.0, clock(disabled).countAt(epoch + 1_000_000), 1e-8)
    }

    @Test fun joystickExpiresWithoutUpdatesAndRouteStopsAtDestination() {
        val joystick = route().put("is_route_mode", false).put("sim_mode", "JOYSTICK")
        publishStepCounterClock(joystick, null, epoch)
        assertEquals(2358.25, clock(joystick).countAt(epoch + 60_000), 1e-8)
        val stopped = route().put("stop_at_destination", true)
        publishStepCounterClock(stopped, null, epoch)
        val deadline = clock(stopped).parameters.endMillis
        assertTrue(deadline in (epoch + 300_000)..(epoch + 400_000))
        assertEquals(clock(stopped).countAt(deadline), clock(stopped).countAt(epoch + 1_000_000), 0.0)
    }

    @Test fun invalidRouteFreezesAndOldManualSettingsAreClamped() {
        val invalid = route().put("route_points", JSONArray()).put("step_cadence_spm", 12)
        publishStepCounterClock(invalid, null, epoch)
        assertEquals(2350.0, clock(invalid).countAt(epoch + 60_000), 1e-8)
        assertEquals(80.0, clock(invalid).parameters.cadenceSpm, 0.0)
        val maximum = route().put("step_cadence_spm", 500)
        publishStepCounterClock(maximum, null, epoch)
        assertEquals(240.0, clock(maximum).parameters.cadenceSpm, 0.0)
    }

    @Test fun savedPreviousBootCannotPublishSeventyThousandStepsAfterRestart() {
        val old = route().also { publishStepCounterClock(it, null, epoch, 8) }
        val later = epoch + 26_000_000
        assertTrue(clock(old).countAt(later) > 70_000)
        val restarted = JSONObject(old.toString())
        publishStepCounterClock(restarted, old, later, 9)
        assertEquals(0.0, clock(restarted).countAt(later), 0.0)
        assertEquals(165.0, clock(restarted).countAt(later + 60_000), 1e-8)
        val reconnected = JSONObject(restarted.toString())
        publishStepCounterClock(reconnected, restarted, later + 120_000, 9)
        assertEquals(330.0, clock(reconnected).countAt(later + 120_000), 1e-8)
        assertEquals(restarted.getString(StepCounterClock.CONFIG_KEY), reconnected.getString(StepCounterClock.CONFIG_KEY))
    }

    @Test fun legacyMigrationUsesZeroRatherThanAnInventedInitialCounter() {
        val legacy = route().also { publishStepCounterClock(it, null, epoch) }
        assertNull(clock(legacy).bootCount)
        publishStepCounterClock(legacy, legacy, epoch + 600_000, 3)
        assertEquals(0.0, clock(legacy).countAt(epoch + 600_000), 0.0)
        assertEquals(165.0, clock(legacy).countAt(epoch + 660_000), 1e-8)
    }
}
