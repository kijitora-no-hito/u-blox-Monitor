package jp.kjtr.ublox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** スペクトルと u-blox モニターの表示の計算（spectrum_view.py・monitor_view.py の純ロジック部分）。 */
class MonitorViewTest {
    private fun block(center: Long, level: Double, peak: Int = -1) =
        SpanBlock(DoubleArray(Ubx.SPAN_BINS) { if (it == peak) level + 10 else level }, 128_000_000, 500_000, center, 5)

    @Test
    fun niceTicksLikePython() {
        // 値は Python 版 nice_ticks の結果と同じ
        assertEquals(listOf(1540.0, 1560.0, 1580.0, 1600.0, 1620.0, 1640.0), Spectrum.niceTicks(1522.0, 1650.0, 8))
        assertEquals(listOf(10.0, 20.0, 30.0), Spectrum.niceTicks(8.0, 32.0, 4))
        assertEquals(listOf(0.2, 0.4), Spectrum.niceTicks(0.11, 0.45, 2))
        // 負の値（int() は 0 方向に切り捨て）
        assertEquals(listOf(-10.0, -5.0, 0.0, 5.0), Spectrum.niceTicks(-12.0, 6.0, 4))
    }

    @Test
    fun traceHoldsMaxAndMeanAndResetsOnRetune() {
        val t = SpectrumTrace()
        t.add(block(1586_000_000, 20.0, peak = 10))
        t.add(block(1586_000_000, 30.0))
        assertEquals(30.0, t.current!![0], 0.0)
        assertEquals(30.0, t.maximum!![10], 0.0)   // 20+10 と 30 の大きい方
        assertEquals(25.0, t.mean!![0], 1e-9)
        assertEquals(2, t.count)
        t.add(block(1191_938_000, 5.0))            // 中心が変わったらやり直し
        assertEquals(1, t.count)
        assertEquals(5.0, t.maximum!![0], 0.0)
        assertEquals(1191.938 - 64, t.frequencies!![0], 1e-6)
    }

    @Test
    fun spectrumSetFollowsCountAndSourceChange() {
        val set = SpectrumSet()
        val blocks = listOf(block(1586_000_000, 20.0), block(1191_938_000, 21.0), block(1254_906_000, 22.0))
        assertTrue(set.update(blocks, 1))
        assertFalse(set.update(blocks, 1))         // 同じ受信数は反映しない
        assertEquals(3, set.traces.size)
        assertTrue(set.update(blocks, 2))
        assertEquals(2, set.traces[0].count)
        set.clear()
        assertTrue(set.traces.isEmpty())
        assertTrue(set.update(blocks, 3))
        assertEquals(1, set.traces[0].count)
        assertTrue(set.update(blocks, 1))          // 受信数が戻った = データ源が作り直された
        assertEquals(1, set.traces[0].count)
        assertFalse(set.update(null, 5))
    }

    @Test
    fun formatAndTitleLikePython() {
        assertEquals("1540", Spectrum.formatG(1540.0))
        assertEquals("0.2", Spectrum.formatG(0.2))
        assertEquals("-5", Spectrum.formatG(-5.0))
        assertEquals("1575.42", Spectrum.formatG(1575.42))
        val b = SpanBlock(DoubleArray(256), 128_000_000, 500_000, 1_191_938_000, 5)
        assertEquals("中心 1191.938 MHz  幅 128 MHz  分解能 500 kHz  PGA 5 dB    縦軸: レベル [dB]", Spectrum.blockTitle(b))
    }

    @Test
    fun spectrumStatusText() {
        assertEquals("MON-SPAN 受信 4 回", Spectrum.statusText(4, true, 9))
        assertEquals("MON-SPAN を要求しています。", Spectrum.statusText(0, true, 2))
        assertTrue(Spectrum.statusText(0, true, 3).startsWith("MON-SPAN の応答がありません。"))
        assertTrue(Spectrum.statusText(0, false, 0).startsWith("接続すると"))
    }

