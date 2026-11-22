package jp.kjtr.ubloxmonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 内蔵 GNSS の UBX 合成（PhoneUbx / PhoneRawx）と比較（Compare）。合成したものを Ubx.parse* に通して確かめる。 */
class PhoneUbxTest {
    private val c = PhoneUbx.SPEED_OF_LIGHT

    private fun frame(packet: ByteArray): Frame = UbxStream().feed(packet).single()

    private fun approx(expected: Double, actual: Double?, tol: Double) {
        assertNotNull(actual)
        assertTrue("expected $expected but $actual", abs(expected - actual!!) <= tol)
    }

    // ---- NAV-PVT ----

    /** 2024-01-01 00:00:00.250 UTC（月曜）。GPS 週 2295、週内 86418.25 秒（うるう秒 18） */
    private val utc = 1704067200250L

    @Test
    fun navPvtRoundTrip() {
        val fix = PhoneFix(
            utcMillis = utc, latitude = 35.6812345, longitude = 139.7671234, altitude = 45.678, mslAltitude = 8.123,
            accuracy = 3.5, verticalAccuracy = 5.25, speed = 2.0, speedAccuracy = 0.4, bearing = 90.0, bearingAccuracy = 12.5,
        )
        val f = frame(PhoneUbx.navPvt(fix, numSv = 17))
        assertEquals(Ubx.NAV_PVT, f.msg)
        assertEquals(92, f.payload.size)
        val pvt = Ubx.parseNavPvt(f.payload)!!
        assertEquals(86418250L, pvt.itow)
        assertEquals("2024/01/01 00:00:00", pvt.utc)
        assertTrue(pvt.timeValid)
        assertEquals(4, pvt.fixRank)   // 3D
        assertEquals("3D", pvt.fixLabel)
        assertEquals(17, pvt.numSv)
        approx(35.6812345, pvt.latitude, 1e-7)
        approx(139.7671234, pvt.longitude, 1e-7)
        approx(45.678, pvt.height, 1e-3)
        approx(8.123, pvt.heightMsl, 1e-3)
        approx(3.5, pvt.hacc, 1e-3)
        approx(5.25, pvt.vacc, 1e-3)
        approx(2.0, pvt.speed, 1e-3)
        approx(90.0, pvt.heading, 1e-5)
        assertEquals(0.0, pvt.pdop, 0.0)
        assertFalse(pvt.diffSoln)
        val r = LeReader(f.payload)
        assertEquals(0x07, r.u1(11))            // validDate | validTime | fullyResolved
        assertEquals(250_000_000, r.i4(16))     // nano
        assertEquals(0, r.i4(48))               // velN（東向き）
        assertEquals(2000, r.i4(52))            // velE
        assertEquals(400L, r.u4(68))            // sAcc
        assertEquals(1_250_000L, r.u4(72))      // headAcc
        assertEquals("—", Display.statusText(pvt).getValue("pdop"))
    }

    @Test
    fun navPvtWithoutAltitudeIs2dAndMslFallsBackToHeight() {
        val pvt = Ubx.parseNavPvt(frame(PhoneUbx.navPvt(PhoneFix(utc, 35.0, 139.0), 5)).payload)!!
        assertEquals(3, pvt.fixRank)   // 2D
        val withAlt = Ubx.parseNavPvt(frame(PhoneUbx.navPvt(PhoneFix(utc, 35.0, 139.0, altitude = 40.0), 5)).payload)!!
        approx(40.0, withAlt.heightMsl, 1e-3)
    }

    @Test
    fun itowUsesLeapSeconds() {
        assertEquals(86418250L, PhoneUbx.itow(utc))
        assertEquals(86417250L, PhoneUbx.itow(utc, leapSeconds = 17))
        // 週の終わり（土曜 23:59:50 UTC）＋ 18 秒は次の週の 8 秒
        val sat = 1704585590000L   // 2024-01-06 23:59:50 UTC
        assertEquals(8000L, PhoneUbx.itow(sat))
    }

    // ---- NAV-SAT ----

