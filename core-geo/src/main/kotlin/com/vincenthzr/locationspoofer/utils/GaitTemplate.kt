package com.vincenthzr.locationspoofer.utils

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AccelSample(val timestampNanos: Long, val x: Float, val y: Float, val z: Float)
data class GyroSample(val timestampNanos: Long, val x: Float, val y: Float, val z: Float)

/**
 * 用户录制的个人步态模板：一个跨步（左右脚各一步）周期内的三轴加速度（含重力），
 * 以及可选的三轴角速度（rad/s）。v1 模板仅含加速度，v2 同时保存陀螺仪曲线。
 *
 * 只保存平均后的单个周期而不是原始记录：一是体积小（约 1–3KB，可以直接放进配置文件跨进程下发），
 * 二是回放时由 [MotionRealism] 按当前步频拉伸、逐步叠加力度差异和噪声，避免原样循环同一段录音被识破。
 */
class GaitTemplate(
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val cadenceSpm: Int,
    val strideCount: Int,
    val gyroX: FloatArray? = null,
    val gyroY: FloatArray? = null,
    val gyroZ: FloatArray? = null
) {
    init {
        require(x.size == SAMPLES && y.size == SAMPLES && z.size == SAMPLES)
        require(listOf(x, y, z).all { axis -> axis.all { it.isFinite() } })
        require(cadenceSpm > 0 && strideCount > 0)
        val gyro = listOf(gyroX, gyroY, gyroZ)
        require(gyro.all { it == null } || gyro.all { axis ->
            axis != null && axis.size == SAMPLES && axis.all { it.isFinite() }
        })
    }

    private val mean = floatArrayOf(x.average().toFloat(), y.average().toFloat(), z.average().toFloat())
    val hasGyroscope: Boolean get() = gyroX != null

    /** Mean gravity direction in the recorded device axes; magnitude is one g. */
    fun gravity(): FloatArray {
        val norm = sqrt(mean.sumOf { it.toDouble() * it })
        return if (norm > 1e-6) FloatArray(3) { (mean[it] * 9.80665 / norm).toFloat() }
        else floatArrayOf(0f, 0f, 9.80665f)
    }

    /** Recorded angular velocity in rad/s, sharing the accelerometer stride phase. */
    fun sampleGyroscope(stridePhase: Double, strength: Double): FloatArray? {
        if (!hasGyroscope) return null
        val pos = (stridePhase - floor(stridePhase)) * SAMPLES
        return arrayOf(gyroX!!, gyroY!!, gyroZ!!).map {
            val i = pos.toInt() % SAMPLES
            val fraction = pos - floor(pos)
            ((it[i] * (1 - fraction) + it[(i + 1) % SAMPLES] * fraction) * strength).toFloat()
        }.toFloatArray()
    }

    /**
     * @param stridePhase 跨步相位，只取小数部分
     * @param strength 动态分量（相对周期均值的部分）的缩放；均值代表重力方向，保持不变
     */
    fun sample(stridePhase: Double, strength: Double): FloatArray {
        val pos = (stridePhase - floor(stridePhase)) * SAMPLES
        val i = pos.toInt() % SAMPLES
        val j = (i + 1) % SAMPLES
        val frac = (pos - floor(pos)).toFloat()
        val axes = arrayOf(x, y, z)
        return FloatArray(3) { a ->
            val v = axes[a][i] * (1 - frac) + axes[a][j] * frac
            mean[a] + ((v - mean[a]) * strength).toFloat()
        }
    }

    fun encode(): String = buildString {
        append(if (hasGyroscope) VERSION else LEGACY_VERSION).append(';').append(cadenceSpm).append(';').append(strideCount)
        val axes = listOf(x, y, z) + if (hasGyroscope) listOf(gyroX!!, gyroY!!, gyroZ!!) else emptyList()
        for (axis in axes) {
            append(';')
            axis.joinTo(this, ",") { String.format(Locale.US, "%.3f", it) }
        }
    }

    sealed class Extraction {
        class Success(val template: GaitTemplate) : Extraction()
        class Failure(val reason: Reason) : Extraction()
    }

    enum class Reason { TOO_SHORT, NO_RHYTHM, TOO_FEW_STRIDES }

    companion object {
        const val SAMPLES = 64
        const val MIN_RECORDING_SEC = 8.0
        const val MIN_STRIDES = 4
        private const val LEGACY_VERSION = "v1"
        private const val VERSION = "v2"
        private const val RATE_HZ = 50.0

        fun decode(encoded: String?): GaitTemplate? {
            if (encoded.isNullOrBlank()) return null
            return try {
                val parts = encoded.split(';')
                val hasGyro = parts[0] == VERSION && parts.size == 9
                if (!hasGyro && !(parts[0] == LEGACY_VERSION && parts.size == 6)) return null
                val axes = parts.drop(3).map { p -> p.split(',').map { it.toFloat() }.toFloatArray() }
                GaitTemplate(axes[0], axes[1], axes[2], parts[1].toInt(), parts[2].toInt(),
                    axes.getOrNull(3), axes.getOrNull(4), axes.getOrNull(5))
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 从一段边走路边录制的加速度计数据中提取步态模板：
         * 重采样到固定频率 → 用加速度模长的自相关求出单步周期 → 找出每一步的冲击峰 →
         * 以"同一只脚起步"的相邻两步为一个跨步切片 → 各跨步重采样到 [SAMPLES] 点后取平均。
         */
        fun extract(samples: List<AccelSample>, gyroscope: List<GyroSample> = emptyList()): Extraction {
            if (samples.size < 2) return Extraction.Failure(Reason.TOO_SHORT)
            val sorted = samples.sortedBy { it.timestampNanos }
            val durationSec = (sorted.last().timestampNanos - sorted.first().timestampNanos) / 1e9
            if (durationSec < MIN_RECORDING_SEC) return Extraction.Failure(Reason.TOO_SHORT)

            val n = (durationSec * RATE_HZ).toInt()
            val rx = resample(sorted, n) { it.x }
            val ry = resample(sorted, n) { it.y }
            val rz = resample(sorted, n) { it.z }

            val magnitude = DoubleArray(n) { sqrt((rx[it] * rx[it] + ry[it] * ry[it] + rz[it] * rz[it]).toDouble()) }
            val trend = movingAverage(magnitude, (RATE_HZ).toInt())
            val signal = movingAverage(DoubleArray(n) { magnitude[it] - trend[it] }, 5)

            val stepLag = estimateStepLag(signal) ?: return Extraction.Failure(Reason.NO_RHYTHM)
            val peaks = findPeaks(signal, (stepLag * 0.6).toInt())

            val strideLen = 2 * stepLag
            val strides = ArrayList<IntRange>()
            var k = 0
            while (k + 2 < peaks.size) {
                val start = peaks[k]
                val end = peaks[k + 2]
                if (abs(end - start - strideLen) <= strideLen * 0.25) strides += start until end
                k += 2
            }
            if (strides.size < MIN_STRIDES) return Extraction.Failure(Reason.TOO_FEW_STRIDES)

            fun average(axis: FloatArray) = FloatArray(SAMPLES) { s ->
                strides.map { r ->
                    val pos = r.first + (r.last + 1 - r.first) * s.toDouble() / SAMPLES
                    interpolate(axis, pos)
                }.average().toFloat()
            }

            val meanStrideSec = strides.map { (it.last + 1 - it.first) / RATE_HZ }.average()
            val cadence = (120.0 / meanStrideSec).roundToInt()
            // Use accelerometer timestamps and stride boundaries for BOTH streams. Independently
            // normalizing each recording would shift the gyro phase when sensor startup is delayed.
            val gyro = gyroscope.filter { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
                .sortedBy { it.timestampNanos }
                .distinctBy { it.timestampNanos }
                .map { AccelSample(it.timestampNanos, it.x, it.y, it.z) }
            val t0 = sorted.first().timestampNanos
            val firstStrideTime = t0 + (strides.first().first / RATE_HZ * 1e9).toLong()
            val lastStrideTime = t0 + ((strides.last().last + 1) / RATE_HZ * 1e9).toLong()
            val usableGyro = gyro.size >= 2 && gyro.first().timestampNanos <= firstStrideTime &&
                gyro.last().timestampNanos >= lastStrideTime &&
                gyro.zipWithNext().none { (a, b) -> b.timestampNanos - a.timestampNanos > 250_000_000L }
            val gyroAxes = if (usableGyro) listOf<(AccelSample) -> Float>({ it.x }, { it.y }, { it.z })
                .map { average(resample(gyro, n, t0, it)) } else emptyList()
            return Extraction.Success(GaitTemplate(average(rx), average(ry), average(rz), cadence, strides.size,
                gyroAxes.getOrNull(0), gyroAxes.getOrNull(1), gyroAxes.getOrNull(2)))
        }

        private fun resample(sorted: List<AccelSample>, n: Int, t0: Long = sorted.first().timestampNanos, axis: (AccelSample) -> Float): FloatArray {
            val out = FloatArray(n)
            var j = 0
            for (i in 0 until n) {
                val t = t0 + (i / RATE_HZ * 1e9).toLong()
                while (j < sorted.size - 2 && sorted[j + 1].timestampNanos < t) j++
                val a = sorted[j]
                val b = sorted[j + 1]
                val span = (b.timestampNanos - a.timestampNanos).coerceAtLeast(1L)
                val f = ((t - a.timestampNanos).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
                out[i] = axis(a) * (1 - f) + axis(b) * f
            }
            return out
        }

        private fun movingAverage(data: DoubleArray, window: Int): DoubleArray {
            val half = window / 2
            return DoubleArray(data.size) { i ->
                val from = (i - half).coerceAtLeast(0)
                val to = (i + half).coerceAtMost(data.size - 1)
                var sum = 0.0
                for (k in from..to) sum += data[k]
                sum / (to - from + 1)
            }
        }

        /** 单步周期（采样点数），对应 70~240 步/分钟；若最大相关落在跨步周期上，退回到它的一半 */
        private fun estimateStepLag(signal: DoubleArray): Int? {
            val minLag = (0.25 * RATE_HZ).toInt()
            val maxLag = (0.85 * RATE_HZ).toInt()
            if (signal.size < maxLag * 4) return null
            val energy = signal.sumOf { it * it }
            if (energy <= 0.0) return null
            fun corr(lag: Int): Double {
                var s = 0.0
                for (i in 0 until signal.size - lag) s += signal[i] * signal[i + lag]
                return s / energy
            }
            var best = minLag
            var bestCorr = Double.NEGATIVE_INFINITY
            for (lag in minLag..maxLag) {
                val c = corr(lag)
                if (c > bestCorr) {
                    bestCorr = c
                    best = lag
                }
            }
            if (bestCorr < 0.2) return null
            val half = best / 2
            if (half >= minLag && corr(half) > 0.8 * bestCorr) return half
            return best
        }

        private fun findPeaks(signal: DoubleArray, minDistance: Int): List<Int> {
            val std = sqrt(signal.sumOf { it * it } / signal.size)
            val peaks = ArrayList<Int>()
            for (i in 1 until signal.size - 1) {
                if (signal[i] < 0.3 * std || signal[i] < signal[i - 1] || signal[i] < signal[i + 1]) continue
                if (peaks.isNotEmpty() && i - peaks.last() < minDistance) {
                    if (signal[i] > signal[peaks.last()]) peaks[peaks.size - 1] = i
                } else {
                    peaks += i
                }
            }
            return peaks
        }

        private fun interpolate(axis: FloatArray, pos: Double): Double {
            val i = pos.toInt().coerceIn(0, axis.size - 1)
            val j = (i + 1).coerceAtMost(axis.size - 1)
            val f = pos - i
            return axis[i] * (1 - f) + axis[j] * f
        }
    }
}
