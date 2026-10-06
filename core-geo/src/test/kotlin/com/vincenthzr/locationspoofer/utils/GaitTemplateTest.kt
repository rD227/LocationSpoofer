package com.vincenthzr.locationspoofer.utils

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaitTemplateTest {

    /** 用内置步态波形合成一段"边走边录"的数据：固定步频、带逐步力度差异和传感器噪声，采样间隔也带抖动 */
    private fun syntheticWalk(cadenceSpm: Double, seconds: Double, speed: Double = 1.3): List<AccelSample> {
        val session = MotionRealism.Session(42L, MotionRealism.Level.MEDIUM, 0)
        val rnd = Random(7)
        val out = ArrayList<AccelSample>()
        var tNanos = 1_000_000_000L
        val end = tNanos + (seconds * 1e9).toLong()
        while (tNanos < end) {
            val steps = cadenceSpm / 60.0 * (tNanos - 1_000_000_000L) / 1e9
            val a = session.accelerometer(steps, speed, null, rnd)
            out += AccelSample(tNanos, a[0], a[1], a[2])
            tNanos += 10_000_000L + rnd.nextInt(2_000_000) // ~100Hz，间隔有抖动
        }
        return out
    }

    @Test
    fun `extracts cadence and a template from a walking recording`() {
        val result = GaitTemplate.extract(syntheticWalk(110.0, 20.0))
        assertTrue(result is GaitTemplate.Extraction.Success)
        val template = (result as GaitTemplate.Extraction.Success).template
        assertEquals(110.0, template.cadenceSpm.toDouble(), 4.0)
        assertTrue(template.strideCount >= GaitTemplate.MIN_STRIDES)
        // 模板保留重力：竖直轴一个周期的均值约等于 g
        assertEquals(9.8, template.z.average(), 0.3)
    }

    @Test
    fun `extracts running cadence too`() {
        val result = GaitTemplate.extract(syntheticWalk(170.0, 15.0, speed = 3.2))
        assertTrue(result is GaitTemplate.Extraction.Success)
        assertEquals(170.0, (result as GaitTemplate.Extraction.Success).template.cadenceSpm.toDouble(), 6.0)
    }

    @Test
    fun `rejects recordings that are too short or have no rhythm`() {
        val short = GaitTemplate.extract(syntheticWalk(110.0, 3.0))
        assertEquals(GaitTemplate.Reason.TOO_SHORT, (short as GaitTemplate.Extraction.Failure).reason)

        val rnd = Random(3)
        val still = (0 until 1500).map { AccelSample(it * 10_000_000L, 0f, 0f, 9.8f + (rnd.nextGaussian() * 0.02).toFloat()) }
        val result = GaitTemplate.extract(still)
        assertTrue(result is GaitTemplate.Extraction.Failure)
    }

    @Test
    fun `encode and decode round trip`() {
        val template = (GaitTemplate.extract(syntheticWalk(110.0, 20.0)) as GaitTemplate.Extraction.Success).template
        val decoded = GaitTemplate.decode(template.encode())
        assertNotNull(decoded)
        assertEquals(template.cadenceSpm, decoded!!.cadenceSpm)
        assertEquals(template.strideCount, decoded.strideCount)
        for (i in 0 until GaitTemplate.SAMPLES) {
            assertEquals(template.z[i], decoded.z[i], 0.001f)
        }
        assertNull(GaitTemplate.decode("garbage"))
        assertNull(GaitTemplate.decode(""))
    }

    @Test
    fun `playback keeps the gravity mean while scaling the dynamic part`() {
        val template = (GaitTemplate.extract(syntheticWalk(110.0, 20.0)) as GaitTemplate.Extraction.Success).template
        val n = 1000
        val normal = (0 until n).map { template.sample(it.toDouble() / n, 1.0)[2] }
        val strong = (0 until n).map { template.sample(it.toDouble() / n, 1.5)[2] }
        assertEquals(normal.average(), strong.average(), 0.01)
        assertTrue(strong.max() - strong.min() > normal.max() - normal.min())
    }

    @Test
    fun `gyro recording shares acceleration time origin despite different rate and delayed startup`() {
        val acceleration = syntheticWalk(110.0, 20.0)
        val gyro = acceleration.filterIndexed { index, _ -> index % 3 == 1 }.map {
            GyroSample(it.timestampNanos, it.x * 0.1f, it.y * 0.1f, it.z * 0.1f)
        }
        val template = (GaitTemplate.extract(acceleration, gyro) as GaitTemplate.Extraction.Success).template
        assertTrue(template.hasGyroscope)
        for (i in 0 until GaitTemplate.SAMPLES) {
            assertEquals(template.x[i] * 0.1f, template.gyroX!![i], 0.025f)
            assertEquals(template.z[i] * 0.1f, template.gyroZ!![i], 0.04f)
        }
        val restored = GaitTemplate.decode(template.encode())!!
        assertTrue(template.encode().startsWith("v2;"))
        assertTrue(restored.hasGyroscope)
        for (i in 0 until GaitTemplate.SAMPLES) {
            assertEquals(template.gyroZ!![i], restored.gyroZ!![i], 0.001f)
        }
    }

    @Test
    fun `legacy templates and missing or interrupted gyro recordings fall back cleanly`() {
        val acceleration = syntheticWalk(110.0, 20.0)
        val legacy = (GaitTemplate.extract(acceleration) as GaitTemplate.Extraction.Success).template
        assertTrue(legacy.encode().startsWith("v1;"))
        assertNull(GaitTemplate.decode(legacy.encode())!!.sampleGyroscope(0.2, 1.0))
        val interrupted = acceleration.filterIndexed { index, _ -> index < 100 || index > 300 }.map {
            GyroSample(it.timestampNanos, 1f, 0f, 0f)
        }
        val fallback = (GaitTemplate.extract(acceleration, interrupted) as GaitTemplate.Extraction.Success).template
        assertTrue(!fallback.hasGyroscope)
        assertNull(GaitTemplate.decode(legacy.encode().replaceFirst("v1", "v2")))
        val invalid = legacy.encode().split(';').toMutableList()
        invalid[3] = "NaN," + invalid[3].substringAfter(',')
        assertNull(GaitTemplate.decode(invalid.joinToString(";")))
    }
}
