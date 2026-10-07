package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.*
import org.junit.Test

class StepCounterClockTest {
    private val epoch = 1_791_270_000_000L
    private val parameters = StepCounterClock.Parameters(epoch, 165.0, true)
    private val clock = StepCounterClock.State(2350.0, epoch, parameters)

    @Test fun newProcessesAndIntermittentReadersShareTheSameCounter() {
        val secondProcess = StepCounterClock.decode(clock.encode())!!
        repeat(3000) { clock.countAt(epoch + it * 20L) }
        assertEquals(2515.0, secondProcess.countAt(epoch + 60_000), 1e-8)
        assertEquals(clock.countAt(epoch + 60_000), secondProcess.countAt(epoch + 60_000), 0.0)
        // An hour without subscribers must not freeze the physical-style cumulative counter.
        assertEquals(12250.0, secondProcess.countAt(epoch + 3_600_000), 1e-8)
    }

    @Test fun pauseResumeAndNewSessionPreservePreviouslyAccumulatedSteps() {
        val paused = StepCounterClock.transition(clock, parameters.copy(running = false), epoch + 60_000)
        assertEquals(2515.0, paused.countAt(epoch + 600_000), 1e-8)
        val resumed = StepCounterClock.transition(paused, parameters.copy(epochMillis = epoch + 600_000), epoch + 600_000)
        assertEquals(2680.0, resumed.countAt(epoch + 660_000), 1e-8)
        assertEquals(2680.0, StepCounterClock.decode(resumed.encode())!!.countAt(epoch + 660_000), 1e-8)
    }

    @Test fun rateChangesDoNotRetroactivelyRewriteTheCounter() {
        val faster = StepCounterClock.transition(clock, parameters.copy(cadenceSpm = 200.0), epoch + 60_000)
        assertEquals(2515.0, faster.countAt(epoch + 60_000), 1e-8)
        assertEquals(2715.0, faster.countAt(epoch + 120_000), 1e-8)
        assertSame(clock, StepCounterClock.transition(clock, parameters, epoch + 180_000))
    }

    @Test fun movementDeadlineStopsCountingAndEarlierWallTimeNeverSubtractsSteps() {
        val limited = clock.copy(parameters = parameters.copy(endMillis = epoch + 60_000))
        assertEquals(2515.0, limited.countAt(epoch + 600_000), 1e-8)
        val reanchored = StepCounterClock.transition(clock, parameters.copy(cadenceSpm = 180.0), epoch + 60_000)
        assertEquals(2515.0, reanchored.countAt(epoch), 1e-8)
    }

    @Test fun noisyClocksAgreeAcrossIndependentReadersAndStayInsideCadenceLimits() {
        for (base in listOf(80.0, 81.0, 165.0, 239.0, 240.0)) {
            val noisy = parameters.copy(cadenceSpm = base, realismLevel = 3, speedFluctuationPct = 30)
            val source = clock.copy(parameters = noisy)
            val restored = StepCounterClock.decode(source.encode())!!
            val session = MotionRealism.session(epoch, 3, 30)
            for (second in 0..3600) {
                val now = epoch + second * 1000L
                val difference = source.countAt(now + 1000) - source.countAt(now)
                assertTrue("$base: $difference", difference >= 80.0 / 60 - 1e-8 && difference <= 4 + 1e-8)
                assertEquals(source.countAt(now), restored.countAt(now), 0.0)
                assertTrue(session.boundedCadence(base, second.toDouble()) in 80.0..240.0)
            }
        }
    }

    @Test fun malformedClockCannotPublishNonfiniteOrOutOfRangeCounts() {
        assertNull(StepCounterClock.decode(""))
        assertNull(StepCounterClock.decode(clock.encode().replace("2350.0", "NaN")))
        assertNull(StepCounterClock.decode(clock.encode().replace("165.0", "241.0")))
        assertNull(StepCounterClock.decode(clock.encode() + ";trailing"))
        assertEquals(parameters, StepCounterClock.decode(clock.encode())!!.parameters)
    }

    @Test fun bootRestartDropsPriorBootCountButProcessRestartKeepsIt() {
        val firstBoot = clock.inBoot(41, epoch)
        assertEquals(0.0, firstBoot.countAt(epoch), 0.0)
        assertEquals(165.0, firstBoot.countAt(epoch + 60_000), 1e-8)
        val restored = StepCounterClock.decode(firstBoot.encode())!!
        assertSame(restored, restored.inBoot(41, epoch + 3_600_000))
        assertEquals(9900.0, restored.countAt(epoch + 3_600_000), 1e-8)
        val rebooted = restored.inBoot(42, epoch + 3_600_000)
        assertEquals(0.0, rebooted.countAt(epoch + 3_600_000), 0.0)
        assertEquals(165.0, rebooted.countAt(epoch + 3_660_000), 1e-8)
        assertEquals(42L, rebooted.bootCount)
    }

    @Test fun pauseAndRateChangeKeepBootIdentityAndSerializedClock() {
        val sameBoot = clock.inBoot(7, epoch)
        val paused = StepCounterClock.transition(sameBoot, parameters.copy(running = false), epoch + 60_000)
        assertEquals(7L, StepCounterClock.decode(paused.encode())!!.bootCount)
        val faster = StepCounterClock.transition(paused, parameters.copy(cadenceSpm = 200.0), epoch + 600_000)
        assertEquals(365.0, faster.countAt(epoch + 660_000), 1e-8)
        assertEquals(7L, faster.bootCount)
        assertNull(StepCounterClock.decode(faster.encode().substringBeforeLast(';') + ";-1"))
    }
}
