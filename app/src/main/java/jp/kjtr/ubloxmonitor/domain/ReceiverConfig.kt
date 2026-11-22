package jp.kjtr.ubloxmonitor.domain

/*
 * 受信機設定の項目定義と設定パケットの組み立て（画面は 2 段目で作る）。
 * 移植元: ublox_monitor/settings.py の GUI 以外の部分。
 *
 * F9 / X20 は設定キー（CFG-VALGET / VALSET）、M8 は CFG-NAV5 / CFG-RATE で読み書きする。
 * M8 は衛星系・信号の変更に対応しない。
 */

/** 設定項目。legacy は M8 での（メッセージ, フィールド）。 */
data class ConfigItem(
    val ident: String,
    val label: String,
    val key: Long,
    val choices: List<Pair<Int, String>> = emptyList(),
    val minimum: Int = 0,
    val maximum: Int = 1,
    val unit: String = "",
    val signed: Boolean = false,
    val legacy: Pair<String, String>? = null,
)

object ReceiverConfig {
    val GENERATIONS = listOf("自動判別" to null, "F9 / X20" to "F9", "M8" to "M8")
    val LAYERS = listOf(
        "RAM のみ（電源を切ると戻る）" to Ubx.LAYER_RAM,
        "RAM + BBR + Flash（保存する）" to (Ubx.LAYER_RAM or Ubx.LAYER_BBR or Ubx.LAYER_FLASH),
    )

    val NAV_ITEMS = listOf(
        ConfigItem(
            "dynmodel", "ダイナミクスモデル", 0x20110021, legacy = "nav5" to "dynmodel", choices = listOf(
                0 to "Portable", 2 to "Stationary", 3 to "Pedestrian", 4 to "Automotive", 5 to "Sea",
                6 to "Airborne <1g", 7 to "Airborne <2g", 8 to "Airborne <4g", 9 to "Wrist",
                10 to "Bike", 11 to "Lawn mower", 12 to "E-scooter",
            )
        ),
        ConfigItem(
            "fixmode", "測位モード", 0x20110011, legacy = "nav5" to "fixmode",
            choices = listOf(1 to "2D のみ", 2 to "3D のみ", 3 to "自動 2D/3D"),
        ),
        ConfigItem("dgnssmode", "RTK モード", 0x20140011, choices = listOf(2 to "RTK Float まで", 3 to "RTK Fixed")),
        ConfigItem(
            "minelev", "仰角マスク", 0x201100A4, minimum = -90, maximum = 90, unit = "°",
            signed = true, legacy = "nav5" to "minelev",
        ),
        ConfigItem("mincno", "最小 C/N0", 0x201100A3, maximum = 63, unit = "dB-Hz", legacy = "nav5" to "mincno"),
        ConfigItem("minsvs", "最小衛星数", 0x201100A1, maximum = 32, unit = "機", legacy = "nav5" to "minsvs"),
        ConfigItem("measrate", "測定周期", 0x30210001, minimum = 25, maximum = 65535, unit = "ms", legacy = "rate" to "meas"),
        ConfigItem(
            "navrate", "測位の間引き（測定 N 回に 1 回）", 0x30210002, minimum = 1, maximum = 127,
            unit = "回", legacy = "rate" to "nav",
        ),
    )
    val SYSTEM_ITEMS = listOf(
        ConfigItem("GPS", "GPS", 0x1031001F), ConfigItem("SBAS", "SBAS", 0x10310020),
        ConfigItem("GAL", "Galileo", 0x10310021), ConfigItem("BDS", "BeiDou", 0x10310022),
        ConfigItem("QZSS", "QZSS", 0x10310024), ConfigItem("GLO", "GLONASS", 0x10310025),
        ConfigItem("NAVIC", "NavIC", 0x10310026),
    )
    val SIGNAL_ITEMS = listOf(
        ConfigItem("GPS_L1CA", "GPS L1C/A", 0x10310001), ConfigItem("GPS_L2C", "GPS L2C", 0x10310003),
        ConfigItem("GPS_L5", "GPS L5", 0x10310004), ConfigItem("SBAS_L1CA", "SBAS L1C/A", 0x10310005),
        ConfigItem("GAL_E1", "Galileo E1", 0x10310007), ConfigItem("GAL_E5A", "Galileo E5a", 0x10310009),
        ConfigItem("GAL_E5B", "Galileo E5b", 0x1031000A), ConfigItem("GAL_E6", "Galileo E6", 0x1031000B),
        ConfigItem("BDS_B1", "BeiDou B1I", 0x1031000D), ConfigItem("BDS_B2", "BeiDou B2I", 0x1031000E),
        ConfigItem("BDS_B1C", "BeiDou B1C", 0x1031000F), ConfigItem("BDS_B2A", "BeiDou B2a", 0x10310028),
        ConfigItem("BDS_B3", "BeiDou B3I", 0x10310010), ConfigItem("QZSS_L1CA", "QZSS L1C/A", 0x10310012),
        ConfigItem("QZSS_L1S", "QZSS L1S", 0x10310014), ConfigItem("QZSS_L2C", "QZSS L2C", 0x10310015),
        ConfigItem("QZSS_L5", "QZSS L5", 0x10310017), ConfigItem("QZSS_L1CB", "QZSS L1C/B", 0x10310039),
        ConfigItem("GLO_L1", "GLONASS L1", 0x10310018), ConfigItem("GLO_L2", "GLONASS L2", 0x1031001A),
        ConfigItem("NAVIC_L5", "NavIC L5", 0x1031001D),
    )
    val INPUT_ITEMS = listOf(
        ConfigItem("RTCM3_USB", "RTCM3 入力（USB）", 0x10770004),
        ConfigItem("RTCM3_UART1", "RTCM3 入力（UART1）", 0x10730004),
        ConfigItem("RTCM3_UART2", "RTCM3 入力（UART2）", 0x10750004),
    )

