package jp.kjtr.ublox.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * RTCM3 の解析（フレーム分離・CRC-24Q・メッセージ番号・1005 / 1006 の基準局位置）、
 * 基線長、NAV-PVT からの GGA 文。移植元: ublox_monitor/rtcm.py の受信スレッド以外（受信は Ntrip.kt）。
 */

/** 基準局（1005 / 1006）。ECEF は m、緯度経度は度、高さは楕円体高 m。 */
data class Station(
    val id: Int, val x: Double, val y: Double, val z: Double,
    val lat: Double, val lon: Double, val height: Double,
)

object Rtcm {
    const val WGS84_A = 6378137.0
    const val WGS84_F = 1 / 298.257223563

    val MESSAGE_NAMES = mapOf(
        1001 to "GPS L1 観測量", 1002 to "GPS L1 観測量", 1003 to "GPS L1/L2 観測量",
        1004 to "GPS L1/L2 観測量", 1005 to "基準局の位置", 1006 to "基準局の位置 + アンテナ高",
        1007 to "アンテナ情報", 1008 to "アンテナ情報", 1009 to "GLONASS L1 観測量",
        1010 to "GLONASS L1 観測量", 1011 to "GLONASS L1/L2 観測量", 1012 to "GLONASS L1/L2 観測量",
        1013 to "システムパラメータ", 1019 to "GPS エフェメリス", 1020 to "GLONASS エフェメリス",
        1033 to "受信機・アンテナ情報", 1042 to "BeiDou エフェメリス", 1044 to "QZSS エフェメリス",
        1045 to "Galileo F/NAV エフェメリス", 1046 to "Galileo I/NAV エフェメリス",
        1230 to "GLONASS コードバイアス", 4072 to "u-blox 独自（移動基準局）",
    )
    private val MSM_SYSTEMS = mapOf(
        107 to "GPS", 108 to "GLONASS", 109 to "Galileo", 110 to "SBAS", 111 to "QZSS",
        112 to "BeiDou", 113 to "NavIC",
    )

    /** RTCM3 メッセージ番号の説明を返す。 */
    fun messageName(msgType: Int): String {
        MESSAGE_NAMES[msgType]?.let { return it }
        val system = MSM_SYSTEMS[msgType / 10]
        if (system != null && msgType % 10 in 1..7) return "$system MSM${msgType % 10}"
        return ""
    }

    // ---- RTCM3 フレーム ----

    private val CRC_TABLE = IntArray(256) { byte ->
        var crc = byte shl 16
        repeat(8) {
            crc = crc shl 1
            if (crc and 0x1000000 != 0) crc = crc xor 0x1864CFB
        }
        crc and 0xFFFFFF
    }

