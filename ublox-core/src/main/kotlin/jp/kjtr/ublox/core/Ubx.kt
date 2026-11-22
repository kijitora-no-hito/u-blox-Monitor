package jp.kjtr.ublox.core

/*
 * UBX プロトコルの分離（チェックサム検査）、デコード、送信パケットの組み立て。
 * 移植元: ublox_monitor/ubx.py（Python 版）。Android には依存しない。
 */

/** UBX のメッセージ種別（クラス, ID）。 */
data class MsgId(val cls: Int, val id: Int) {
    override fun toString(): String = Ubx.MESSAGE_NAMES[this] ?: "%02X-%02X".format(cls, id)
}

object Ubx {
    val SYNC = byteArrayOf(0xB5.toByte(), 0x62)
    const val MAX_PAYLOAD = 8192

    val NAV_PVT = MsgId(0x01, 0x07)
    val NAV_SAT = MsgId(0x01, 0x35)
    val RXM_SFRBX = MsgId(0x02, 0x13)
    val RXM_MEASX = MsgId(0x02, 0x14)
    val RXM_RAWX = MsgId(0x02, 0x15)
    val ACK_NAK = MsgId(0x05, 0x00)
    val ACK_ACK = MsgId(0x05, 0x01)
    val CFG_MSG = MsgId(0x06, 0x01)
    val CFG_RATE = MsgId(0x06, 0x08)
    val CFG_NAV5 = MsgId(0x06, 0x24)
    val CFG_VALSET = MsgId(0x06, 0x8A)
    val CFG_VALGET = MsgId(0x06, 0x8B)
    val MON_VER = MsgId(0x0A, 0x04)
    val MON_SPAN = MsgId(0x0A, 0x31)

    val MESSAGE_NAMES: Map<MsgId, String> = mapOf(
        MON_SPAN to "MON-SPAN",
        NAV_PVT to "NAV-PVT", NAV_SAT to "NAV-SAT", RXM_SFRBX to "RXM-SFRBX", RXM_MEASX to "RXM-MEASX",
        RXM_RAWX to "RXM-RAWX", ACK_NAK to "ACK-NAK", ACK_ACK to "ACK-ACK", CFG_MSG to "CFG-MSG",
        CFG_RATE to "CFG-RATE", CFG_NAV5 to "CFG-NAV5", CFG_VALSET to "CFG-VALSET",
        CFG_VALGET to "CFG-VALGET", MON_VER to "MON-VER",
    )

    /** gnssId → (名称, 衛星名の接頭辞) */
    val GNSS: Map<Int, Pair<String, String>> = mapOf(
        0 to ("GPS" to "G"), 1 to ("SBAS" to "S"), 2 to ("GALILEO" to "E"), 3 to ("BEIDOU" to "C"),
        4 to ("IMES" to "I"), 5 to ("QZSS" to "J"), 6 to ("GLONASS" to "R"), 7 to ("NAVIC" to "N"),
    )

    /** (gnssId, sigId) → 信号名 */
    val SIGNALS: Map<Pair<Int, Int>, String> = mapOf(
        (0 to 0) to "L1CA", (0 to 3) to "L2CL", (0 to 4) to "L2CM", (0 to 6) to "L5I", (0 to 7) to "L5Q",
        (1 to 0) to "L1CA",
        (2 to 0) to "E1C", (2 to 1) to "E1B", (2 to 3) to "E5aI", (2 to 4) to "E5aQ", (2 to 5) to "E5bI",
        (2 to 6) to "E5bQ", (2 to 8) to "E6B", (2 to 9) to "E6C", (2 to 10) to "E6A",
        (3 to 0) to "B1ID1", (3 to 1) to "B1ID2", (3 to 2) to "B2ID1", (3 to 3) to "B2ID2", (3 to 4) to "B3ID1",
        (3 to 10) to "B3ID2", (3 to 5) to "B1Cp", (3 to 6) to "B1Cd", (3 to 7) to "B2ap", (3 to 8) to "B2ad",
        (5 to 0) to "L1CA", (5 to 1) to "L1S", (5 to 4) to "L2CM", (5 to 5) to "L2CL", (5 to 8) to "L5I",
        (5 to 9) to "L5Q", (5 to 10) to "L6D", (5 to 11) to "L6E", (5 to 12) to "L1CB",
        (6 to 0) to "L1OF", (6 to 2) to "L2OF",
        (7 to 0) to "L5A",
    )