    @Test
    fun systemOffsetsMatchPcLayout() {
        assertEquals(20, MonitorMath.systemOffset("GPS"))
        assertEquals(20 + 12 * 48 + 35, MonitorMath.systemOffset("GLONASS"))
        assertEquals(20 + 2 * (12 * 48 + 35), MonitorMath.systemOffset("GALILEO"))
        assertEquals(20, MonitorMath.systemOffset("BEIDOU"))
        assertEquals(20 + 24 * 48 + 35, MonitorMath.systemOffset("QZSS"))
    }

    @Test
    fun bandStatusAndTrendLines() {
        val now = 100.0
        val signals = mapOf(
            0 to SignalObs(45, pr = true, cp = true, t = 99.0),     // GPS L1CA
            3 to SignalObs(40, pr = true, cp = false, t = 99.5),    // GPS L2CL
            7 to SignalObs(30, pr = true, cp = true, t = 90.0),     // GPS L5Q（古い）
        )
        val status = MonitorMath.bandStatus(0, signals, now)
        assertEquals(mapOf("L1" to (true to true), "L2" to (true to false)), status)
        val entry = TrackEntry(signals, 99.5)
        assertEquals(45, MonitorMath.barCno(entry))
        assertEquals(listOf("-", "-", "-", "-", "-"), MonitorMath.measxValues(entry))
        assertTrue(MonitorMath.active(entry, now))

        val items = listOf(
            TrendPoint(100.0, 60, 0, 0, 1, 1, 0 to 0),     // 今・L1
            TrendPoint(-250.0, 30, 3, 63, 0, 0, 0 to 0),   // 5 分より前
            TrendPoint(40.0, 30, 3, 63, 0, 0, 0 to 3),     // L2
            TrendPoint(70.0, 30, 0, 0, 1, 1, null),        // MEASX は L1 扱い
        )
        val l1 = MonitorMath.trendLines(items, "L1", now)
        assertEquals(2, l1[0].size)
        assertEquals(1.0 to 0.0, l1[0][0])                   // C/N0 60 は段の上端
        assertEquals(0.9, l1[0][1].first, 1e-9)              // 30 秒前
        assertEquals(0.75 + (0.8 - 0.7) * 0.25, l1[3][0].second, 1e-9)
        val l2 = MonitorMath.trendLines(items, "L2", now)
        assertEquals(1, l2[1].size)
        assertEquals(0.25, l2[1][0].second, 1e-9)            // MPath 3 は 2 段目の上端
        assertNull(MonitorMath.trendLines(items, "L5", now).firstOrNull { it.isNotEmpty() })
    }

    @Test
    fun monitorSnapshotFromALogHasMeasxValues() {
        val snap = UbxSamples.replay(UbxSamples.log(seconds = 3)).state.monitorSnapshot()
        val gps = snap.tracks.getValue("GPS").getValue(5)
        assertEquals(5, MonitorMath.measxValues(gps).size)
        assertTrue(MonitorMath.measxValues(gps).none { it == "-" })
        assertEquals(36.0, MonitorMath.sfrbxHeight(SfrbxEntry(130, setOf(1))), 1e-9)
    }

    @Test
    fun unsupportedGroupsAndBandsWithData() {
        val x20p = Ubx.parseMonVer(
            UbxStream().feed(UbxSamples.monVer("ZED-X20P", "50.02", "000B0000", listOf("GPS;GAL;BDS", "SBAS;QZSS", "NAVIC"))).single().payload
        )
        assertEquals(setOf("GLONASS"), MonitorMath.unsupportedGroups(x20p))
        assertTrue(MonitorMath.unsupportedGroups(null).isEmpty())
        val trend = TrendSnapshot(
            100.0, emptyMap(),
            mapOf(
                "GPS" to mapOf(5 to listOf(TrendPoint(99.0, 40, 0, 0, 1, 1, 0 to 3), TrendPoint(99.0, 40, 0, 0, 1, 1, null))),
                "GALILEO" to mapOf(4 to listOf(TrendPoint(98.0, 40, 0, 0, 1, 1, 2 to 8))),
                "BEIDOU" to mapOf(21 to listOf(TrendPoint(-300.0, 40, 0, 0, 1, 1, 3 to 7))),   // 5 分より前
            ),
        )
        assertEquals(setOf("L2", "L1", "L6"), MonitorMath.bandsWithData(trend))
    }
}
