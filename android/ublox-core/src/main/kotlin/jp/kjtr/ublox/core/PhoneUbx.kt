package jp.kjtr.ublox.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * スマホ内蔵 GNSS の値から UBX（NAV-PVT・NAV-SAT・RXM-RAWX）を合成する。Android には依存しない。
 * data/source/InternalGnssSource が Android の Location / GnssStatus / GnssMeasurementsEvent を
 * 下のデータクラスに詰め替えて渡す。フィールドの意味は u-blox Interface Description（F9 HPG / X20）に合わせる。
 */

/** Location 1 個分。Android の Location.altitude は WGS84 楕円体高。 */
data class PhoneFix(
    /** Location.time（UTC のミリ秒） */
    val utcMillis: Long,
    val latitude: Double,
    val longitude: Double,
    /** 楕円体高 [m]（無ければ null → 2D） */
    val altitude: Double? = null,
    /** 標高 [m]（API 34+ の mslAltitudeMeters） */
    val mslAltitude: Double? = null,
    /** 水平の推定精度 [m]（Android は 68% の半径） */
    val accuracy: Double? = null,
    val verticalAccuracy: Double? = null,
    /** 対地速度 [m/s] */
    val speed: Double? = null,
    val speedAccuracy: Double? = null,
    /** 進行方位 [deg] */
    val bearing: Double? = null,
    val bearingAccuracy: Double? = null,
)

/** GnssStatus の衛星 1 エントリ（周波数ごとに別エントリで来ることがある）。constellation は Android の値。 */
data class PhoneSat(
    val constellation: Int,
    val svid: Int,
    val cn0: Double,
    val elevation: Double,
    val azimuth: Double,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean = false,
    val hasAlmanac: Boolean = false,
    val carrierFrequencyHz: Double? = null,
)

/** GnssClock。fullBiasNanos が無いときは null（その epoch の RAWX は出さない）。 */
data class PhoneClock(
    val timeNanos: Long,
    val fullBiasNanos: Long?,
    val biasNanos: Double = 0.0,
    val leapSecond: Int? = null,
    val discontinuityCount: Int = 0,
)

/** GnssMeasurement 1 個。state・adrState は Android のビットのまま。 */
data class PhoneMeas(
    val constellation: Int,
    val svid: Int,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0: Double,
    val pseudorangeRate: Double,
    val pseudorangeRateUncertainty: Double,
    val adrState: Int = 0,
    val adr: Double = 0.0,
    val adrUncertainty: Double = 0.0,
    val carrierFrequencyHz: Double? = null,
    /** API 29+ の codeType（"C"、"I"、"Q"、"X" など） */
    val codeType: String? = null,
    val timeOffsetNanos: Double = 0.0,
)

/** NAV-SAT 用にまとめた衛星 1 機（u-blox の gnssId / svId）。 */
data class MergedSat(
    val gnssId: Int, val svId: Int, val cno: Int, val elev: Int, val azim: Int,
    val used: Boolean, val hasEphemeris: Boolean, val hasAlmanac: Boolean,
)

object PhoneUbx {
    // ---- Android の定数（android.location.GnssStatus / GnssMeasurement と同じ値） ----
    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7

    const val STATE_TOW_DECODED = 0x8
    const val STATE_GLO_TOD_DECODED = 0x80
    const val STATE_GAL_E1C_2ND_CODE_LOCK = 0x800
    const val STATE_TOW_KNOWN = 0x4000
    const val STATE_GLO_TOD_KNOWN = 0x8000

    const val ADR_STATE_VALID = 0x1
    const val ADR_STATE_RESET = 0x2
    const val ADR_STATE_CYCLE_SLIP = 0x4
    const val ADR_STATE_HALF_CYCLE_RESOLVED = 0x8
    const val ADR_STATE_HALF_CYCLE_REPORTED = 0x10

