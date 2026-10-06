package com.vincenthzr.locationspoofer.xposed.hooks

import org.junit.Assert.*
import org.junit.Test

class StepEventScheduleTest {
    @Test fun detectorMatches165StepsPerMinuteAt50Hz() {
        val schedule = StepEventSchedule()
        val times = mutableListOf<Long>()
        for (tick in 0..3000) {
            times.addAll(schedule.advance(2350.0 + tick * 0.02 * 165 / 60, tick * 20_000_000L).toList())
        }
        assertEquals(165, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b > a })
        assertEquals(60_000_000_000L, times.last())
    }

    @Test fun delayedTickDeliversEachCrossingInsteadOfOnePerSecond() {
        val schedule = StepEventSchedule()
        assertEquals(0, schedule.advance(100.25, 1_000_000_000L).size)
        assertArrayEquals(longArrayOf(1_250_000_000L, 1_583_333_333L, 1_916_666_666L),
            schedule.advance(103.25, 2_000_000_000L))
    }

    @Test fun attachResetAndSuspensionDoNotReplayHistoricalSteps() {
        val schedule = StepEventSchedule()
        assertEquals(0, schedule.advance(9000.0, 1L).size)
        assertEquals(0, schedule.advance(20.0, 2L).size)
        assertEquals(1, schedule.advance(21.0, 3L).size)
        assertEquals(16, schedule.advance(1000.0, 4L).size)
        assertEquals(0, schedule.advance(1000.0, 5L).size)
    }
}