    /** 帯域（L1 / L2 / L5 / L6） */
    val BANDS = listOf("L1", "L2", "L5", "L6")

    /** (gnssId, sigId) → 帯域 */
    val SIGNAL_BANDS: Map<Pair<Int, Int>, String> = mapOf(
        (0 to 0) to "L1", (0 to 3) to "L2", (0 to 4) to "L2", (0 to 6) to "L5", (0 to 7) to "L5",
        (1 to 0) to "L1",
        (2 to 0) to "L1", (2 to 1) to "L1", (2 to 3) to "L5", (2 to 4) to "L5", (2 to 5) to "L5", (2 to 6) to "L5",
        (2 to 8) to "L6", (2 to 9) to "L6", (2 to 10) to "L6",
        (3 to 0) to "L1", (3 to 1) to "L1", (3 to 2) to "L5", (3 to 3) to "L5", (3 to 4) to "L6", (3 to 10) to "L6",
        (3 to 5) to "L1", (3 to 6) to "L1", (3 to 7) to "L5", (3 to 8) to "L5",
        (5 to 0) to "L1", (5 to 1) to "L1", (5 to 4) to "L2", (5 to 5) to "L2", (5 to 8) to "L5", (5 to 9) to "L5",
        (5 to 10) to "L6", (5 to 11) to "L6", (5 to 12) to "L1",
        (6 to 0) to "L1", (6 to 2) to "L2",
        (7 to 0) to "L5",
    )

    /** 測位状態の順位（グラフの縦軸と色分けに使う） */
    val FIX_LABELS = listOf(
        "No Fix", "DR", "Time", "2D", "3D", "GNSS+DR", "DGNSS", "RTK Float", "RTK Fixed",
    )
    private val FIX_TYPE_RANKS = mapOf(0 to 0, 1 to 1, 2 to 3, 3 to 4, 4 to 5, 5 to 2)

    /** NAV-PVT flags3 の lastCorrectionAge（bit 1-4） */
    val CORRECTION_AGES = mapOf(
        1 to "1 秒未満", 2 to "1〜2 秒", 3 to "2〜5 秒", 4 to "5〜10 秒", 5 to "10〜15 秒",
        6 to "15〜20 秒", 7 to "20〜30 秒", 8 to "30〜45 秒", 9 to "45〜60 秒",
        10 to "60〜90 秒", 11 to "90〜120 秒", 12 to "120 秒以上",
    )

    /** 衛星名（G05、J02 など）を返す。 */
    fun satName(gnssId: Int, svId: Int): String {
        val prefix = GNSS[gnssId]?.second ?: "?"
        val sv = if (gnssId == 1 && svId >= 100) svId - 100 else svId
        return "%s%02d".format(prefix, sv)
    }

    /** 衛星系の名称を返す。 */
    fun systemName(gnssId: Int): String = GNSS[gnssId]?.first ?: "OTHER"

    /** 信号名を返す。未知の信号は sigN とする。 */
    fun signalName(gnssId: Int, sigId: Int): String = SIGNALS[gnssId to sigId] ?: "sig$sigId"