    // ---- 時刻 ----
    const val SPEED_OF_LIGHT = 299792458.0
    /** GPS 時刻の起点（1980-01-06 00:00:00 UTC）の Unix 時刻 [ms] */
    const val GPS_EPOCH_UNIX_MS = 315964800000L
    const val WEEK_MS = 604800000L
    const val WEEK_NS = 604800L * 1_000_000_000L
    const val DAY_NS = 86400L * 1_000_000_000L
    /** GPS − UTC のうるう秒（GnssClock.leapSecond が無いときの既定。2017 年から 18） */
    const val DEFAULT_LEAP_SECONDS = 18
    /** BDT = GPS − 14 秒 */
    const val BDS_OFFSET_NS = 14L * 1_000_000_000L
    /** GLONASS 時刻 = UTC + 3 時間 */
    const val GLO_OFFSET_NS = 3L * 3600L * 1_000_000_000L
    /** Galileo の E1C 2 次コードだけで分かる時刻の範囲 */
    const val GAL_100MS_NS = 100_000_000L

    /** 疑似距離として妥当な範囲 [m] */
    const val PR_MIN = 1e7
    const val PR_MAX = 1e8

    // ---- 周波数 [Hz] ----
    const val F_L1 = 1575.42e6
    const val F_L5 = 1176.45e6
    const val F_E5B = 1207.14e6
    const val F_B1I = 1561.098e6
    const val F_G1 = 1602.0e6
    const val F_G1_STEP = 0.5625e6

    /** UTC ミリ秒を GPS 時刻（起点からのミリ秒）にする。 */
    fun gpsMillis(utcMillis: Long, leapSeconds: Int = DEFAULT_LEAP_SECONDS): Long =
        utcMillis - GPS_EPOCH_UNIX_MS + leapSeconds * 1000L

    /** UTC ミリ秒を GPS 週内秒 [ms]（NAV-PVT / NAV-SAT の iTOW）にする。 */
    fun itow(utcMillis: Long, leapSeconds: Int = DEFAULT_LEAP_SECONDS): Long =
        Math.floorMod(gpsMillis(utcMillis, leapSeconds), WEEK_MS)

    /** Android の衛星系を u-blox の gnssId にする。対応しないものは null。 */
    fun gnssId(constellation: Int): Int? = when (constellation) {
        CONSTELLATION_GPS -> 0
        CONSTELLATION_SBAS -> 1
        CONSTELLATION_GALILEO -> 2
        CONSTELLATION_BEIDOU -> 3
        CONSTELLATION_QZSS -> 5
        CONSTELLATION_GLONASS -> 6
        CONSTELLATION_IRNSS -> 7
        else -> null
    }

    /**
     * Android の svid を u-blox の svId にする。表示できないものは null。
     * QZSS は 193-202 → 1-10。GLONASS は slot（1-24）だけ使い、FCN 表現（93-106）は捨てる（u-blox の 255 に相当）。
     */
    fun svId(gnssId: Int, svid: Int): Int? = when (gnssId) {
        0 -> svid.takeIf { it in 1..32 }
        1 -> svid.takeIf { it in 120..158 }
        2 -> svid.takeIf { it in 1..36 }
        3 -> svid.takeIf { it in 1..63 }
        5 -> (svid - 192).takeIf { it in 1..10 } ?: svid.takeIf { it in 1..10 }
        6 -> svid.takeIf { it in 1..24 }
        7 -> svid.takeIf { it in 1..14 }
        else -> null
    }

    private fun near(f: Double, center: Double, tol: Double = 2e6) = abs(f - center) <= tol

    /** L1 帯（1559〜1610 MHz）か。周波数が分からなければ L1 とみなす。 */
    fun isL1Band(f: Double?): Boolean = f == null || f in 1559e6..1610e6

    /** 周波数が分からないときに使う、その衛星系の L1 帯の周波数（GLONASS は FCN 0、IRNSS は L5）。 */
    fun defaultFrequency(gnssId: Int): Double = when (gnssId) {
        3 -> F_B1I
        6 -> F_G1
        7 -> F_L5
        else -> F_L1
    }