    @Test
    fun navSatMapsIdsMergesFrequenciesAndDropsUnknown() {
        val sats = listOf(
            PhoneSat(PhoneUbx.CONSTELLATION_GPS, 5, 40.4, 45.0, 120.0, true, hasEphemeris = true, carrierFrequencyHz = PhoneUbx.F_L1),
            PhoneSat(PhoneUbx.CONSTELLATION_GPS, 5, 45.0, 45.0, 120.0, false, carrierFrequencyHz = PhoneUbx.F_L5),
            PhoneSat(PhoneUbx.CONSTELLATION_QZSS, 194, 38.0, 70.0, 180.0, false, hasAlmanac = true),
            PhoneSat(PhoneUbx.CONSTELLATION_GLONASS, 7, 30.0, 20.0, 300.0, true),
            PhoneSat(PhoneUbx.CONSTELLATION_GLONASS, 100, 30.0, 20.0, 300.0, false),   // FCN 表現は捨てる
            PhoneSat(PhoneUbx.CONSTELLATION_SBAS, 137, 35.0, 40.0, 200.0, false),
            PhoneSat(0, 1, 30.0, 10.0, 10.0, false),                                     // 不明な衛星系
            PhoneSat(PhoneUbx.CONSTELLATION_GALILEO, 11, 0.0, -5.0, 359.6, false),
        )
        assertEquals(2, PhoneUbx.usedCount(sats))
        val f = frame(PhoneUbx.navSat(86418000L, sats))
        assertEquals(Ubx.NAV_SAT, f.msg)
        val r = LeReader(f.payload)
        assertEquals(86418000L, r.u4(0))
        assertEquals(1, r.u1(4))
        val list = Ubx.parseNavSat(f.payload)
        val names = list.map { Ubx.satName(it.gnss, it.sv) }
        assertEquals(listOf("G05", "S37", "E11", "J02", "R07"), names)
        val g05 = list[0]
        assertEquals(40, g05.cno)          // L1 側の C/N0
        assertTrue(g05.used)
        assertEquals(7, g05.quality)
        assertEquals(45, g05.elev)
        assertEquals(120, g05.azim)
        val j02 = list[3]
        assertFalse(j02.used)
        assertEquals(4, j02.quality)
        val e11 = list[2]
        assertEquals(0, e11.quality)
        assertEquals(-5, e11.elev)
        assertEquals(0, e11.azim)          // 359.6 → 360 → 0
        // flags: ephAvail（bit 11）・orbitSource = 1
        val flags = r.u4(8 + 8)
        assertEquals(1L, (flags shr 11) and 1)
        assertEquals(1L, (flags shr 8) and 7)
        val qzFlags = r.u4(8 + 3 * 12 + 8)
        assertEquals(1L, (qzFlags shr 12) and 1)
        assertEquals(2L, (qzFlags shr 8) and 7)
    }

    @Test
    fun svAndSigIdMapping() {
        assertEquals(1, PhoneUbx.svId(5, 193))
        assertEquals(10, PhoneUbx.svId(5, 202))
        assertNull(PhoneUbx.svId(6, 93))
        assertEquals(24, PhoneUbx.svId(6, 24))
        assertEquals(0, PhoneUbx.sigId(0, PhoneUbx.F_L1, "C"))
        assertEquals(0, PhoneUbx.sigId(0, null, null))
        assertEquals(6, PhoneUbx.sigId(0, PhoneUbx.F_L5, "I"))
        assertEquals(7, PhoneUbx.sigId(0, PhoneUbx.F_L5, "Q"))
        assertEquals(7, PhoneUbx.sigId(0, PhoneUbx.F_L5, null))
        assertNull(PhoneUbx.sigId(0, 1227.6e6, "L"))   // GPS L2 は対応しない
        assertEquals(0, PhoneUbx.sigId(2, PhoneUbx.F_L1, "C"))
        assertEquals(1, PhoneUbx.sigId(2, PhoneUbx.F_L1, "B"))
        assertEquals(0, PhoneUbx.sigId(2, PhoneUbx.F_L1, null))
        assertEquals(3, PhoneUbx.sigId(2, PhoneUbx.F_L5, "I"))
        assertEquals(4, PhoneUbx.sigId(2, PhoneUbx.F_L5, null))
        assertEquals(6, PhoneUbx.sigId(2, PhoneUbx.F_E5B, "Q"))
        assertEquals(0, PhoneUbx.sigId(3, PhoneUbx.F_B1I, "I"))
        assertEquals(0, PhoneUbx.sigId(3, null, null))
        assertEquals(5, PhoneUbx.sigId(3, PhoneUbx.F_L1, "P"))
        assertEquals(6, PhoneUbx.sigId(3, PhoneUbx.F_L1, "D"))
        assertEquals(7, PhoneUbx.sigId(3, PhoneUbx.F_L5, "P"))
        assertEquals(8, PhoneUbx.sigId(3, PhoneUbx.F_L5, "D"))
        assertEquals(7, PhoneUbx.sigId(3, PhoneUbx.F_L5, null))
        assertEquals(0, PhoneUbx.sigId(5, PhoneUbx.F_L1, "C"))
        assertEquals(8, PhoneUbx.sigId(5, PhoneUbx.F_L5, "I"))
        assertEquals(9, PhoneUbx.sigId(5, PhoneUbx.F_L5, "Q"))
        assertEquals(0, PhoneUbx.sigId(6, 1602e6 + 3 * 0.5625e6, null))
        assertEquals(0, PhoneUbx.sigId(7, PhoneUbx.F_L5, "A"))
        assertEquals(0, PhoneUbx.sigId(1, PhoneUbx.F_L1, "C"))
        // 全部 Ubx.SIGNALS にある組み合わせ
        for ((g, s) in listOf(0 to 0, 0 to 6, 0 to 7, 2 to 0, 2 to 1, 2 to 3, 2 to 4, 2 to 6, 3 to 0, 3 to 5, 3 to 6, 3 to 7, 3 to 8, 5 to 0, 5 to 8, 5 to 9, 6 to 0, 7 to 0, 1 to 0)) {
            assertTrue("$g/$s", (g to s) in Ubx.SIGNALS)
        }
        assertEquals(10, PhoneUbx.glonassFreqId(1602e6 + 3 * 0.5625e6))
        assertEquals(0, PhoneUbx.glonassFreqId(1602e6 - 7 * 0.5625e6))
        assertEquals(0, PhoneUbx.glonassFreqId(null))
    }

