package jp.kjtr.ublox.core

/*
 * 受信機を「位置を取る部品」として使う側（ロガーなど）向けの値と設定。
 * GnssFix は NAV-PVT を測位 1 回分の値にしたもの、OutputProfile は接続時に受信機へ送る出力の設定。
 * Android には依存しない（Location への変換は ublox-android の GnssFix.toAndroidLocation）。
 */

/**
 * 1 回の測位（NAV-PVT）。位置が無い（No Fix など）ときは latitude / longitude が null。
 *
 * @property fixType NAV-PVT の fixType（0 no fix / 1 DR / 2 2D / 3 3D / 4 GNSS+DR / 5 time only）
 * @property fixRank 測位状態の順位（[Ubx.FIX_LABELS] の番号。RTK Fixed が 8）
 * @property carrSoln 0 なし / 1 RTK Float / 2 RTK Fixed
 * @property height 楕円体高 [m]（Android の Location.altitude と同じ基準）
 * @property heightMsl 標高（平均海面高）[m]
 * @property hAcc 水平の推定精度 [m]（u-blox の値。Android の accuracy の 68% とは定義が少し違う）
 * @property speed 対地速度 [m/s]、[heading] 進行方向 [°]（北から時計回り）
 * @property utcMillis 受信機の UTC（日付と時刻が有効なときだけ）
 * @property receivedAtMillis 受け取ったときの端末の時刻（System.currentTimeMillis）
 * @property receivedElapsedNanos 受け取ったときの単調な時刻（Android では SystemClock.elapsedRealtimeNanos を入れる）
 */
data class GnssFix(
    val fixType: Int,
    val fixRank: Int,
    val gnssFixOk: Boolean,
    val carrSoln: Int,
    val diffSoln: Boolean,
    val numSv: Int,
    val latitude: Double?,
    val longitude: Double?,
    val height: Double?,
    val heightMsl: Double?,
    val hAcc: Double?,
    val vAcc: Double?,
    val speed: Double?,
    val speedAcc: Double?,
    val heading: Double?,
    val headingAcc: Double?,
    val pdop: Double,
    val itow: Long,
    val utcMillis: Long?,
    val correctionAge: Int,
    val receivedAtMillis: Long,
    val receivedElapsedNanos: Long,
) {
    /** 測位状態の表示名（No Fix / 3D / RTK Fixed など） */
    val fixLabel: String get() = Ubx.FIX_LABELS.getOrElse(fixRank) { "?" }

    /** 位置があるか */
    val hasPosition: Boolean get() = latitude != null && longitude != null

    /** 記録に使う時刻。受信機の UTC が有効ならそれ、無ければ受け取った時刻。 */
    val time: Long get() = utcMillis ?: receivedAtMillis

    companion object {
        fun from(
            pvt: Pvt,
            receivedAtMillis: Long = System.currentTimeMillis(),
            receivedElapsedNanos: Long = System.nanoTime(),
        ) = GnssFix(
            fixType = pvt.fixType, fixRank = pvt.fixRank, gnssFixOk = pvt.gnssFixOk, carrSoln = pvt.carrSoln,
            diffSoln = pvt.diffSoln, numSv = pvt.numSv, latitude = pvt.latitude, longitude = pvt.longitude,
            height = pvt.height, heightMsl = pvt.heightMsl, hAcc = pvt.hacc, vAcc = pvt.vacc,
            speed = pvt.speed, speedAcc = pvt.sacc, heading = pvt.heading, headingAcc = pvt.headAcc, pdop = pvt.pdop,
            itow = pvt.itow, utcMillis = pvt.utcMillis, correctionAge = pvt.correctionAge,
            receivedAtMillis = receivedAtMillis, receivedElapsedNanos = receivedElapsedNanos,
        )
    }
}

/**
 * ダイナミクスモデル（F9 / X20 は CFG-NAVSPG-DYNMODEL、M8 は CFG-NAV5 の dynModel）。
 * 値は u-blox の定義のまま。受信機・ファームウェアが対応しない値は NAK になる（その設定だけ通らない）。
 */
enum class DynModel(val value: Int, val label: String) {
    PORTABLE(0, "Portable"),
    STATIONARY(2, "Stationary"),
    PEDESTRIAN(3, "Pedestrian"),
    AUTOMOTIVE(4, "Automotive"),
    SEA(5, "Sea"),
    AIRBORNE_1G(6, "Airborne <1g"),
    AIRBORNE_2G(7, "Airborne <2g"),
    AIRBORNE_4G(8, "Airborne <4g"),
    WRIST(9, "Wrist"),
    BIKE(10, "Bike"),
    LAWN_MOWER(11, "Lawn mower"),
    ESCOOTER(12, "E-scooter"),
    ;