    /**
     * 周波数と codeType から u-blox の sigId を決める（Ubx.SIGNALS の表と合わせる）。対応しない組み合わせは null。
     * codeType が分からないときは u-blox が普段出す方（L5 は Q、E5a は Q など）にする。
     */
    fun sigId(gnssId: Int, frequencyHz: Double?, codeType: String?): Int? {
        val f = frequencyHz ?: defaultFrequency(gnssId)
        val code = codeType?.uppercase()
        return when (gnssId) {
            0 -> when {
                near(f, F_L1) -> if (code == null || code == "C") 0 else null
                near(f, F_L5) -> if (code == "I") 6 else 7
                else -> null
            }
            1 -> if (near(f, F_L1)) 0 else null
            2 -> when {
                near(f, F_L1) -> if (code == "B") 1 else 0
                near(f, F_L5) -> if (code == "I") 3 else 4
                near(f, F_E5B) -> if (code == "I") 5 else 6
                else -> null
            }
            3 -> when {
                near(f, F_B1I) -> 0
                near(f, F_L1) -> if (code == "D") 6 else 5
                near(f, F_L5) -> if (code == "D") 8 else 7
                else -> null
            }
            5 -> when {
                near(f, F_L1) -> if (code == null || code == "C") 0 else null
                near(f, F_L5) -> if (code == "I") 8 else 9
                else -> null
            }
            6 -> if (f in 1597e6..1607e6) 0 else null
            7 -> if (near(f, F_L5)) 0 else null
            else -> null
        }
    }

    /** GLONASS の周波数から freqId（FCN + 7、0〜13）を求める。分からなければ 0。 */
    fun glonassFreqId(frequencyHz: Double?): Int {
        if (frequencyHz == null) return 0
        val k = ((frequencyHz - F_G1) / F_G1_STEP).roundToInt()
        return if (k in -7..6) k + 7 else 0
    }

    private fun clampU4(v: Double): Long = v.roundToLong().coerceIn(0L, 0xFFFFFFFFL)

    // ---- NAV-PVT ----