    /** 受信機側のポート名 → RTCM3 入力の項目 ID */
    val RTCM3_INPUT = mapOf("USB" to "RTCM3_USB", "UART1" to "RTCM3_UART1", "UART2" to "RTCM3_UART2")
    val ITEMS = NAV_ITEMS + SYSTEM_ITEMS + SIGNAL_ITEMS + INPUT_ITEMS
    val ITEM_BY_IDENT = ITEMS.associateBy { it.ident }
    private val TRACKING_IDENTS = (SYSTEM_ITEMS + SIGNAL_ITEMS).map { it.ident }.toSet()

    /** 設定キーで読み書きする世代か。 */
    fun usesKeys(gen: String) = gen != "M8"

    /**
     * 現在の設定を読み出すパケットを返す。
     * 設定キーは 1 つずつ問い合わせる（未対応のキーが混ざると要求全体が NAK になるため）。
     */
    fun readPackets(gen: String): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        if (gen in listOf("F9", "X20", "Unknown")) ITEMS.forEach { packets.add(Ubx.buildValget(listOf(it.key))) }
        if (gen in listOf("M8", "Unknown")) {
            packets.add(Ubx.build(Ubx.CFG_NAV5))
            packets.add(Ubx.build(Ubx.CFG_RATE))
        }
        return packets
    }

    /** バイト列をリトルエンディアンの整数にする。 */
    private fun fromBytes(raw: ByteArray, signed: Boolean): Int {
        var v = 0L
        for (i in raw.indices.reversed()) v = (v shl 8) or (raw[i].toLong() and 0xFF)
        if (signed && raw.isNotEmpty() && raw.last() < 0) v -= 1L shl (8 * raw.size)
        return v.toInt()
    }

    /** 受信機から読んだ内容を {項目 ID: 整数値} にする。読めていない項目は含めない。 */
    fun currentValues(gen: String, cfgValues: Map<Long, ByteArray>, nav5: ByteArray?, rate: Rate?): Map<String, Int> {
        val values = LinkedHashMap<String, Int>()
        if (usesKeys(gen)) {
            for (item in ITEMS) {
                val raw = cfgValues[item.key] ?: continue
                values[item.ident] = fromBytes(raw, item.signed)
            }
            return values
        }
        val n5 = nav5?.let { Ubx.parseNav5(it) }
        for (item in NAV_ITEMS) {
            val (src, field) = item.legacy ?: continue
            val v = when (src) {
                "nav5" -> n5?.let {
                    when (field) {
                        "dynmodel" -> it.dynmodel; "fixmode" -> it.fixmode; "minelev" -> it.minelev
                        "minsvs" -> it.minsvs; else -> it.mincno
                    }
                }
                else -> rate?.let { if (field == "meas") it.meas else it.nav }
            } ?: continue
            values[item.ident] = v
        }
        return values
    }

    /** 画面の入力 chosen のうち、現在値 current と違うものだけを返す。 */
    fun changed(current: Map<String, Int>, chosen: Map<String, Int>): Map<String, Int> =
        chosen.filter { (ident, value) -> ident in current && current[ident] != value }

    /** changes（{項目 ID: 値}）を書き込むパケットを返す。 */
    fun writePackets(gen: String, changes: Map<String, Int>, layers: Int, nav5: ByteArray?, rate: Rate?): List<ByteArray> {
        if (changes.isEmpty()) return emptyList()
        if (usesKeys(gen)) {
            val values = LinkedHashMap<Long, Long>()
            changes.forEach { (ident, value) -> values[ITEM_BY_IDENT.getValue(ident).key] = value.toLong() }
            val signed = ITEMS.filter { it.signed }.map { it.key }.toSet()
            return listOf(Ubx.buildValset(values, layers, signed))
        }
        val packets = ArrayList<ByteArray>()
        val nav5Changes = LinkedHashMap<String, Int>()
        changes.forEach { (ident, value) ->
            val legacy = ITEM_BY_IDENT.getValue(ident).legacy
            if (legacy?.first == "nav5") nav5Changes[legacy.second] = value
        }
        if (nav5Changes.isNotEmpty() && nav5 != null) packets.add(Ubx.buildNav5(nav5, nav5Changes))
        if (rate != null && ("measrate" in changes || "navrate" in changes)) {
            packets.add(Ubx.buildRate(changes["measrate"] ?: rate.meas, changes["navrate"] ?: rate.nav, rate.timeref))
        }
        return packets
    }

    /** 値の表示文字列を返す。 */
    fun describe(item: ConfigItem, value: Int?): String {
        if (value == null) return "—"
        if (item.choices.isNotEmpty()) return item.choices.firstOrNull { it.first == value }?.second ?: "値 $value"
        if (item in NAV_ITEMS) return "$value ${item.unit}".trim()
        return if (value != 0) "有効" else "無効"
    }

    /** 確認画面に出す変更内容の行を返す。 */
    fun changeLines(changes: Map<String, Int>): List<String> = changes.map { (ident, value) ->
        val item = ITEM_BY_IDENT.getValue(ident)
        "${item.label}: ${describe(item, value)}"
    }

    /** 衛星系・信号の変更を含むか（GNSS 部が再起動して再捕捉になる）。 */
    fun restartsTracking(changes: Map<String, Int>) = changes.keys.any { it in TRACKING_IDENTS }
}