    // ---- RXM-RAWX ----

    private val week = 2295L
    private val towNs = 86418_500_000_000L
    private val tRx = week * PhoneUbx.WEEK_NS + towNs
    private val timeNanos = 1_000_000_000_000L
    private fun clock(bias: Double = 0.25, leap: Int? = 18, disc: Int = 0, tRxGps: Long = tRx) =
        PhoneClock(timeNanos, timeNanos - tRxGps, bias, leap, disc)

    private val towOk = PhoneUbx.STATE_TOW_DECODED or 0x1

    private fun meas(
        constellation: Int, svid: Int, tTx: Long, state: Int = towOk, freq: Double? = null, code: String? = null,
        adrState: Int = 0, adr: Double = 0.0, rate: Double = 0.0,
    ) = PhoneMeas(
        constellation, svid, state, tTx, receivedSvTimeUncertaintyNanos = 10, cn0 = 41.6,
        pseudorangeRate = rate, pseudorangeRateUncertainty = 0.05, adrState = adrState, adr = adr,
        adrUncertainty = 0.008, carrierFrequencyHz = freq, codeType = code,
    )

    private class Raw(payload: ByteArray) {
        val r = LeReader(payload)
        val rcvTow = r.f8(0)
        val week = r.u2(8)
        val leapS = r.i1(10)
        val numMeas = r.u1(11)
        val recStat = r.u1(12)
        val version = r.u1(13)
        fun pr(i: Int) = r.f8(16 + i * 32)
        fun cp(i: Int) = r.f8(16 + i * 32 + 8)
        fun dop(i: Int) = r.f4(16 + i * 32 + 16).toDouble()
        fun freqId(i: Int) = r.u1(16 + i * 32 + 23)
        fun lock(i: Int) = r.u2(16 + i * 32 + 24)
        fun prStd(i: Int) = r.u1(16 + i * 32 + 27)
        fun trk(i: Int) = r.u1(16 + i * 32 + 30)
    }