    /**
     * Location から NAV-PVT（92 バイト）を作る。numSv は GnssStatus で測位に使っている衛星の数。
     * 分からない値（tAcc・pDOP・velD・headVeh など）は 0。
     */
    fun navPvt(fix: PhoneFix, numSv: Int, leapSeconds: Int = DEFAULT_LEAP_SECONDS): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = fix.utcMillis }
        val ms = Math.floorMod(fix.utcMillis, 1000L)
        val alt = fix.altitude
        val speed = fix.speed
        val bearing = fix.bearing
        val (velN, velE) = if (speed != null && bearing != null) {
            val rad = Math.toRadians(bearing)
            speed * cos(rad) to speed * sin(rad)
        } else 0.0 to 0.0
        val w = LeWriter()
            .u4(itow(fix.utcMillis, leapSeconds))
            .u2(cal.get(Calendar.YEAR))
            .u1(cal.get(Calendar.MONTH) + 1)
            .u1(cal.get(Calendar.DAY_OF_MONTH))
            .u1(cal.get(Calendar.HOUR_OF_DAY))
            .u1(cal.get(Calendar.MINUTE))
            .u1(cal.get(Calendar.SECOND))
            .u1(0x07)                                   // validDate | validTime | fullyResolved
            .u4(0)                                      // tAcc（不明）
            .i4((ms * 1_000_000L).toInt())              // nano
            .u1(if (alt != null) 3 else 2)              // fixType
            .u1(0x01)                                   // flags: gnssFixOK（diffSoln・carrSoln は 0）
            .u1(0)                                      // flags2
            .u1(numSv.coerceIn(0, 255))
            .i4((fix.longitude * 1e7).roundToInt())
            .i4((fix.latitude * 1e7).roundToInt())
            .i4(((alt ?: 0.0) * 1000).roundToInt())    // height（楕円体高）
            .i4(((fix.mslAltitude ?: alt ?: 0.0) * 1000).roundToInt())  // hMSL（無ければ楕円体高と同じ）
            .u4(clampU4((fix.accuracy ?: 0.0) * 1000))
            .u4(clampU4((fix.verticalAccuracy ?: 0.0) * 1000))
            .i4((velN * 1000).roundToInt())
            .i4((velE * 1000).roundToInt())
            .i4(0)                                      // velD（不明）
            .i4(((speed ?: 0.0) * 1000).roundToInt())  // gSpeed
            .i4(((bearing ?: 0.0) * 1e5).roundToInt()) // headMot
            .u4(clampU4((fix.speedAccuracy ?: 0.0) * 1000))
            .u4(clampU4((fix.bearingAccuracy ?: 0.0) * 1e5))
            .u2(0)                                      // pDOP（不明。表示は「—」）
            .u2(0)                                      // flags3
            .zeros(4)                                   // reserved
            .i4(0)                                      // headVeh
            .u2(0)                                      // magDec
            .u2(0)                                      // magAcc
        return Ubx.build(Ubx.NAV_PVT, w.toByteArray())
    }

    // ---- NAV-SAT ----

    /**
     * GnssStatus のエントリを u-blox の衛星 1 機ずつにまとめる。周波数ごとのエントリは 1 機にし、
     * C/N0 は L1 帯の値（無ければ最大）、使用・軌道情報はどれかにあれば立てる。
     */
    fun mergeSats(sats: List<PhoneSat>): List<MergedSat> {
        class Acc(val gnssId: Int, val svId: Int) {
            var l1Cno: Int? = null
            var maxCno = 0
            var elev: Int? = null
            var azim: Int? = null
            var used = false
            var eph = false
            var alm = false
        }
        val map = LinkedHashMap<Pair<Int, Int>, Acc>()
        for (s in sats) {
            val g = gnssId(s.constellation) ?: continue
            val sv = svId(g, s.svid) ?: continue
            val a = map.getOrPut(g to sv) { Acc(g, sv) }
            val cno = s.cn0.roundToInt().coerceIn(0, 255)
            if (isL1Band(s.carrierFrequencyHz)) a.l1Cno = maxOf(a.l1Cno ?: 0, cno)
            a.maxCno = maxOf(a.maxCno, cno)
            if (a.elev == null || (a.elev == 0 && a.azim == 0)) {
                a.elev = s.elevation.roundToInt().coerceIn(-90, 90)
                a.azim = s.azimuth.roundToInt().let { Math.floorMod(it, 360) }
            }
            a.used = a.used || s.usedInFix
            a.eph = a.eph || s.hasEphemeris
            a.alm = a.alm || s.hasAlmanac
        }
        return map.values.sortedWith(compareBy({ it.gnssId }, { it.svId })).map {
            MergedSat(it.gnssId, it.svId, it.l1Cno ?: it.maxCno, it.elev ?: 0, it.azim ?: 0, it.used, it.eph, it.alm)
        }
    }

    /** 測位に使っている衛星の数（NAV-PVT の numSV）。 */
    fun usedCount(sats: List<PhoneSat>): Int = mergeSats(sats).count { it.used }

    /** GnssStatus から NAV-SAT（version 1）を作る。 */
    fun navSat(itowMs: Long, sats: List<PhoneSat>): ByteArray {
        val merged = mergeSats(sats).take(255)
        val w = LeWriter().u4(itowMs).u1(1).u1(merged.size).zeros(2)
        for (s in merged) {
            val quality = when {
                s.cno <= 0 -> 0
                s.used -> 7      // 測位に使用 = コード・搬送波とも追尾中とみなす
                else -> 4        // 追尾中（コードロック・時刻同期）
            }
            val orbitSource = when {
                s.hasEphemeris -> 1
                s.hasAlmanac -> 2
                else -> 0
            }
            var flags = quality.toLong()
            if (s.used) flags = flags or 0x08
            // health（bit 4-5）は Android から分からないので 0（不明）
            flags = flags or (orbitSource.toLong() shl 8)
            if (s.hasEphemeris) flags = flags or (1L shl 11)
            if (s.hasAlmanac) flags = flags or (1L shl 12)
            w.u1(s.gnssId).u1(s.svId).u1(s.cno).u1(s.elev).u2(s.azim).u2(0).u4(flags)
        }
        return Ubx.build(Ubx.NAV_SAT, w.toByteArray())
    }

    // ---- RXM-RAWX の部品 ----

    /** prStdev の符号化（0.01 × 2^n m の n、0〜15）。 */
    fun prStdevCode(sigmaM: Double): Int =
        if (sigmaM <= 0.01) 0 else ceil(ln(sigmaM / 0.01) / ln(2.0) - 1e-9).toInt().coerceIn(0, 15)

    /** cpStdev の符号化（0.004 cycles 単位、0〜15）。 */
    fun cpStdevCode(sigmaCycles: Double): Int = (sigmaCycles / 0.004).roundToInt().coerceIn(0, 15)

    /** doStdev の符号化（0.002 × 2^n Hz の n、0〜15）。 */
    fun doStdevCode(sigmaHz: Double): Int =
        if (sigmaHz <= 0.002) 0 else ceil(ln(sigmaHz / 0.002) / ln(2.0) - 1e-9).toInt().coerceIn(0, 15)

    /** 疑似距離の計算結果。 */
    data class Range(val meters: Double, val valid: Boolean)

    /**
     * 疑似距離 [m] を求める。tRxGps は受信時刻（GPS 起点からの整数 ns）、frac はその端数 [ns]。
     * receivedSvTimeNanos は衛星系ごとの時刻系（GPS/QZSS/GAL/IRNSS/SBAS は週内、GAL の 2 次コードだけなら 100 ms 内、
     * BDS は BDT の週内、GLONASS は GLONASS 時刻の日内）なので、受信時刻を同じ基準に直して差を取る。
     */
    fun pseudorange(m: PhoneMeas, gnssId: Int, tRxGps: Long, frac: Double, leapSeconds: Int): Range {
        val towOk = m.state and (STATE_TOW_DECODED or STATE_TOW_KNOWN) != 0
        val period: Long
        val rx: Long
        val known: Boolean
        when (gnssId) {
            3 -> {
                period = WEEK_NS
                rx = Math.floorMod(tRxGps - BDS_OFFSET_NS, WEEK_NS)
                known = towOk
            }
            6 -> {
                period = DAY_NS
                rx = Math.floorMod(tRxGps - leapSeconds * 1_000_000_000L + GLO_OFFSET_NS, DAY_NS)
                known = m.state and (STATE_GLO_TOD_DECODED or STATE_GLO_TOD_KNOWN) != 0
            }
            2 -> if (!towOk && m.state and STATE_GAL_E1C_2ND_CODE_LOCK != 0) {
                period = GAL_100MS_NS
                rx = Math.floorMod(tRxGps, GAL_100MS_NS)
                known = true
            } else {
                period = WEEK_NS
                rx = Math.floorMod(tRxGps, WEEK_NS)
                known = towOk
            }
            else -> {
                period = WEEK_NS
                rx = Math.floorMod(tRxGps, WEEK_NS)
                known = towOk
            }
        }
        var diff = (rx - Math.floorMod(m.receivedSvTimeNanos, period)).toDouble() + frac
        if (period == GAL_100MS_NS) {
            // 100 ms の不定性: 伝搬時間（約 70〜100 ms）を 0〜100 ms に収める
            diff = ((diff % period) + period) % period
        } else {
            // 週・日の跨ぎ（受信側が繰り上がった／衛星側が繰り上がった）を補正する
            if (diff > period / 2.0) diff -= period
            if (diff < -period / 2.0) diff += period
        }
        val pr = diff * SPEED_OF_LIGHT * 1e-9
        return Range(pr, known && pr in PR_MIN..PR_MAX)
    }
}

