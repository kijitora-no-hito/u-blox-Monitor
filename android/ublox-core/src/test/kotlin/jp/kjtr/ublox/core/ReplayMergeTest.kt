package jp.kjtr.ublox.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.TimeZone

/** 2 本の UBX ログを GPS 時刻で合わせて再生する（ReplayMerge.kt。0.4.0）。 */
class ReplayMergeTest {
    private val W = GpsTime.WEEK

    /** GPS 時刻 [gps] 秒の NAV-PVT（UTC は gps − 18 秒。[utcValid] = false なら日付・時刻を無効に）。 */
    private fun pvt(gps: Double, utcValid: Boolean = true): ByteArray {
        val tow = Math.floorMod(Math.round(gps * 1000), (W * 1000).toLong())
        val frame = UbxSamples.navPvt(tow)
        val p = frame.copyOfRange(6, frame.size - 2)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = Math.round((gps - GpsTime.LEAP_SECONDS + GpsTime.GPS_EPOCH_UNIX) * 1000)
        val y = cal.get(Calendar.YEAR)
        p[4] = (y and 0xFF).toByte(); p[5] = (y shr 8).toByte()
        p[6] = (cal.get(Calendar.MONTH) + 1).toByte(); p[7] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        p[8] = cal.get(Calendar.HOUR_OF_DAY).toByte(); p[9] = cal.get(Calendar.MINUTE).toByte(); p[10] = cal.get(Calendar.SECOND).toByte()
        p[11] = if (utcValid) 0x07 else 0x00
        return Ubx.build(Ubx.NAV_PVT, p)
    }

    /** GPS 時刻 [gps] 秒の RXM-RAWX（[week] = 0 は週不明）。 */
    private fun rawx(gps: Double, week: Int? = null): ByteArray {
        val frame = UbxSamples.rxmRawx(0.0)
        val p = frame.copyOfRange(6, frame.size - 2)
        val tow = gps - Math.floor(gps / W) * W
        val w = LeWriter().f8(tow).u2(week ?: (gps / W).toInt()).toByteArray()
        w.copyInto(p, 0)
        return Ubx.build(Ubx.RXM_RAWX, p)
    }

    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun segments(bytes: ByteArray): Pair<List<Segment>, UbxSegmentReader> {
        val r = UbxSegmentReader(bytes.inputStream())
        val out = ArrayList<Segment>()
        while (true) out.add(r.next() ?: break)
        return out to r
    }

    /** 2026-11 ごろの GPS 時刻（週 2393） */
    private val T0 = 2393 * W + 300_000.0

    @Test
    fun resolveWeek() {
        assertEquals(2393 * W + 10.0, GpsTime.resolve(10.0, 2393 * W + 100.0), 1e-9)
        // おおよその時刻が週の直前でも、近い方の週
        assertEquals(2394 * W + 10.0, GpsTime.resolve(10.0, 2394 * W - 50.0), 1e-9)
        assertEquals(2393 * W + W - 5.0, GpsTime.resolve(W - 5.0, 2394 * W + 3.0), 1e-9)
    }

    @Test
    fun segmentsKeepAllBytesAndTimes() {
        val nmea = "\$GNGGA,000000.00,,,,,0,00,99.99,,,,,,*00\r\n".toByteArray()
        val bytes = cat(
            byteArrayOf(0x01, 0xB5.toByte(), 0x00), nmea, rawx(T0), UbxSamples.navSat(1000), pvt(T0),
            nmea, rawx(T0 + 1), pvt(T0 + 1), Ubx.build(Ubx.NAV_PVT, ByteArray(92)).also { it[10] = 0x55 }, // 壊れたチェックサム
            rawx(T0 + 2), byteArrayOf(0x24, 0x24),
        )
        val (segs, r) = segments(bytes)
        // 区切りはエポックの時刻が変わったところ（同じ時刻の RAWX と PVT は同じ区切り）。端切れは最後の区切りに入る
        assertEquals(3, segs.size)
        assertArrayEquals(bytes, cat(*segs.map { it.bytes }.toTypedArray()))
        assertEquals(bytes.size.toLong(), r.position)
        val off = r.offset!!
        assertEquals(T0, segs[0].rel!! + off, 1e-6)
        assertEquals(T0 + 1, segs[1].rel!! + off, 1e-6)
        assertEquals(T0 + 2, segs[2].rel!! + off, 1e-6)
    }

