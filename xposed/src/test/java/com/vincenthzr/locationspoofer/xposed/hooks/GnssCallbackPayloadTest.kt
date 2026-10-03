package com.vincenthzr.locationspoofer.xposed.hooks

import org.junit.Assert.*
import org.junit.Test

class GnssCallbackPayloadTest {
    // Match public GnssStatus getters, including API 30 baseband measurements.
    private class Status {
        fun getSatelliteCount() = 1
        fun getSvid(index: Int) = 21
        fun getConstellationType(index: Int) = 5
        fun hasEphemerisData(index: Int) = true
        fun hasAlmanacData(index: Int) = true
        fun usedInFix(index: Int) = true
        fun hasCarrierFrequencyHz(index: Int) = true
        fun hasBasebandCn0DbHz(index: Int) = true
        fun getCn0DbHz(index: Int) = 38f
        fun getElevationDegrees(index: Int) = 55f
        fun getAzimuthDegrees(index: Int) = 120f
        fun getCarrierFrequencyHz(index: Int) = 1561098000f
        fun getBasebandCn0DbHz(index: Int) = 35.5f
    }

    @Test fun `Android 11 payload round trips satellite identity flags and baseband`() {
        val payload = GnssCallbackPayload.fromStatus(Status(), 7)!!
        assertEquals(7, payload.size)
        assertEquals(1, payload[0])
        val flags = (payload[1] as IntArray)[0]
        assertEquals(21, flags shr 12)
        assertEquals(5, (flags shr 8) and 15)
        assertEquals(31, flags and 31)
        assertArrayEquals(floatArrayOf(35.5f), payload[6] as FloatArray, 0f)
        assertArrayEquals(floatArrayOf(1561098000f), payload[5] as FloatArray, 0f)
    }

    @Test fun `older array signature keeps original packing and has no baseband`() {
        val payload = GnssCallbackPayload.fromStatus(Status(), 6)!!
        assertEquals(6, payload.size)
        val flags = (payload[1] as IntArray)[0]
        assertEquals(21, flags shr 8)
        assertEquals(5, (flags shr 4) and 15)
        assertEquals(15, flags and 15)
    }

    @Test fun `modern parcel signature passes object and unknown signatures are skipped`() {
        val status = Status()
        assertSame(status, GnssCallbackPayload.fromStatus(status, 1)!![0])
        assertNull(GnssCallbackPayload.fromStatus(status, 5))
    }
}
