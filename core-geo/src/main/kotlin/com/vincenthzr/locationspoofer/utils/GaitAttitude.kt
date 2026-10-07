package com.vincenthzr.locationspoofer.utils

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Periodic pocket attitude, device-to-world quaternion ordered x,y,z,w.
 * Recorded gyro supplies the periodic swing, not an absolute compass calibration.
 */
object GaitAttitude {
    const val GRAVITY = 9.80665

    data class Pose(val x: Double, val y: Double, val z: Double, val w: Double) {
        fun rotationVector(): FloatArray = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

        /** R^T * world gravity, in the same device axes as Android accelerometer values. */
        fun gravity(): FloatArray = floatArrayOf(
            (2 * (x * z - w * y) * GRAVITY).toFloat(),
            (2 * (y * z + w * x) * GRAVITY).toFloat(),
            ((1 - 2 * (x * x + y * y)) * GRAVITY).toFloat()
        )

        /** Legacy TYPE_ORIENTATION: degrees, clockwise azimuth and roll. */
        fun orientationDegrees(): FloatArray {
            val r01 = 2 * (x * y - w * z)
            val r11 = 1 - 2 * (x * x + z * z)
            val r20 = 2 * (x * z - w * y)
            val r21 = 2 * (y * z + w * x)
            val r22 = 1 - 2 * (x * x + y * y)
            val heading = ((atan2(r01, r11) * 180 / PI) % 360 + 360) % 360
            return floatArrayOf(heading.toFloat(), (atan2(-r21, r22) * 180 / PI).toFloat(),
                (asin(r20.coerceIn(-1.0, 1.0)) * 180 / PI).toFloat())
        }

        internal operator fun times(b: Pose): Pose = Pose(
            w * b.x + x * b.w + y * b.z - z * b.y,
            w * b.y - x * b.z + y * b.w + z * b.x,
            w * b.z + x * b.y - y * b.x + z * b.w,
            w * b.w - x * b.x - y * b.y - z * b.z
        )

        internal fun normalized(): Pose {
            val norm = sqrt(x * x + y * y + z * z + w * w)
            val sign = if (w < 0) -1.0 else 1.0
            return Pose(x * sign / norm, y * sign / norm, z * sign / norm, w * sign / norm)
        }
    }

    fun sample(steps: Double, speed: Double, template: GaitTemplate?, bearingDegrees: Double = 0.0): Pose {
        val baseGravity = template?.gravity() ?: floatArrayOf(0f, 0f, GRAVITY.toFloat())
        val gx = baseGravity[0] / GRAVITY
        val gy = baseGravity[1] / GRAVITY
        val gz = baseGravity[2] / GRAVITY
        // Minimal rotation aligning the recorded mean gravity with world +Z.
        val base = if (gz < -0.999999) Pose(1.0, 0.0, 0.0, 0.0)
            else Pose(gy, -gx, 0.0, 1 + gz).normalized()
        val angles = if (speed <= 0.05) DoubleArray(3) else template?.sampleAttitudeAngles(steps / 2) ?: run {
            val phase = PI * steps
            val swing = 0.08 + 0.025 * speed.coerceIn(0.0, 6.0)
            doubleArrayOf(swing * sin(phase), 0.55 * swing * sin(phase + 0.9),
                0.35 * swing * sin(phase - 0.6) + 0.10 * swing * sin(2 * phase))
        }
        val rx = Pose(sin(angles[0] / 2), 0.0, 0.0, cos(angles[0] / 2))
        val ry = Pose(0.0, sin(angles[1] / 2), 0.0, cos(angles[1] / 2))
        val rz = Pose(0.0, 0.0, sin(angles[2] / 2), cos(angles[2] / 2))
        val yaw = -bearingDegrees * PI / 360
        return (Pose(0.0, 0.0, sin(yaw), cos(yaw)) * base * rx * ry * rz).normalized()
    }

    /** Rewrite the advertised Android layout, respecting short legacy arrays and extra slots. */
    fun writeValues(type: Int, values: FloatArray, pose: Pose): Boolean {
        val output = when (type) {
            9 -> pose.gravity()
            3 -> pose.orientationDegrees()
            11, 15, 20 -> pose.rotationVector()
            else -> return false
        }
        if (values.size < 3) return false
        output.copyInto(values, endIndex = minOf(output.size, values.size))
        // Game rotation vector has no heading-accuracy field. Other vectors use -1 when unknown.
        if ((type == 11 || type == 20) && values.size >= 5) values[4] = -1f
        return true
    }
}