    /** クラスからペイロード末尾までの 8 ビット Fletcher チェックサムを返す。 */
    fun checksum(data: ByteArray, from: Int = 0, to: Int = data.size): ByteArray {
        var a = 0
        var b = 0
        for (i in from until to) {
            a = (a + (data[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        return byteArrayOf(a.toByte(), b.toByte())
    }

    /** UBX パケットを組み立てる。 */
    fun build(msg: MsgId, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = SYNC[0]
        out[1] = SYNC[1]
        out[2] = msg.cls.toByte()
        out[3] = msg.id.toByte()
        out[4] = (payload.size and 0xFF).toByte()
        out[5] = (payload.size shr 8 and 0xFF).toByte()
        payload.copyInto(out, 6)
        val ck = checksum(out, 2, 6 + payload.size)
        out[6 + payload.size] = ck[0]
        out[7 + payload.size] = ck[1]
        return out
    }

    // ---- デコード ----

    /** NAV-PVT をデコードする。短すぎる場合は null。 */
    fun parseNavPvt(payload: ByteArray): Pvt? {
        if (payload.size < 84) return null
        val r = LeReader(payload)
        val itow = r.u4(0)
        val year = r.u2(4)
        val month = r.u1(6)
        val day = r.u1(7)
        val hour = r.u1(8)
        val minute = r.u1(9)
        val second = r.u1(10)
        val valid = r.u1(11)
        val tacc = r.u4(12)
        val fixType = r.u1(20)
        val flags = r.u1(21)
        val numSv = r.u1(23)
        val lon = r.i4(24)
        val lat = r.i4(28)
        val height = r.i4(32)
        val hmsl = r.i4(36)
        val hacc = r.u4(40)
        val vacc = r.u4(44)
        val gspeed = r.i4(60)
        val headMot = r.i4(64)
        val sacc = r.u4(68)
        val headAcc = r.u4(72)
        val nano = r.i4(16)
        val pdop = r.u2(76)
        val fixOk = flags and 0x01 != 0
        val carrier = (flags shr 6) and 0x03
        val rank = when {
            !fixOk -> 0
            carrier == 2 -> 8
            carrier == 1 -> 7
            flags and 0x02 != 0 && fixType in listOf(2, 3, 4) -> 6
            else -> FIX_TYPE_RANKS[fixType] ?: 0
        }
        val hasPos = fixOk && fixType in listOf(1, 2, 3, 4)
        val flags3 = r.u2(78)
        return Pvt(
            itow = itow,
            utc = "%04d/%02d/%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second),
            timeValid = valid and 0x03 == 0x03,
            fixRank = rank, numSv = numSv,
            latitude = if (hasPos) lat * 1e-7 else null,
            longitude = if (hasPos) lon * 1e-7 else null,
            height = if (hasPos) height / 1000.0 else null,
            heightMsl = if (hasPos) hmsl / 1000.0 else null,
            hacc = if (hasPos) hacc / 1000.0 else null,
            vacc = if (hasPos) vacc / 1000.0 else null,
            speed = if (hasPos) gspeed / 1000.0 else null,
            heading = if (hasPos) headMot * 1e-5 else null,
            pdop = pdop * 0.01, tacc = tacc,
            diffSoln = flags and 0x02 != 0, correctionAge = (flags3 shr 1) and 0x0F,
            fixType = fixType, gnssFixOk = fixOk, carrSoln = carrier,
            utcMillis = if (valid and 0x03 == 0x03) utcMillis(year, month, day, hour, minute, second, nano) else null,
            sacc = if (hasPos) sacc / 1000.0 else null,
            headAcc = if (hasPos) headAcc * 1e-5 else null,
        )
    }

    /** NAV-PVT の UTC の日付・時刻（nano は -1e9〜1e9 の補正）を UNIX 時刻 [ms] にする。 */
    fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, nano: Int): Long? {
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        // java.time は Android 7（API 24・25）に無いので、暦の計算は自前（days from civil）
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468
        val seconds = days * 86400L + hour * 3600L + minute * 60L + second
        return seconds * 1000L + Math.floorDiv(nano, 1_000_000)
    }

    /** NAV-SAT をリストにする。仰角・方位が無効な衛星は null を入れる。 */
    fun parseNavSat(payload: ByteArray): List<NavSat> {
        if (payload.size < 8) return emptyList()
        val r = LeReader(payload)
        val count = minOf(r.u1(5), (payload.size - 8) / 12)
        return (0 until count).map { index ->
            val off = 8 + index * 12
            val elev = r.i1(off + 3)
            val azim = r.i2(off + 4)
            val flags = r.u4(off + 8)
            val quality = (flags and 0x07).toInt()
            val known = elev in -90..90 && (elev != 0 || azim != 0 || quality >= 4)
            NavSat(
                gnss = r.u1(off), sv = r.u1(off + 1), cno = r.u1(off + 2),
                elev = if (known) elev else null, azim = if (known) azim else null,
                used = flags and 0x08 != 0L, quality = quality,
            )
        }
    }

    /** RXM-RAWX を（受信機時刻 TOW, 測定のリスト）にする。 */
    fun parseRxmRawx(payload: ByteArray): Pair<Double?, List<RawxMeas>> {
        if (payload.size < 16) return null to emptyList()
        val r = LeReader(payload)
        val rcvTow = r.f8(0)
        val count = minOf(r.u1(11), (payload.size - 16) / 32)
        val meas = (0 until count).map { index ->
            val off = 16 + index * 32
            val trk = r.u1(off + 30)
            RawxMeas(
                gnss = r.u1(off + 20), sv = r.u1(off + 21), sig = r.u1(off + 22), cno = r.u1(off + 26),
                prValid = trk and 0x01 != 0, cpValid = trk and 0x02 != 0,
            )
        }
        return rcvTow to meas
    }

    /** RXM-MEASX をリストにする。 */
    fun parseRxmMeasx(payload: ByteArray): List<MeasxMeas> {
        if (payload.size < 44) return emptyList()
        val r = LeReader(payload)
        val count = minOf(r.u1(34), (payload.size - 44) / 24)
        return (0 until count).map { index ->
            val off = 44 + index * 24
            MeasxMeas(
                gnss = r.u1(off), sv = r.u1(off + 1), cno = r.u1(off + 2), mpath = r.u1(off + 3),
                wChips = r.u2(off + 12), fChips = r.u2(off + 14), cPhase = r.u4(off + 16),
                iPhase = r.u1(off + 20), rmse = r.u1(off + 21),
            )
        }
    }

    /** RXM-SFRBX をデコードする。subframe は先頭ワードから得たサブフレーム番号（GPS 系）。 */
    fun parseRxmSfrbx(payload: ByteArray): Sfrbx? {
        if (payload.size < 8) return null
        val r = LeReader(payload)
        val gnss = r.u1(0)
        val sv = r.u1(1)
        val sig = r.u1(2)
        val numWords = r.u1(4)
        var subframe = 0
        if (numWords >= 2 && payload.size >= 16 && gnss in listOf(0, 5) && sig == 0) {
            // GPS / QZSS L1C/A の LNAV: 2 ワード目（HOW）の bit 8-10 がサブフレーム番号
            subframe = ((r.u4(12) shr 8) and 0x07).toInt()
        }
        return Sfrbx(gnss, sv, sig, subframe)
    }

    /** MON-VER をデコードする。gen は M8 / F9 / X20 / Unknown。 */
    fun parseMonVer(payload: ByteArray): MonVer? {
        if (payload.size < 40) return null
        fun text(from: Int, to: Int): String {
            val sb = StringBuilder()
            for (i in from until minOf(to, payload.size)) {
                val c = payload[i].toInt() and 0xFF
                if (c == 0) break
                if (c < 0x80) sb.append(c.toChar())   // ascii 以外は捨てる（errors="ignore"）
            }
            return sb.toString()
        }
        val software = text(0, 30)
        val hardware = text(30, 40)
        val extensions = (40 until payload.size - 29 step 30).map { text(it, it + 30) }
        val fields = LinkedHashMap<String, String>()
        for (ext in extensions) {
            val eq = ext.indexOf('=')
            if (eq >= 0) fields[ext.substring(0, eq)] = ext.substring(eq + 1)
        }
        val module = fields["MOD"] ?: ""
        val protver = (fields["PROTVER"] ?: "0").trim().toDoubleOrNull() ?: 0.0
        val gen = when {
            "X20" in module || protver >= 50.0 && protver < 60.0 -> "X20"
            "00080000" in hardware || "M8" in module || protver >= 15.0 && protver <= 23.01 -> "M8"
            "F9" in module || protver >= 27.0 && protver < 50.0 -> "F9"
            else -> "Unknown"
        }
        return MonVer(gen, software, hardware, module, (fields["PROTVER"] ?: "").trim(), extensions)
    }

    const val SPAN_BINS = 256
    const val SPAN_BLOCK_SIZE = 272
    const val SPAN_DB_SCALE = 0.25   // spectrum の 1 単位 [dB]

    /**
     * MON-SPAN（スペクトラムアナライザ）を RF ブロックごとのリストにする。
     * 周波数 [Hz] は center + span * (i - 128) / 256。レベルは 0.25 dB 単位。
     */
    fun parseMonSpan(payload: ByteArray): List<SpanBlock> {
        if (payload.size < 4) return emptyList()
        val r = LeReader(payload)
        val count = minOf(r.u1(1), (payload.size - 4) / SPAN_BLOCK_SIZE)
        return (0 until count).map { index ->
            val off = 4 + index * SPAN_BLOCK_SIZE
            SpanBlock(
                spectrum = DoubleArray(SPAN_BINS) { r.u1(off + it) * SPAN_DB_SCALE },
                span = r.u4(off + SPAN_BINS), res = r.u4(off + SPAN_BINS + 4),
                center = r.u4(off + SPAN_BINS + 8), pga = r.u1(off + SPAN_BINS + 12),
            )
        }
    }

    /** MON-SPAN の 1 ブロックの各点の周波数 [MHz] を返す。 */
    fun spanFrequencies(block: SpanBlock): DoubleArray = DoubleArray(SPAN_BINS) { i ->
        (block.center + block.span * (i - SPAN_BINS / 2).toDouble() / SPAN_BINS) / 1e6
    }

    // ---- 設定（F9 / X20: CFG-VALGET / VALSET） ----

    const val LAYER_RAM = 0x01
    const val LAYER_BBR = 0x02
    const val LAYER_FLASH = 0x04
    private val KEY_SIZES = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8)

    /** 設定キーの値のバイト数を返す（キー ID の bit 28-30 で決まる）。 */
    fun keySize(key: Long): Int = KEY_SIZES[((key shr 28) and 0x07).toInt()] ?: 0

    /** CFG-VALGET の要求を作る。layer 0 は RAM。 */
    fun buildValget(keys: List<Long>, layer: Int = 0): ByteArray {
        val w = LeWriter().u1(0).u1(layer).u1(0).u1(0)
        keys.forEach { w.u4(it) }
        return build(CFG_VALGET, w.toByteArray())
    }

    /**
     * CFG-VALSET を作る。values は {キー: 整数値}（挿入順を保つ）。
     * 符号付きの値も 2 の補数の下位バイトを詰めるので、signed は Python 版との対応のために残す。
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildValset(values: Map<Long, Long>, layers: Int = LAYER_RAM, signed: Set<Long> = emptySet()): ByteArray {
        val w = LeWriter().u1(0).u1(layers).u1(0).u1(0)
        for ((key, value) in values) {
            w.u4(key)
            w.int(value, keySize(key))
        }
        return build(CFG_VALSET, w.toByteArray())
    }

    /** CFG-VALGET の応答を {キー: 値のバイト列} にする。 */
    fun parseValget(payload: ByteArray): LinkedHashMap<Long, ByteArray> {
        val values = LinkedHashMap<Long, ByteArray>()
        val r = LeReader(payload)
        var pos = 4
        while (pos + 4 <= payload.size) {
            val key = r.u4(pos)
            val size = keySize(key)
            if (size == 0 || pos + 4 + size > payload.size) break
            values[key] = payload.copyOfRange(pos + 4, pos + 4 + size)
            pos += 4 + size
        }
        return values
    }

    // ---- 設定（M8: CFG-NAV5 / CFG-RATE / CFG-MSG） ----

    val NAV5_MASKS = mapOf("dynmodel" to 0x0001, "minelev" to 0x0002, "fixmode" to 0x0004, "cno" to 0x0100)

    /** CFG-NAV5 をデコードする。 */
    fun parseNav5(payload: ByteArray): Nav5? {
        if (payload.size < 36) return null
        val r = LeReader(payload)
        return Nav5(dynmodel = r.u1(2), fixmode = r.u1(3), minelev = r.i1(12), minsvs = r.u1(24), mincno = r.u1(25))
    }

    /** 読み出した CFG-NAV5 のペイロード base に changes を反映したパケットを作る。 */
    fun buildNav5(base: ByteArray, changes: Map<String, Int>): ByteArray {
        val payload = base.copyOf(36)
        var mask = 0
        changes["dynmodel"]?.let { payload[2] = it.toByte(); mask = mask or NAV5_MASKS.getValue("dynmodel") }
        changes["fixmode"]?.let { payload[3] = it.toByte(); mask = mask or NAV5_MASKS.getValue("fixmode") }
        changes["minelev"]?.let { payload[12] = it.toByte(); mask = mask or NAV5_MASKS.getValue("minelev") }
        if ("minsvs" in changes || "mincno" in changes) {
            changes["minsvs"]?.let { payload[24] = it.toByte() }
            changes["mincno"]?.let { payload[25] = it.toByte() }
            mask = mask or NAV5_MASKS.getValue("cno")
        }
        payload[0] = (mask and 0xFF).toByte()
        payload[1] = (mask shr 8 and 0xFF).toByte()
        return build(CFG_NAV5, payload)
    }

    /** CFG-RATE をデコードする。 */
    fun parseRate(payload: ByteArray): Rate? {
        if (payload.size < 6) return null
        val r = LeReader(payload)
        return Rate(r.u2(0), r.u2(2), r.u2(4))
    }

    /** CFG-RATE を作る。 */
    fun buildRate(meas: Int, nav: Int, timeref: Int): ByteArray =
        build(CFG_RATE, LeWriter().u2(meas).u2(nav).u2(timeref).toByteArray())

    /** CFG-MSG（接続中のポートへの出力レート設定）を作る。 */
    fun buildCfgMsg(msg: MsgId, rate: Int): ByteArray =
        build(CFG_MSG, byteArrayOf(msg.cls.toByte(), msg.id.toByte(), rate.toByte()))
}

/** バイト列から UBX フレームを取り出す。チェックサムが合わないものは捨てる。 */
class UbxStream {
    private var buf = ByteArray(16384)
    private var len = 0
    var checksumErrors = 0
        private set

    private fun find(from: Int): Int {
        var i = from
        while (i + 1 < len) {
            if (buf[i] == Ubx.SYNC[0] && buf[i + 1] == Ubx.SYNC[1]) return i
            i++
        }
        return -1
    }

    /** data を追加し、完成したフレームを返す。 */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Frame> {
        if (len + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + length))
        data.copyInto(buf, len, offset, offset + length)
        len += length
        val frames = ArrayList<Frame>()
        var pos = 0
        while (true) {
            val start = find(pos)
            if (start < 0) {
                // 末尾が 0xB5 なら次のデータと繋がる可能性がある
                pos = if (len > 0 && buf[len - 1] == Ubx.SYNC[0]) len - 1 else len
                break
            }
            if (len - start < 6) {
                pos = start
                break
            }
            val length2 = (buf[start + 4].toInt() and 0xFF) or ((buf[start + 5].toInt() and 0xFF) shl 8)
            if (length2 > Ubx.MAX_PAYLOAD) {
                pos = start + 2
                continue
            }
            val end = start + 8 + length2
            if (len < end) {
                pos = start
                break
            }
            val ck = Ubx.checksum(buf, start + 2, end - 2)
            if (ck[0] == buf[end - 2] && ck[1] == buf[end - 1]) {
                frames.add(
                    Frame(
                        MsgId(buf[start + 2].toInt() and 0xFF, buf[start + 3].toInt() and 0xFF),
                        buf.copyOfRange(start + 6, end - 2),
                    )
                )
                pos = end
            } else {
                checksumErrors++
                pos = start + 2
            }
        }
        if (pos > 0) {
            buf.copyInto(buf, 0, pos, len)
            len -= pos
        }
        return frames
    }
}

/** 取り出した 1 フレーム。 */
class Frame(val msg: MsgId, val payload: ByteArray)

/** NAV-PVT の測位結果。 */
data class Pvt(
    val itow: Long,
    val utc: String,
    val timeValid: Boolean,
    val fixRank: Int,
    val numSv: Int,
    val latitude: Double?,
    val longitude: Double?,
    val height: Double?,
    val heightMsl: Double?,
    val hacc: Double?,
    val vacc: Double?,
    val speed: Double?,
    val heading: Double?,
    val pdop: Double,
    val tacc: Long,
    val diffSoln: Boolean = false,
    /** flags3 の lastCorrectionAge（0 は情報なし） */
    val correctionAge: Int = 0,
    /** NAV-PVT の fixType そのもの（0 no fix / 1 DR / 2 2D / 3 3D / 4 GNSS+DR / 5 time only） */
    val fixType: Int = 0,
    /** flags の gnssFixOK */
    val gnssFixOk: Boolean = false,
    /** flags の carrSoln（0 なし / 1 RTK Float / 2 RTK Fixed） */
    val carrSoln: Int = 0,
    /** UTC の UNIX 時刻 [ms]（日付と時刻が有効なときだけ） */
    val utcMillis: Long? = null,
    /** 速度の推定精度 [m/s] */
    val sacc: Double? = null,
    /** 進行方向の推定精度 [°] */
    val headAcc: Double? = null,
) {
    /** 補正情報の経過時間の表示文字列。 */
    val correctionAgeText: String get() = Ubx.CORRECTION_AGES[correctionAge] ?: "—"

    /** 測位状態の表示名。 */
    val fixLabel: String get() = Ubx.FIX_LABELS[fixRank]
}

data class NavSat(
    val gnss: Int, val sv: Int, val cno: Int, val elev: Int?, val azim: Int?, val used: Boolean, val quality: Int,
)

data class RawxMeas(val gnss: Int, val sv: Int, val sig: Int, val cno: Int, val prValid: Boolean, val cpValid: Boolean)

data class MeasxMeas(
    val gnss: Int, val sv: Int, val cno: Int, val mpath: Int,
    val wChips: Int, val fChips: Int, val cPhase: Long, val iPhase: Int, val rmse: Int,
)

data class Sfrbx(val gnss: Int, val sv: Int, val sig: Int, val subframe: Int)

data class MonVer(
    val gen: String, val sw: String, val hw: String, val module: String, val protver: String,
    val extensions: List<String>,
)

class SpanBlock(val spectrum: DoubleArray, val span: Long, val res: Long, val center: Long, val pga: Int)

data class Nav5(val dynmodel: Int, val fixmode: Int, val minelev: Int, val minsvs: Int, val mincno: Int)

data class Rate(val meas: Int, val nav: Int, val timeref: Int)