    @Test
    fun rawxPseudorangesPerSystemRoundTrip() {
        val travel = 73_000_000L
        val gloRx = Math.floorMod(tRx - 18_000_000_000L + PhoneUbx.GLO_OFFSET_NS, PhoneUbx.DAY_NS)
        val bdsRx = Math.floorMod(tRx - PhoneUbx.BDS_OFFSET_NS, PhoneUbx.WEEK_NS)
        val list = listOf(
            meas(PhoneUbx.CONSTELLATION_GPS, 5, towNs - travel, freq = PhoneUbx.F_L1, code = "C",
                adrState = PhoneUbx.ADR_STATE_VALID or PhoneUbx.ADR_STATE_HALF_CYCLE_RESOLVED, adr = 1234.5, rate = -500.0),
            meas(PhoneUbx.CONSTELLATION_GPS, 5, towNs - travel, freq = PhoneUbx.F_L5, code = "Q"),
            meas(PhoneUbx.CONSTELLATION_BEIDOU, 20, bdsRx - 80_000_000L, freq = PhoneUbx.F_B1I, code = "I"),
            meas(PhoneUbx.CONSTELLATION_GLONASS, 7, gloRx - 70_000_000L, state = PhoneUbx.STATE_GLO_TOD_DECODED,
                freq = 1602e6 + 3 * 0.5625e6),
            meas(PhoneUbx.CONSTELLATION_GALILEO, 11, towNs - 90_000_000L, freq = PhoneUbx.F_L1, code = "C"),
            meas(PhoneUbx.CONSTELLATION_QZSS, 194, towNs - 120_000_000L, state = 0x1),   // TOW 不明
            meas(PhoneUbx.CONSTELLATION_GPS, 9, towNs - travel, freq = 1227.6e6, code = "L"),  // 対応しない信号は捨てる
            meas(PhoneUbx.CONSTELLATION_GLONASS, 100, gloRx - 70_000_000L, state = PhoneUbx.STATE_GLO_TOD_DECODED),
        )
        val f = frame(PhoneRawx().encode(clock(), list)!!)
        assertEquals(Ubx.RXM_RAWX, f.msg)
        val raw = Raw(f.payload)
        approx((towNs - 0.25) * 1e-9, raw.rcvTow, 1e-9)
        assertEquals(2295, raw.week)
        assertEquals(18, raw.leapS)
        assertEquals(6, raw.numMeas)
        assertEquals(0x01, raw.recStat)
        assertEquals(1, raw.version)

        val (tow, parsed) = Ubx.parseRxmRawx(f.payload)
        approx(86418.5, tow, 1e-6)
        assertEquals(
            listOf("G05/0", "G05/7", "C20/0", "R07/0", "E11/0", "J02/0"),
            parsed.map { "${Ubx.satName(it.gnss, it.sv)}/${it.sig}" },
        )
        assertEquals(listOf(true, true, true, true, true, false), parsed.map { it.prValid })
        assertEquals(listOf(true, false, false, false, false, false), parsed.map { it.cpValid })
        assertEquals(42, parsed[0].cno)

        approx((travel - 0.25) * c * 1e-9, raw.pr(0), 1e-4)
        approx((80_000_000L - 0.25) * c * 1e-9, raw.pr(2), 1e-4)
        approx((70_000_000L - 0.25) * c * 1e-9, raw.pr(3), 1e-4)
        approx((90_000_000L - 0.25) * c * 1e-9, raw.pr(4), 1e-4)
        val lambda = c / PhoneUbx.F_L1
        approx(1234.5 / lambda, raw.cp(0), 1e-6)
        approx(500.0 / lambda, raw.dop(0), 1e-3)
        assertEquals(0x01 or 0x02 or 0x04, raw.trk(0))
        assertEquals(10, raw.freqId(3))           // FCN 3 → 10
        assertEquals(0, raw.lock(0))              // 最初の epoch
        // 10 ns → 約 3 m → 0.01 × 2^9 = 5.12 m
        assertEquals(9, raw.prStd(0))
    }

    @Test
    fun rawxWeekRolloverAndRangeCheck() {
        val tRx2 = (week + 1) * PhoneUbx.WEEK_NS + 10_000_000L   // 週が変わった直後
        val list = listOf(
            meas(PhoneUbx.CONSTELLATION_GPS, 3, PhoneUbx.WEEK_NS - 60_000_000L),   // 衛星側はまだ前の週
            meas(PhoneUbx.CONSTELLATION_GPS, 4, 9_000_000L),                        // 1 ms → 300 km は範囲外
        )
        val raw = Raw(frame(PhoneRawx().encode(clock(bias = 0.0, tRxGps = tRx2), list)!!).payload)
        assertEquals(2296, raw.week)
        approx(70_000_000L * c * 1e-9, raw.pr(0), 1e-4)
        assertEquals(1, raw.trk(0) and 1)
        assertEquals(0, raw.trk(1) and 1)
    }

