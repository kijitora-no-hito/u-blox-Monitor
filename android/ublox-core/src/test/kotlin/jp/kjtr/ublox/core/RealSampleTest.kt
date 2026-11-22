package jp.kjtr.ublox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 実機（ZED-X20P、FTDI 経由 UART1、921600 bps）の 30 秒分のログでの確認。
 * 位置が入っているのでファイルはリポジトリに入れない。環境変数 UBX_SAMPLE か下の既定パスに無ければ skip する。
 */
class RealSampleTest {
    private fun sample(): File? {
        val path = System.getenv("UBX_SAMPLE")
            ?: "C:\\Users\\rei\\AppData\\Local\\Temp\\claude\\2d460853eaa813cc\\8ea81c33-2983-4f19-8570-468db6208541\\scratchpad\\x20p_sample.ubx"
        return File(path).takeIf { it.isFile }
    }

    @Test
    fun x20pLogIsDecodedAndAggregated() {
        val file = sample()
        assumeTrue("実機のサンプルが無いので skip", file != null)
        val clock = ReplayClock()
        val pipeline = Pipeline(clock)
        ReplayRunner(pipeline, FileInputStream(file!!), 0.0, clock).run()
        val snap = pipeline.state.snapshot()
        assertEquals(0, pipeline.stream.checksumErrors)
        assertEquals(30, snap.counts[Ubx.NAV_PVT])
        assertEquals(30, snap.pvtHistory.size)
        assertNotNull(snap.pvt)
        val pvt = snap.pvt!!
        assertTrue(pvt.fixRank >= 4)       // 3D 以上
        assertNotNull(pvt.latitude)
        assertTrue(snap.satellites.isNotEmpty())
        assertTrue(snap.satellites.values.count { it.used } > 0)
        // u-blox モニターの材料（MEASX・SFRBX・RAWX が入っている。MON-SPAN は無い）
        val monitor = pipeline.state.monitorSnapshot()
        val tracked = monitor.tracks.values.flatMap { it.values }.filter { MonitorMath.active(it, monitor.now) }
        assertTrue(tracked.isNotEmpty())
        assertTrue("MEASX の値がある", tracked.any { e -> MonitorMath.measxValues(e).none { it == "-" } })
        assertTrue("RAWX の帯域がある", tracked.any { it.signals.isNotEmpty() })
        assertTrue("SFRBX がある", monitor.sfrbx.values.any { it.isNotEmpty() })
        val gpsSubframes = monitor.sfrbx.getValue("GPS").values.flatMap { it.subframes }.toSet()
        assertTrue("GPS L1C/A のサブフレーム番号 $gpsSubframes", gpsSubframes.isNotEmpty() && gpsSubframes.all { it in 1..5 })
        val trend = pipeline.state.trendSnapshot()
        val l1 = trend.history.getValue("GPS").values.sumOf { MonitorMath.trendLines(it, "L1", trend.now)[0].size }
        assertTrue(l1 > 0)
        val set = SpectrumSet()
        assertTrue(!set.update(pipeline.spectrum, pipeline.spectrumCount))
        println("u-blox モニター: 追尾 ${tracked.size} 機、GPS サブフレーム $gpsSubframes、L1 トレンド $l1 点")
        pipeline.version?.let { v ->
            println("MON-VER: ${v.extensions}")
            ReceiverConfig.supportedSystems(v)?.let { assertTrue("X20P は GLONASS 非対応", "GLO" !in it) }
        }
        println(
            "X20P: ${pvt.fixLabel} 使用 ${pvt.numSv} 機、衛星 ${snap.satellites.size} 機、信号 ${snap.signals.size} 種、" +
                "時計 ${clock.value} 秒、メッセージ ${snap.counts.size} 種 ${snap.counts}"
        )
    }
}
