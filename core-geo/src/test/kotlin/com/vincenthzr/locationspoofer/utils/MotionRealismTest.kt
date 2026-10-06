package com.vincenthzr.locationspoofer.utils

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRealismTest {

    private val start = 1_789_398_037_217L

    @Test
    fun `level off with no speed fluctuation keeps the legacy uniform motion`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        assertEquals(3.0 * 125.0, s.distance(3.0, 125.0), 1e-9)
        assertEquals(3.0, s.speed(3.0, 125.0), 1e-12)
        assertEquals(165.0 / 60.0 * 125.0, s.steps(165.0, 125.0), 1e-9)
    }

    @Test
    fun `sessions with the same start timestamp agree across processes`() {
        val a = MotionRealism.Session(start, MotionRealism.Level.HIGH, 20)
        val b = MotionRealism.Session(start, MotionRealism.Level.HIGH, 20)
        val c = MotionRealism.Session(start + 1, MotionRealism.Level.HIGH, 20)
        assertEquals(a.distance(3.0, 777.0), b.distance(3.0, 777.0), 0.0)
        assertEquals(a.steps(160.0, 777.0), b.steps(160.0, 777.0), 0.0)
        assertNotEquals(a.distance(3.0, 777.0), c.distance(3.0, 777.0), 1e-6)
    }

    @Test
    fun `speed stays within the configured fluctuation and matches the distance derivative`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 20)
        var sawVariation = false
        for (t in 0..3600 step 7) {
            val v = s.speed(3.0, t.toDouble())
            assertTrue("speed $v at $t", v in 3.0 * 0.8 - 1e-9..3.0 * 1.2 + 1e-9)
            if (abs(v - 3.0) > 0.1) sawVariation = true
            val h = 1e-3
            val derivative = (s.distance(3.0, t + h) - s.distance(3.0, t.toDouble())) / h
            assertEquals(v, derivative, 1e-3)
        }
        assertTrue(sawVariation)
    }

    @Test
    fun `step count keeps increasing and cadence varies around the base`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.HIGH, 15)
        var last = -1.0
        var minCadence = Double.MAX_VALUE
        var maxCadence = 0.0
        for (t in 0..1800) {
            val steps = s.steps(170.0, t.toDouble())
            assertTrue(steps > last)
            last = steps
            val c = s.cadence(170.0, t.toDouble())
            minCadence = minOf(minCadence, c)
            maxCadence = maxOf(maxCadence, c)
        }
        assertTrue(maxCadence - minCadence > 5)
        assertTrue(minCadence > 170 * 0.85 && maxCadence < 170 * 1.15)
    }

    @Test
    fun `procedural gait averages to gravity over whole cycles`() {
        for (speed in listOf(1.3, 3.2)) {
            val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
            val rnd = Random(1)
            val n = 20_000
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for (i in 0 until n) {
                val stepsFloat = i * 40.0 / n // 恰好 40 步 = 20 个完整跨步
                val a = s.accelerometer(stepsFloat, speed, null, rnd)
                sx += a[0]; sy += a[1]; sz += a[2]
            }
            assertEquals(0.0, sx / n, 0.05)
            assertEquals(0.0, sy / n, 0.05)
            assertEquals(9.80665, sz / n, 0.05)
        }
    }

    @Test
    fun `step impact strength differs between steps when randomness is on`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 0)
        val quiet = Random(0)
        // 关闭噪声的对照：同一相位、不同步序号，冲击峰值应当不同
        val peaks = (0 until 6).map { step -> s.accelerometer(step.toDouble(), 1.4, null, quiet)[2] }
        assertTrue(peaks.toSet().size > 1)
    }

    @Test
    fun `session cache returns the same instance for the same parameters`() {
        val a = MotionRealism.session(start, 2, 10)
        val b = MotionRealism.session(start, 2, 10)
        val c = MotionRealism.session(start, 2, 20)
        assertTrue(a === b)
        assertTrue(a !== c)
    }

    @Test
    fun `linear acceleration removes gravity in recorded device axes`() {
        val template = GaitTemplate(FloatArray(64) { 9.80665f }, FloatArray(64), FloatArray(64), 120, 10)
        val session = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        val linear = session.linearAcceleration(0.25, 1.4, template, Random(1))
        for (axis in linear) assertEquals(0f, axis, 0.0001f)
    }

    @Test
    fun `default gyro varies with stride has zero mean and stops at zero speed`() {
        val session = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        val gyro = (0 until 1000).map { session.gyroscope(it / 500.0, 3.0, 180.0, null, Random(1)) }
        for (axis in 0..2) {
            assertEquals(0.0, gyro.map { it[axis] }.average(), 0.001)
            assertTrue(gyro.maxOf { it[axis] } - gyro.minOf { it[axis] } > 0.4f)
            assertTrue(gyro.all { it[axis].isFinite() && abs(it[axis]) < 5f })
        }
        assertTrue(session.gyroscope(0.2, 0.0, 180.0, null, Random(1)).all { it == 0f })
    }

    @Test
    fun `recorded gyro uses stride phase and scales angular velocity with cadence`() {
        val curve = FloatArray(64) { kotlin.math.sin(2 * Math.PI * it / 64).toFloat() }
        val template = GaitTemplate(FloatArray(64), FloatArray(64), FloatArray(64) { 9.80665f },
            120, 10, curve, curve, curve)
        val session = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        val recorded = session.gyroscope(0.5, 3.0, 120.0, template, Random(1))
        val faster = session.gyroscope(0.5, 3.0, 180.0, template, Random(1))
        for (i in 0..2) {
            assertEquals(1f, recorded[i], 0.0001f)
            assertEquals(1.5f, faster[i], 0.0001f)
        }
    }
}