    @Test
    fun galileoSecondaryCodeOnlyUses100ms() {
        val state = 0x1 or PhoneUbx.STATE_GAL_E1C_2ND_CODE_LOCK
        val rx100 = Math.floorMod(tRx, 100_000_000L)
        val tTx = Math.floorMod(rx100 - 85_000_000L, 100_000_000L)
        val raw = Raw(frame(PhoneRawx().encode(clock(bias = 0.0), listOf(meas(PhoneUbx.CONSTELLATION_GALILEO, 2, tTx, state = state)))!!).payload)
        approx(85_000_000L * c * 1e-9, raw.pr(0), 1e-4)
        assertEquals(1, raw.trk(0) and 1)
    }

    @Test
    fun rawxSkipsWithoutFullBiasAndFlagsClockReset() {
        val enc = PhoneRawx()
        val m = listOf(meas(PhoneUbx.CONSTELLATION_GPS, 5, towNs - 73_000_000L,
            adrState = PhoneUbx.ADR_STATE_VALID, adr = 10.0))
        assertNull(enc.encode(PhoneClock(timeNanos, null), m))
        assertNotNull(enc.skipReason)
        val first = Raw(frame(enc.encode(clock(disc = 1), m)!!).payload)
        assertEquals(0, first.recStat and 0x02)
        assertEquals(0, first.lock(0))
        // 1 秒後: locktime が 1000 ms
        val second = Raw(frame(enc.encode(clock(disc = 1, tRxGps = tRx + 1_000_000_000L), m)!!).payload)
        assertEquals(1000, second.lock(0))
        // 時計が不連続 → その epoch は出さず、次で clkReset、locktime は数え直し
        assertNull(enc.encode(clock(disc = 2, tRxGps = tRx + 2_000_000_000L), m))
        val third = Raw(frame(enc.encode(clock(disc = 2, tRxGps = tRx + 3_000_000_000L), m)!!).payload)
        assertEquals(0x02, third.recStat and 0x02)
        assertEquals(0, third.lock(0))
        val fourth = Raw(frame(enc.encode(clock(disc = 2, tRxGps = tRx + 4_000_000_000L), m)!!).payload)
        assertEquals(0, fourth.recStat and 0x02)
        assertEquals(1000, fourth.lock(0))
        // サイクルスリップで 0 に戻る
        val slip = listOf(m[0].copy(adrState = PhoneUbx.ADR_STATE_VALID or PhoneUbx.ADR_STATE_CYCLE_SLIP))
        assertEquals(0, Raw(frame(enc.encode(clock(disc = 2, tRxGps = tRx + 5_000_000_000L), slip)!!).payload).lock(0))
        // 長く続くと 65535 で止まる
        val long = Raw(frame(enc.encode(clock(disc = 2, tRxGps = tRx + 500_000_000_000L), m)!!).payload)
        assertEquals(65535, long.lock(0))
    }

    @Test
    fun stdevCodes() {
        assertEquals(0, PhoneUbx.prStdevCode(0.005))
        assertEquals(0, PhoneUbx.prStdevCode(0.01))
        assertEquals(1, PhoneUbx.prStdevCode(0.02))
        assertEquals(15, PhoneUbx.prStdevCode(1e6))
        assertEquals(2, PhoneUbx.cpStdevCode(0.008))
        assertEquals(15, PhoneUbx.cpStdevCode(1.0))
        assertEquals(0, PhoneUbx.doStdevCode(0.001))
        assertEquals(3, PhoneUbx.doStdevCode(0.016))
    }

    // ---- Pipeline に流す（再生・記録と同じ経路） ----

    @Test
    fun synthesizedFramesFeedPipeline() {
        val clock = ReplayClock()
        val pipeline = Pipeline(clock)
        pipeline.onEpoch = { step -> clock.value += step }
        val sats = listOf(
            PhoneSat(PhoneUbx.CONSTELLATION_GPS, 5, 40.0, 45.0, 120.0, true, carrierFrequencyHz = PhoneUbx.F_L1),
            PhoneSat(PhoneUbx.CONSTELLATION_GPS, 5, 44.0, 45.0, 120.0, true, carrierFrequencyHz = PhoneUbx.F_L5),
        )
        val m = listOf(
            meas(PhoneUbx.CONSTELLATION_GPS, 5, towNs - 73_000_000L, freq = PhoneUbx.F_L1, code = "C"),
            meas(PhoneUbx.CONSTELLATION_GPS, 5, towNs - 73_000_000L, freq = PhoneUbx.F_L5, code = "Q"),
        )
        val enc = PhoneRawx()
        for (k in 0 until 3) {
            pipeline.feed(PhoneUbx.navPvt(PhoneFix(utc + k * 1000, 35.0, 139.0, 40.0, accuracy = 4.0), 1))
            pipeline.feed(PhoneUbx.navSat(PhoneUbx.itow(utc + k * 1000), sats))
            pipeline.feed(enc.encode(clock(tRxGps = tRx + k * 1_000_000_000L), m)!!)
        }
        assertEquals(0, pipeline.stream.checksumErrors)
        val snap = pipeline.state.snapshot()
        assertEquals(3, snap.counts[Ubx.NAV_PVT])
        assertEquals(3, snap.counts[Ubx.RXM_RAWX])
        assertEquals(2.0, clock.value, 1e-9)
        val g05 = snap.satellites.getValue("G05")
        assertTrue(g05.used)
        assertEquals(mapOf("L1" to 42, "L5" to 42), g05.bands)
        assertEquals(setOf("GPS L1CA", "GPS L5Q"), snap.signals.keys)
    }

