package jp.kjtr.ubloxmonitor.domain

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** テストと動作確認用の UBX メッセージを作る（移植元: tests/ubx_samples.py）。 */
object UbxSamples {
    /** (gnssId, svId, 方位, 仰角, [(sigId, C/N0)]) */
    data class Sat(val gnss: Int, val sv: Int, val azim: Int, val elev: Int, val signals: List<Pair<Int, Int>>)

    val SATELLITES = listOf(
        Sat(0, 5, 40, 62, listOf(0 to 45, 3 to 40)), Sat(0, 13, 120, 35, listOf(0 to 41, 3 to 36)),
        Sat(0, 15, 210, 48, listOf(0 to 43)), Sat(0, 24, 300, 20, listOf(0 to 33, 3 to 28)),
        Sat(2, 4, 80, 55, listOf(0 to 42, 5 to 39)), Sat(2, 11, 170, 28, listOf(0 to 37, 5 to 34)),
        Sat(2, 19, 260, 70, listOf(0 to 46, 5 to 43)), Sat(3, 21, 20, 44, listOf(0 to 40, 2 to 38)),
        Sat(3, 35, 150, 66, listOf(0 to 44, 2 to 41)), Sat(5, 2, 190, 78, listOf(0 to 44, 5 to 41)),
        Sat(5, 7, 200, 47, listOf(0 to 40, 5 to 37)), Sat(6, 8, 330, 31, listOf(0 to 38, 2 to 33)),
    )

    /** Python の round()（偶数丸め）。 */
    private fun pyRound(v: Double): Long = Math.rint(v).toLong()

    /** NAV-PVT を作る。carrier は 0: なし、1: Float、2: Fixed。 */
    fun navPvt(
        itow: Long, lat: Double = 35.6812, lon: Double = 139.7671, carrier: Int = 2, numSv: Int = 12,
        correctionAge: Int = 0,
    ): ByteArray {
        val second = (itow / 1000 % 60).toInt()
        val w = LeWriter().u4(itow).u2(2026).u1(11).u1(17).u1(3).u1((itow / 60000 % 60).toInt()).u1(second)
            .u1(0x07).u4(20).i4(0).u1(3).u1(0x01 or 0x02 or (carrier shl 6)).u1(0).u1(numSv)
            .i4(pyRound(lon * 1e7).toInt()).i4(pyRound(lat * 1e7).toInt()).i4(45123).i4(8456).u4(14).u4(21)
            .i4(0).i4(0).i4(0).i4(1250).i4(9000000).u4(50).u4(100000).u2(120)
        val payload = w.toByteArray().copyOf(92)
        val flags3 = correctionAge shl 1
        payload[78] = (flags3 and 0xFF).toByte()
        payload[79] = (flags3 shr 8).toByte()
        return Ubx.build(Ubx.NAV_PVT, payload)
    }

    /** NAV-SAT を作る。 */
    fun navSat(itow: Long): ByteArray {
        val w = LeWriter().u4(itow).u1(1).u1(SATELLITES.size).u2(0)
        for (s in SATELLITES) {
            w.u1(s.gnss).u1(s.sv).u1(s.signals[0].second).u1(s.elev).u2(s.azim).u2(0)
                .u4(if (s.elev >= 25) 0x0F else 0x04)
        }
        return Ubx.build(Ubx.NAV_SAT, w.toByteArray())
    }

    /** RXM-RAWX を作る。 */
    fun rxmRawx(tow: Double): ByteArray {
        val meas = LeWriter()
        var count = 0
        for (s in SATELLITES) {
            for ((sigId, cno) in s.signals) {
                val wobble = pyRound(2 * sin(tow / 7 + s.sv)).toInt()
                meas.f8(2.1e7).f8(1.1e8).f4(-500.0f).u1(s.gnss).u1(s.sv).u1(sigId).u1(0).u2(64500)
                    .u1(cno + wobble).u1(5).u1(2).u1(8).u1(0x03).u1(0)
                count++
            }
        }
        val header = LeWriter().f8(tow).u2(2393).u1(18).u1(count).u1(0x01).u1(1).u1(0).u1(0)
        return Ubx.build(Ubx.RXM_RAWX, header.toByteArray() + meas.toByteArray())
    }

