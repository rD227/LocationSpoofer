package com.vincenthzr.locationspoofer.xposed.hooks

/** Binder used arrays through Android 11, adding baseband C/N0 in API 30. */
internal object GnssCallbackPayload {
    fun legacy(count: Int): Array<Any?> {
        val size = count.coerceIn(4, 32)
        val flags = IntArray(size) { i -> ((i % 12 + 1) shl 8) or ((if (i < 12) 1 else 5) shl 4) or 15 }
        return arrayOf(size, flags, FloatArray(size) { 38f }, FloatArray(size) { 45f },
            FloatArray(size) { it * 360f / size }, FloatArray(size) { 1575420000f })
    }

    fun fromStatus(status: Any, parameterCount: Int): Array<Any?>? {
        if (parameterCount == 1) return arrayOf(status)
        if (parameterCount != 6 && parameterCount != 7) return null
        val methods = status.javaClass.methods
        fun read(name: String, index: Int? = null): Any = methods.first {
            it.name == name && it.parameterCount == if (index == null) 0 else 1
        }.apply { isAccessible = true }.let {
            if (index == null) it.invoke(status) else it.invoke(status, index)
        }
        val count = read("getSatelliteCount") as Int
        val flags = IntArray(count)
        val cn0 = FloatArray(count)
        val elevations = FloatArray(count)
        val azimuths = FloatArray(count)
        val carriers = FloatArray(count)
        val baseband = FloatArray(count)
        for (i in 0 until count) {
            var bits = 0
            if (read("hasEphemerisData", i) as Boolean) bits = bits or 1
            if (read("hasAlmanacData", i) as Boolean) bits = bits or 2
            if (read("usedInFix", i) as Boolean) bits = bits or 4
            if (read("hasCarrierFrequencyHz", i) as Boolean) bits = bits or 8
            if (parameterCount == 7 && read("hasBasebandCn0DbHz", i) as Boolean) bits = bits or 16
            // API 30 reserves eight flag bits; API 24-29 reserve four.
            val typeShift = if (parameterCount == 7) 8 else 4
            flags[i] = ((read("getSvid", i) as Int) shl (typeShift + 4)) or
                ((read("getConstellationType", i) as Int) shl typeShift) or bits
            cn0[i] = read("getCn0DbHz", i) as Float
            elevations[i] = read("getElevationDegrees", i) as Float
            azimuths[i] = read("getAzimuthDegrees", i) as Float
            carriers[i] = read("getCarrierFrequencyHz", i) as Float
            if (parameterCount == 7) baseband[i] = read("getBasebandCn0DbHz", i) as Float
        }
        return if (parameterCount == 7) arrayOf(count, flags, cn0, elevations, azimuths, carriers, baseband)
        else arrayOf(count, flags, cn0, elevations, azimuths, carriers)
    }
}