    /** RTCM3 の CRC-24Q を返す。 */
    fun crc24q(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = ((crc shl 8) and 0xFFFFFF) xor CRC_TABLE[(crc shr 16) xor (data[i].toInt() and 0xFF)]
        }
        return crc
    }

    /** RTCM3 フレームを組み立てる（テスト・偽キャスター用）。 */
    fun buildFrame(payload: ByteArray): ByteArray {
        val out = ByteArray(3 + payload.size + 3)
        out[0] = 0xD3.toByte()
        out[1] = ((payload.size shr 8) and 0x03).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 3)
        val crc = crc24q(out, 0, 3 + payload.size)
        out[3 + payload.size] = (crc shr 16).toByte()
        out[4 + payload.size] = (crc shr 8).toByte()
        out[5 + payload.size] = crc.toByte()
        return out
    }

    /** data の start ビット目から length ビット（64 まで）を整数で返す（MSB が先）。範囲外のビットは 0。 */
    fun getBits(data: ByteArray, start: Int, length: Int, signed: Boolean = false): Long {
        var v = 0L
        for (i in start until start + length) {
            val byte = i ushr 3
            val bit = if (byte < data.size) (data[byte].toInt() shr (7 - (i and 7))) and 1 else 0
            v = (v shl 1) or bit.toLong()
        }
        if (signed && length in 1..63 && (v shr (length - 1)) and 1L == 1L) v -= 1L shl length
        return v
    }

    /** RTCM3 のメッセージ番号を返す。 */
    fun messageType(payload: ByteArray): Int? = if (payload.size >= 2) getBits(payload, 0, 12).toInt() else null

    /** 1005 / 1006 から基準局の情報を返す。 */
    fun parseStation(payload: ByteArray): Station? {
        val type = messageType(payload)
        if ((type != 1005 && type != 1006) || payload.size < 19) return null
        val x = getBits(payload, 34, 38, true) * 1e-4
        val y = getBits(payload, 74, 38, true) * 1e-4
        val z = getBits(payload, 114, 38, true) * 1e-4
        val (lat, lon, h) = ecefToLlh(x, y, z)
        return Station(getBits(payload, 12, 12).toInt(), x, y, z, lat, lon, h)
    }

    /** WGS84 の ECEF [m] を（緯度 [°], 経度 [°], 楕円体高 [m]）にする。 */
    fun ecefToLlh(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val e2 = WGS84_F * (2 - WGS84_F)
        val lon = atan2(y, x)
        val p = hypot(x, y)
        var lat = atan2(z, p * (1 - e2))
        repeat(6) {
            val n = WGS84_A / sqrt(1 - e2 * sin(lat) * sin(lat))
            val height = p / cos(lat) - n
            lat = atan2(z, p * (1 - e2 * n / (n + height)))
        }
        val n = WGS84_A / sqrt(1 - e2 * sin(lat) * sin(lat))
        val height = p / cos(lat) - n
        return Triple(Math.toDegrees(lat), Math.toDegrees(lon), height)
    }

    /** （緯度 [°], 経度 [°], 楕円体高 [m]）を WGS84 の ECEF [m] にする。 */
    fun llhToEcef(latDeg: Double, lonDeg: Double, height: Double): Triple<Double, Double, Double> {
        val e2 = WGS84_F * (2 - WGS84_F)
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val n = WGS84_A / sqrt(1 - e2 * sin(lat) * sin(lat))
        return Triple(
            (n + height) * cos(lat) * cos(lon),
            (n + height) * cos(lat) * sin(lon),
            (n * (1 - e2) + height) * sin(lat),
        )
    }

    /** 基準局から現在位置までの距離 [m] を返す。分からなければ null。 */
    fun baseline(station: Station?, pvt: Pvt?): Double? {
        if (station == null || pvt?.latitude == null || pvt.longitude == null || pvt.height == null) return null
        val (x, y, z) = llhToEcef(pvt.latitude, pvt.longitude, pvt.height)
        val dx = x - station.x
        val dy = y - station.y
        val dz = z - station.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ---- NMEA GGA（VRS などのキャスターへ現在位置を送る） ----

    /** 度を NMEA の ddmm.mmmmm 形式にする。 */
    private fun nmeaAngle(value: Double, width: Int): String {
        val v = abs(value)
        val degrees = v.toInt()
        val minutes = (v - degrees) * 60
        return String.format(Locale.US, "%0${width}d%08.5f", degrees, minutes)
    }

    /** NAV-PVT から GGA 文を作る。位置が無ければ null。 */
    fun buildGga(pvt: Pvt?): String? {
        val lat = pvt?.latitude ?: return null
        val lon = pvt.longitude ?: return null
        val height = pvt.height ?: return null
        val msl = pvt.heightMsl ?: height
        val quality = when (pvt.fixRank) {
            8 -> 4; 7 -> 5; 6 -> 2; else -> 1
        }
        val clock = pvt.utc.split(" ").getOrElse(1) { "00:00:00" }.replace(":", "")
        val body = String.format(
            Locale.US, "GPGGA,%s.00,%s,%s,%s,%s,%d,%02d,%.1f,%.3f,M,%.3f,M,,",
            clock, nmeaAngle(lat, 2), if (lat >= 0) "N" else "S", nmeaAngle(lon, 3), if (lon >= 0) "E" else "W",
            quality, minOf(pvt.numSv, 99), pvt.pdop, msl, height - msl,
        )
        var checksum = 0
        for (c in body.toByteArray(Charsets.US_ASCII)) checksum = checksum xor (c.toInt() and 0xFF)
        return "\$" + body + String.format(Locale.US, "*%02X\r\n", checksum)
    }
}

/** バイト列から RTCM3 フレームを取り出す。CRC が合わないものは捨てる。 */
class RtcmStream {
    private var buffer = ByteArray(0)
    var crcErrors = 0
        private set

    /** data を追加し、完成したフレームのペイロードのリストを返す。 */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val buf = ByteArray(buffer.size + length)
        buffer.copyInto(buf)
        data.copyInto(buf, buffer.size, offset, offset + length)
        val frames = ArrayList<ByteArray>()
        var pos = 0
        while (true) {
            var start = pos
            while (start < buf.size && buf[start] != 0xD3.toByte()) start++
            if (start >= buf.size) {
                pos = buf.size
                break
            }
            if (buf.size - start < 3) {
                pos = start
                break
            }
            val len = ((buf[start + 1].toInt() and 0x03) shl 8) or (buf[start + 2].toInt() and 0xFF)
            val end = start + 3 + len + 3
            if (buf.size < end) {
                pos = start
                break
            }
            val crc = ((buf[end - 3].toInt() and 0xFF) shl 16) or ((buf[end - 2].toInt() and 0xFF) shl 8) or
                (buf[end - 1].toInt() and 0xFF)
            if (Rtcm.crc24q(buf, start, end - 3) == crc) {
                frames.add(buf.copyOfRange(start + 3, end - 3))
                pos = end
            } else {
                crcErrors++
                pos = start + 1
            }
        }
        buffer = buf.copyOfRange(pos, buf.size)
        return frames
    }
}
