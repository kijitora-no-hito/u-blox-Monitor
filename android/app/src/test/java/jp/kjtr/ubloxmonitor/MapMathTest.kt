package jp.kjtr.ubloxmonitor

import jp.kjtr.ubloxmonitor.domain.MapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.5.0: 地図の直近の点のばらつき・距離の表示・ズームの換算。 */
class MapMathTest {
    @Test
    fun scatterInMeters() {
        assertNull(MapMath.scatter(emptyList()))
        assertNull(MapMath.scatter(listOf(35.0 to 139.0)))
        // 北に 2 cm 離れた 2 点 → 平均から各 1 cm
        val dLat = 0.02 / (Math.PI / 180 * 6_378_137.0)
        val (rms, max) = MapMath.scatter(listOf(35.0 to 139.0, 35.0 + dLat to 139.0))!!
        assertEquals(0.01, rms, 1e-6)
        assertEquals(0.01, max, 1e-6)
        // 東西は cos(緯度) を掛ける
        val dLon = 0.02 / (Math.PI / 180 * 6_378_137.0 * Math.cos(Math.toRadians(35.0)))
        assertEquals(0.01, MapMath.scatter(listOf(35.0 to 139.0, 35.0 to 139.0 + dLon))!!.first, 1e-6)
    }

    @Test
    fun distanceText() {
        assertEquals("—", MapMath.formatDistance(null))
        assertEquals("1.4 cm", MapMath.formatDistance(0.014))
        assertEquals("99.9 cm", MapMath.formatDistance(0.999))
        assertEquals("1.250 m", MapMath.formatDistance(1.25))
    }

    @Test
    fun zoomConversion() {
        assertEquals(18.0, MapMath.googleToOsmZoom(17.0, 2f), 1e-9)
        assertEquals(17.0, MapMath.osmToGoogleZoom(18.0, 2f), 1e-9)
        assertEquals(15.0, MapMath.googleToOsmZoom(15.0, 1f), 1e-9)
    }
}