/**
 * GnssMeasurementsEvent から RXM-RAWX（version 1）を作る。locktime（搬送波位相が続いている時間）と
 * clkReset（受信機の時計の不連続）を数えるので、データ源ごとに 1 つ持つ。
 */
class PhoneRawx {
    private var lastDiscontinuity: Int? = null
    private var pendingReset = false
    /** (gnssId, svId, sigId) → 搬送波位相が続き始めた受信時刻 [ns] */
    private val lockStart = HashMap<Triple<Int, Int, Int>, Long>()

    /** 最後に RAWX を出さなかった理由（出したら null） */
    var skipReason: String? = null
        private set

    /**
     * 1 epoch 分の RXM-RAWX を返す。fullBiasNanos が無い・時計が不連続になった epoch は null
     * （不連続の次の epoch で recStat の clkReset を立てる）。
     */
    fun encode(clock: PhoneClock, meas: List<PhoneMeas>): ByteArray? {
        val fullBias = clock.fullBiasNanos
        if (fullBias == null) {
            skipReason = "時計の GPS 時刻が未確定"
            return null
        }
        val previous = lastDiscontinuity
        lastDiscontinuity = clock.discontinuityCount
        if (previous != null && previous != clock.discontinuityCount) {
            pendingReset = true
            lockStart.clear()
            skipReason = "時計が不連続"
            return null
        }
        val leap = clock.leapSecond ?: PhoneUbx.DEFAULT_LEAP_SECONDS
        // 受信時刻（GPS 起点からの ns）。整数部と端数に分けて精度を保つ
        val tRx = clock.timeNanos - fullBias
        val frac = -clock.biasNanos
        val week = Math.floorDiv(tRx, PhoneUbx.WEEK_NS)
        val rcvTow = (Math.floorMod(tRx, PhoneUbx.WEEK_NS) + frac) * 1e-9

        val body = LeWriter()
        val seen = HashSet<Triple<Int, Int, Int>>()
        val active = HashSet<Triple<Int, Int, Int>>()
        var count = 0
        for (m in meas) {
            if (count >= 255) break
            val gnssId = PhoneUbx.gnssId(m.constellation) ?: continue
            val svId = PhoneUbx.svId(gnssId, m.svid) ?: continue
            val sigId = PhoneUbx.sigId(gnssId, m.carrierFrequencyHz, m.codeType) ?: continue
            val key = Triple(gnssId, svId, sigId)
            if (!seen.add(key)) continue
            val freq = m.carrierFrequencyHz ?: PhoneUbx.defaultFrequency(gnssId)
            val lambda = PhoneUbx.SPEED_OF_LIGHT / freq
            val range = PhoneUbx.pseudorange(m, gnssId, tRx, frac + m.timeOffsetNanos, leap)

            val adrValid = m.adrState and PhoneUbx.ADR_STATE_VALID != 0 && m.adrState and PhoneUbx.ADR_STATE_RESET == 0
            val slip = m.adrState and PhoneUbx.ADR_STATE_CYCLE_SLIP != 0
            val locktime: Int
            if (adrValid && !slip) {
                val start = lockStart.getOrPut(key) { tRx }
                locktime = ((tRx - start) / 1_000_000L).coerceIn(0L, 65535L).toInt()
                active.add(key)
            } else {
                if (adrValid) {
                    lockStart[key] = tRx   // サイクルスリップ: ここから数え直す
                    active.add(key)
                } else {
                    lockStart.remove(key)
                }
                locktime = 0
            }
            var trk = 0
            if (range.valid) trk = trk or 0x01
            if (adrValid) {
                trk = trk or 0x02
                if (m.adrState and PhoneUbx.ADR_STATE_HALF_CYCLE_RESOLVED != 0) trk = trk or 0x04
                if (m.adrState and PhoneUbx.ADR_STATE_HALF_CYCLE_REPORTED != 0) trk = trk or 0x08
            }
            val prSigma = m.receivedSvTimeUncertaintyNanos * PhoneUbx.SPEED_OF_LIGHT * 1e-9
            body.f8(range.meters)
                .f8(if (adrValid) m.adr / lambda else 0.0)
                .f4((-m.pseudorangeRate / lambda).toFloat())
                .u1(gnssId).u1(svId).u1(sigId)
                .u1(if (gnssId == 6) PhoneUbx.glonassFreqId(m.carrierFrequencyHz) else 0)
                .u2(locktime)
                .u1(m.cn0.roundToInt().coerceIn(0, 255))
                .u1(PhoneUbx.prStdevCode(prSigma))
                .u1(PhoneUbx.cpStdevCode(m.adrUncertainty / lambda))
                .u1(PhoneUbx.doStdevCode(m.pseudorangeRateUncertainty / lambda))
                .u1(trk)
                .u1(0)
            count++
        }
        // この epoch に出てこなかった信号は位相が切れたものとする
        lockStart.keys.retainAll(active)

        var recStat = 0
        if (clock.leapSecond != null) recStat = recStat or 0x01
        if (pendingReset) recStat = recStat or 0x02
        pendingReset = false
        skipReason = null
        val header = LeWriter()
            .f8(rcvTow)
            .u2(week.toInt())
            .u1(leap)
            .u1(count)
            .u1(recStat)
            .u1(1)          // version
            .zeros(2)
        return Ubx.build(Ubx.RXM_RAWX, header.toByteArray() + body.toByteArray())
    }
}