    // ---- 比較 ----

    private fun pvt(lat: Double, lon: Double, h: Double) = Ubx.parseNavPvt(
        frame(PhoneUbx.navPvt(PhoneFix(utc, lat, lon, h), 10)).payload
    )!!

    @Test
    fun positionDiffNorthEastAndHeight() {
        val base = pvt(35.0, 139.0, 50.0)
        val north = Compare.diff(base, pvt(35.0001, 139.0, 52.5))!!
        approx(11.132, north.horizontal, 0.01)
        approx(0.0, north.bearing, 0.01)
        approx(2.5, north.up, 1e-3)
        val east = Compare.diff(base, pvt(35.0, 139.0001, 50.0))!!
        approx(90.0, east.bearing, 0.01)
        approx(11.132 * kotlin.math.cos(Math.toRadians(35.0)), east.horizontal, 0.01)
        val sw = Compare.diff(base, pvt(34.9999, 138.9999, 50.0))!!
        assertTrue(sw.bearing in 180.0..270.0)
        assertEquals("南西", Compare.bearingName(sw.bearing))
        assertEquals("北", Compare.bearingName(350.0))
    }

    @Test
    fun commonCn0UsesL1Band() {
        fun snap(vararg sats: Pair<String, SatView>) = Snapshot(0.0, null, emptyList(), linkedMapOf(*sats), emptyMap(), emptyMap())
        val a = snap(
            "G05" to SatView("GPS", 45, 40, 100, true, mapOf("L1" to 42, "L5" to 45)),
            "G07" to SatView("GPS", 30, 40, 100, true, emptyMap()),
            "E11" to SatView("GALILEO", 33, 40, 100, false, mapOf("L5" to 33)),   // L1 帯が無い
        )
        val b = snap(
            "G05" to SatView("GPS", 38, 40, 100, true, emptyMap()),
            "J02" to SatView("QZSS", 35, 40, 100, true, emptyMap()),
        )
        val cmp = Compare.commonCn0(a, b)
        assertEquals(listOf(Triple("G05", 42, 38)), cmp.rows)
        assertEquals(1, cmp.onlyBase)
        assertEquals(1, cmp.onlyOther)
    }

    // ---- 表示（エミュレータで見つかった修正） ----

    @Test
    fun messageTextShowsKnownFirst() {
        val counts = linkedMapOf(
            MsgId(0x27, 0x00) to 500, MsgId(0x0A, 0x11) to 400, Ubx.RXM_RAWX to 30, Ubx.NAV_PVT to 30,
            Ubx.NAV_SAT to 30, MsgId(0x01, 0x03) to 300, Ubx.MON_VER to 1, MsgId(0x0A, 0x09) to 200,
        )
        val lines = Display.messageText(counts).split("\n")
        assertEquals(listOf("NAV-PVT: 30", "NAV-SAT: 30", "RXM-RAWX: 30", "MON-VER: 1", "27-00: 500", "0A-11: 400"), lines)
    }

    @Test
    fun fitYPadsConstantValues() {
        val (lo, hi, _) = ChartMath.fitY(listOf(15.0, 15.0), 0.0, integer = true)
        assertTrue("$lo..$hi", lo <= 13.0 && hi >= 17.0)
        val (lo2, hi2, _) = ChartMath.fitY(listOf(1.0), 0.0, integer = true)
        assertEquals(0.0, lo2, 1e-9)
        assertTrue(hi2 >= 3.0)
        val (lo3, hi3, _) = ChartMath.fitY(listOf(0.0, 0.0), null, integer = false)
        assertTrue(lo3 < 0.0 && hi3 > 0.0)
    }
}