    companion object {
        /** ロガー向けにすすめるもの（自転車・車・徒歩・電動キックボード・汎用） */
        val FOR_LOGGERS = listOf(BIKE, AUTOMOTIVE, PEDESTRIAN, ESCOOTER, PORTABLE)

        fun of(value: Int): DynModel? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 接続時に受信機へ送る出力の設定。どれも RAM だけを変える（電源を切ると元に戻る。BBR / Flash には書かない）。
 * 未対応のキーが 1 つあっても他が通るよう、設定は 1 キー（1 メッセージ）ずつ別のパケットにする。
 */
sealed class OutputProfile {
    /** 受信機へ送るパケット。gen は M8 / F9 / X20 / Unknown、receiverPort は USB / UART1 / UART2。 */
    abstract fun packets(gen: String, receiverPort: String): List<ByteArray>

    /** ログに出す名前 */
    abstract val label: String

    /** u-blox Monitor の表示用（NAV-PVT・NAV-SAT・RXM-RAWX・RXM-SFRBX・RXM-MEASX を毎エポック）。測位レートは変えない。 */
    data object Monitor : OutputProfile() {
        override val label = "モニター（NAV-PVT・NAV-SAT・RAWX・SFRBX・MEASX）"
        override fun packets(gen: String, receiverPort: String) = Link.outputPackets(gen, receiverPort)
    }

    /**
     * ロガー用。NAV-PVT を毎エポック、NAV-SAT を 1 秒に 1 回だけ出し、モニター用の RAWX・SFRBX・MEASX は止める。
     * 測位レート rateHz（1 / 2 / 5 / 10。CFG-RATE-MEAS = 1000 / rateHz ms、CFG-RATE-NAV = 1）と
     * ダイナミクスモデル（null なら変えない）も設定する。
     * 10 Hz では NAV-PVT だけで約 1 KB/秒になるので、UART 経由なら 115200 bps 以上にすること。
     */
    data class Logger(val rateHz: Int = 1, val dynModel: DynModel? = DynModel.AUTOMOTIVE) : OutputProfile() {
        init {
            require(rateHz in RATES) { "rateHz は ${RATES.joinToString("/")} のどれか" }
        }

        override val label get() = "ロガー（NAV-PVT ${rateHz} Hz・NAV-SAT 1 Hz${dynModel?.let { "・${it.label}" } ?: ""}）"

        val measRateMs: Int get() = 1000 / rateHz

        override fun packets(gen: String, receiverPort: String): List<ByteArray> {
            val packets = ArrayList<ByteArray>()
            if (gen in listOf("F9", "X20", "Unknown")) {
                val offset = Link.PORT_OFFSETS.getValue(receiverPort)
                val rates = linkedMapOf(
                    Ubx.NAV_PVT to 1, Ubx.NAV_SAT to rateHz,
                    Ubx.RXM_RAWX to 0, Ubx.RXM_SFRBX to 0, Ubx.RXM_MEASX to 0,
                )
                rates.forEach { (msg, rate) ->
                    packets.add(Ubx.buildValset(mapOf(Link.MSGOUT_KEYS.getValue(msg) + offset to rate.toLong())))
                }
                packets.add(Ubx.buildValset(mapOf(KEY_RATE_MEAS to measRateMs.toLong())))
                packets.add(Ubx.buildValset(mapOf(KEY_RATE_NAV to 1L)))
                dynModel?.let { packets.add(Ubx.buildValset(mapOf(KEY_DYNMODEL to it.value.toLong()))) }
            }
            if (gen in listOf("M8", "Unknown")) {
                packets.add(Ubx.buildCfgMsg(Ubx.NAV_PVT, 1))
                packets.add(Ubx.buildCfgMsg(Ubx.NAV_SAT, rateHz))
                listOf(Ubx.RXM_RAWX, Ubx.RXM_SFRBX, Ubx.RXM_MEASX).forEach { packets.add(Ubx.buildCfgMsg(it, 0)) }
                packets.add(Ubx.buildRate(measRateMs, 1, 1))
                // mask が dynModel だけなので、残りの欄（0）は受信機に無視される
                dynModel?.let { packets.add(Ubx.buildNav5(ByteArray(36), mapOf("dynmodel" to it.value))) }
            }
            return packets
        }
    }

    companion object {
        /** ロガー用の測位レート [Hz]（LOGGER より先に初期化する。Logger の init で使う） */
        val RATES = listOf(1, 2, 5, 10)
        val MONITOR: OutputProfile = Monitor
        /** ロガー用の既定（1 Hz・Automotive） */
        val LOGGER: OutputProfile by lazy { Logger() }
        const val KEY_RATE_MEAS = 0x30210001L
        const val KEY_RATE_NAV = 0x30210002L
        const val KEY_DYNMODEL = 0x20110021L
    }
}