    @Test
    fun weekRolloverAndUtcOffset() {
        // 週の境目をまたぐ NAV-PVT だけのログ（UTC から週を決める）
        val start = 2393 * W + W - 2.0
        val bytes = cat(*(0 until 5).map { pvt(start + it) }.toTypedArray())
        val (segs, r) = segments(bytes)
        assertEquals(5, segs.size)
        val off = r.offset!!
        for (i in 0 until 5) assertEquals(start + i, segs[i].rel!! + off, 1e-6)
        // 時刻の無効な NAV-PVT だけ → 本当の時刻は分からない（rel は週内秒の展開のまま）
        val (segs2, r2) = segments(cat(*(0 until 5).map { pvt(start + it, utcValid = false) }.toTypedArray()))
        assertNull(r2.offset)
        assertEquals(W - 2.0, segs2[0].rel!!, 1e-6)
        assertEquals(W + 2.0, segs2[4].rel!!, 1e-6)
        // RXM-RAWX の週 0（不明）は使わない
        assertNull(segments(rawx(T0, week = 0)).second.offset)
    }

    /** 流された順に (ファイル, GPS 時刻) を記録する。 */
    private fun runMerge(a: ByteArray, b: ByteArray?, startAt: Double = 0.0, speed: Double = 0.0): Pair<List<Pair<Int, Double>>, MergedReplayRunner> {
        val order = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Double>>())
        val clock = ReplayClock()
        val inputs = listOfNotNull(a, b).mapIndexed { i, bytes ->
            val p = Pipeline(clock) {}
            p.addPvtListener { pvt -> order.add(i to pvt.itow / 1000.0) }
            ReplayInput(p, bytes.inputStream(), bytes.size.toLong())
        }
        val runner = MergedReplayRunner(inputs, speed, clock, startAt)
        runner.run()
        return order to runner
    }

    @Test
    fun mergesByGpsTime() {
        // u-blox: T0..T0+9 の 1 Hz（RAWX → PVT）。内蔵: T0+3.5..T0+7.5（PVT だけ。UTC あり）
        val ublox = cat(*(0 until 10).flatMap { listOf(rawx(T0 + it), pvt(T0 + it)) }.toTypedArray())
        val phone = cat(*(0 until 5).map { pvt(T0 + 3.5 + it) }.toTypedArray())
        val (order, runner) = runMerge(ublox, phone)
        assertEquals(15, order.size)
        // 時刻の順（週内秒で比べてよい）
        for (k in 1 until order.size) assertTrue(order.toString(), order[k].second >= order[k - 1].second)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 1), order.take(7).map { it.first })
        assertTrue(runner.aligned)
        assertEquals(9.0, runner.elapsed, 1e-6)
        assertEquals(T0, runner.startGps!!, 1e-6)
        assertEquals(ublox.size.toLong(), runner.positions[0])
        assertEquals(phone.size.toLong(), runner.positions[1])
    }

    @Test
    fun alignsLogWithoutAbsoluteTime() {
        // 内蔵側に本当の時刻が無い（UTC 無効の NAV-PVT だけ）→ u-blox の最初の時刻にいちばん近い週に置く
        val ublox = cat(*(0 until 6).map { pvt(T0 + it) }.toTypedArray())
        val phone = cat(*(0 until 3).map { pvt(T0 + 2.5 + it, utcValid = false) }.toTypedArray())
        val (order, runner) = runMerge(ublox, phone)
        assertEquals(listOf(0, 0, 0, 1, 0, 1, 0, 1, 0), order.map { it.first })
        assertTrue(!runner.aligned)
    }

    @Test
    fun singleFileAndSeek() {
        val ublox = cat(*(0 until 4).map { pvt(T0 + it) }.toTypedArray())
        val (order, _) = runMerge(ublox, null)
        assertEquals(4, order.size)
        // 1 倍速でも、startAt までは待たない（後ろへのシークは作り直して startAt から）
        val t = System.nanoTime()
        val (order2, runner2) = runMerge(ublox, null, startAt = 100.0, speed = 1.0)
        assertEquals(4, order2.size)
        assertEquals(3.0, runner2.elapsed, 1e-6)
        assertTrue((System.nanoTime() - t) / 1e9 < 1.5)
    }

    @Test
    fun pauseResumeAndSeekForward() {
        val ublox = cat(*(0 until 30).map { pvt(T0 + it) }.toTypedArray())
        val clock = ReplayClock()
        val runner = MergedReplayRunner(listOf(ReplayInput(Pipeline(clock) {}, ublox.inputStream(), ublox.size.toLong())), 1.0, clock)
        val th = Thread { runner.run() }.apply { start() }
        Thread.sleep(300)
        runner.pause()
        val at = runner.elapsed
        Thread.sleep(1500)
        assertEquals(at, runner.elapsed, 1e-6) // 止まっている
        runner.seekForward(20.0)
        runner.resume()
        Thread.sleep(500)
        assertTrue("elapsed=${runner.elapsed}", runner.elapsed >= 20.0)
        runner.speed = 0.0 // 最大
        th.join(3000)
        assertTrue(!th.isAlive)
        assertEquals(29.0, runner.elapsed, 1e-6)
    }

    @Test
    fun timeRangeAndPairing() {
        val bytes = cat(*(0 until 20).map { pvt(T0 + it) }.toTypedArray())
        val range = UbxTimeRange.of(bytes.copyOf(bytes.size / 2), bytes.copyOfRange(bytes.size / 2, bytes.size))!!
        assertEquals(T0, range.start, 1e-6)
        assertEquals(T0 + 19, range.end, 1e-6)
        assertNull(UbxTimeRange.of(pvt(T0, utcValid = false), pvt(T0 + 1)))
        val base = TimeRange(1000.0, 2000.0)
        val cands = listOf(
            "before" to TimeRange(0.0, 900.0),
            "short" to TimeRange(1900.0, 2500.0),
            "long" to TimeRange(1100.0, 1700.0),
            "unknown" to null,
        )
        assertEquals("long", LogPairing.best(base, cands))
        assertEquals("near", LogPairing.best(base, listOf("far" to TimeRange(1500.0, 1600.0), "near" to TimeRange(1000.0, 1100.0))))
        assertNull(LogPairing.best(base, listOf("before" to TimeRange(0.0, 900.0), "x" to null)))
    }

    // ---- 実機のログ（位置が入っているのでリポジトリには入れない。無ければ skip。座標は出さない） ----

    private fun sampleDir(): File? =
        (System.getenv("UBX_PAIR_DIR") ?: "C:/Users/rei/OneDrive/Documents/ublox_monitor/phone_20261122").let { File(it) }.takeIf { it.isDirectory }

    private fun range(f: File): TimeRange? = RandomAccessFile(f, "r").use { raf ->
        val n = minOf(raf.length(), UbxTimeRange.PROBE_BYTES.toLong()).toInt()
        val head = ByteArray(n).also { raf.seek(0); raf.readFully(it) }
        val tail = ByteArray(n).also { raf.seek(raf.length() - n); raf.readFully(it) }
        UbxTimeRange.of(head, tail)
    }

    @Test
    fun realPairedLogs() {
        val dir = sampleDir()
        assumeTrue("実機のログが無い", dir != null)
        val files = dir!!.listFiles()!!.filter { it.name.endsWith(".ubx") }
        val ublox = files.filter { it.name.startsWith("ublox_") }
        val internal = files.filter { it.name.startsWith("internal_") }
        assumeTrue(ublox.isNotEmpty() && internal.isNotEmpty())
        var paired = 0
        var both = 0
        for (u in ublox) {
            val ur = range(u) ?: continue
            val pick = LogPairing.best(ur, internal.map { it to range(it) }) ?: continue
            paired++
            val (order, runner) = runMerge(u.readBytes(), pick.readBytes())
            assertTrue(runner.aligned)
            // 内蔵は測位前（RAWX だけ）のこともあるので、両方に NAV-PVT がある組が 1 つはあることだけ見る
            if (order.any { it.first == 0 } && order.any { it.first == 1 }) both++
            for (k in 1 until order.size) assertTrue(order[k].second >= order[k - 1].second || order[k].second < 1000) // 週の境目は除く
            assertEquals(u.length(), runner.positions[0])
            assertEquals(pick.length(), runner.positions[1])
            println("pair ${u.name} + ${pick.name}: overlap %.0f s, pvt %d/%d".format(
                ur.overlap(range(pick)!!), order.count { it.first == 0 }, order.count { it.first == 1 }))
        }
        assertTrue(paired > 0)
        assertTrue(both > 0)
    }
}