    /** RXM-MEASX を作る。 */
    fun rxmMeasx(itow: Long): ByteArray {
        val head = ByteArray(44)
        head[34] = SATELLITES.size.toByte()
        val iw = LeWriter().u4(itow).toByteArray()
        iw.copyInto(head, 4)
        val w = LeWriter().bytes(head)
        SATELLITES.forEachIndexed { index, s ->
            w.u1(s.gnss).u1(s.sv).u1(s.signals[0].second).u1(index % 4).i4(1000).i4(1000)
                .u2(500 + index).u2(300).u4(123456).u1(7).u1(10 + index * 3).u2(0)
        }
        return Ubx.build(Ubx.RXM_MEASX, w.toByteArray())
    }

    /** GPS / QZSS L1C/A の RXM-SFRBX を作る。 */
    fun rxmSfrbx(gnssId: Int, svId: Int, subframe: Int): ByteArray {
        val words = listOf(0x22C00000L, (subframe shl 8).toLong()) + List(8) { 0L }
        val w = LeWriter().u1(gnssId).u1(svId).u1(0).u1(0).u1(words.size).u1(0).u1(2).u1(0)
        words.forEach { w.u4(it) }
        return Ubx.build(Ubx.RXM_SFRBX, w.toByteArray())
    }

    private fun padded(text: String, size: Int) = text.toByteArray(Charsets.US_ASCII).copyOf(size)

    /** MON-VER を作る。 */
    fun monVer(module: String = "ZED-F9P", protver: String = "27.50", hardware: String = "00190000"): ByteArray {
        val w = LeWriter().bytes(padded("EXT CORE 1.00 (abcdef)", 30)).bytes(padded(hardware, 10))
        for (ext in listOf("ROM BASE 0x118B2060", "FWVER=HPG 1.50", "PROTVER=$protver", "MOD=$module")) {
            w.bytes(padded(ext, 30))
        }
        return Ubx.build(Ubx.MON_VER, w.toByteArray())
    }

    /** MON-SPAN を作る（L1 帯と L2/L5 帯の 2 ブロック）。 */
    fun monSpan(phase: Int = 0): ByteArray {
        val w = LeWriter().u1(0).u1(2).u1(0).u1(0)
        val blocks = listOf(
            1583.4e6 to listOf(1575.42, 1561.098, 1602.0),
            1214.0e6 to listOf(1227.60, 1176.45, 1207.14, 1246.0),
        )
        for ((center, bumps) in blocks) {
            val span = 128_000_000L
            val res = 500_000L
            for (index in 0 until 256) {
                val freq = (center + span * (index - 128) / 256.0) / 1e6
                var level = 150 + 6 * sin(index / 9.0) + 4 * sin(index * 1.7 + phase * 2.3)
                level += bumps.sumOf { 40 * exp(-((freq - it) / 4).pow(2)) }
                val edge = minOf(index, 255 - index)
                level -= maxOf(0, 30 - edge) * 2
                w.u1(pyRound(level).coerceIn(0, 255).toInt())
            }
            w.u4(span).u4(res).u4(pyRound(center)).u1(54).zeros(3)
        }
        return Ubx.build(Ubx.MON_SPAN, w.toByteArray())
    }

    /** seconds 秒分の 1 Hz のログを作る。位置は少しずつ北東へ動く。 */
    fun log(seconds: Int = 30, startItow: Long = 100000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(monVer())
        for (index in 0 until seconds) {
            val itow = startItow + index * 1000L
            val carrier = if (index % 10 < 7) 2 else 1
            out.write(navPvt(itow, 35.6812 + index * 2e-6, 139.7671 + index * 3e-6, carrier, correctionAge = 2))
            out.write(navSat(itow))
            out.write(rxmRawx(itow / 1000.0))
            out.write(rxmMeasx(itow))
            out.write(rxmSfrbx(0, 5, index % 5 + 1))
            out.write(rxmSfrbx(5, 2, index % 3 + 1))
            out.write(monSpan(index))
        }
        return out.toByteArray()
    }
}
