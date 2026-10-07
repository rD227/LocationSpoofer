package com.vincenthzr.locationspoofer.utils

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class GaitAttitudeTest {
    private fun template(gravity: Float = 9.80665f): GaitTemplate = GaitTemplate(
        FloatArray(64), FloatArray(64), FloatArray(64) { gravity }, 120, 10,
        FloatArray(64) { sin(2 * PI * it / 64).toFloat() }, FloatArray(64), FloatArray(64))

    @Test fun `vectors stay unit length and gravity stays one g over long counts`() {
        for (gait in listOf(null, template())) for (i in 0..2000) {
            val pose = GaitAttitude.sample(2350.0 + i * 0.031, 4.9, gait, 270.0)
            assertEquals(1.0, pose.rotationVector().sumOf { it.toDouble() * it }, 1e-6)
            assertEquals(GaitAttitude.GRAVITY, sqrt(pose.gravity().sumOf { it.toDouble() * it }), 1e-5)
        }
    }

    @Test fun `recorded gyro provides a continuous periodic attitude without bias drift`() {
        val gait = template()
        for (phase in listOf(-0.3, 0.0, 0.17, 1.9999, 23.99)) {
            val first = GaitAttitude.sample(phase, 3.0, gait).rotationVector()
            val later = GaitAttitude.sample(phase + 10000, 3.0, gait).rotationVector()
            assertArrayEquals(first, later, 1e-6f)
        }
        val a = GaitAttitude.sample(1.999999, 3.0, gait).rotationVector()
        val b = GaitAttitude.sample(2.000001, 3.0, gait).rotationVector()
        assertArrayEquals(a, b, 1e-5f)
        val moving = (0..128).map { GaitAttitude.sample(it / 64.0, 3.0, gait).gravity()[1] }
        assertTrue(moving.max() - moving.min() > 1f)
    }

    @Test fun `stationary side facing and upside down gravity respect recorded axes`() {
        val side = GaitTemplate(FloatArray(64) { 18f }, FloatArray(64), FloatArray(64), 120, 8)
        val down = GaitTemplate(FloatArray(64), FloatArray(64), FloatArray(64) { -15f }, 120, 8)
        assertArrayEquals(floatArrayOf(9.80665f, 0f, 0f), GaitAttitude.sample(0.0, 0.0, side).gravity(), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 0f, -9.80665f), GaitAttitude.sample(0.0, 0.0, down).gravity(), 1e-5f)
    }

    @Test fun `legacy headings are clockwise degrees with documented pitch and roll ranges`() {
        for (heading in listOf(0.0, 90.0, 180.0, 270.0, 359.0)) {
            assertEquals(heading, GaitAttitude.sample(0.0, 0.0, null, heading).orientationDegrees()[0].toDouble(), 1e-4)
        }
        for (i in 0..500) {
            val angles = GaitAttitude.sample(i / 83.0, 5.0, template(), 270.0).orientationDegrees()
            assertTrue(angles[0] >= 0 && angles[0] < 360)
            assertTrue(abs(angles[1]) <= 180 && abs(angles[2]) <= 90)
        }
    }

    @Test fun `android vector layouts preserve extra fields and mark heading uncertainty`() {
        val pose = GaitAttitude.sample(0.25, 3.0, null)
        val full = FloatArray(6) { 42f }
        assertTrue(GaitAttitude.writeValues(11, full, pose))
        assertEquals(-1f, full[4], 0f)
        assertEquals(42f, full[5], 0f)
        assertEquals(1.0, full.take(4).sumOf { it.toDouble() * it }, 1e-6)
        val short = FloatArray(3)
        assertTrue(GaitAttitude.writeValues(20, short, pose))
        assertArrayEquals(full.copyOf(3), short, 0f)
        val game = FloatArray(5) { 42f }
        assertTrue(GaitAttitude.writeValues(15, game, pose))
        assertEquals(42f, game[4], 0f)
        assertFalse(GaitAttitude.writeValues(11, FloatArray(2), pose))
        assertFalse(GaitAttitude.writeValues(2, FloatArray(3), pose))
    }

    @Test fun `acceleration equals linear acceleration plus attitude gravity`() {
        val session = MotionRealism.Session(123456L, MotionRealism.Level.MEDIUM, 10)
        for (gait in listOf(null, template(19f))) for (i in 0..100) {
            val steps = i / 50.0
            val a = session.orientedAccelerometer(steps, 3.0, gait, Random(4))
            val linear = session.linearAcceleration(steps, 3.0, gait, Random(4))
            val gravity = GaitAttitude.sample(steps, 3.0, gait).gravity()
            for (axis in 0..2) assertEquals(a[axis], linear[axis] + gravity[axis], 1e-5f)
        }
        // A recorded magnitude bias cannot become constant synthetic forward acceleration.
        assertArrayEquals(FloatArray(3), MotionRealism.Session(1L, MotionRealism.Level.OFF, 0)
            .linearAcceleration(0.0, 3.0, template(19f), Random(1)), 1e-5f)
    }
}
