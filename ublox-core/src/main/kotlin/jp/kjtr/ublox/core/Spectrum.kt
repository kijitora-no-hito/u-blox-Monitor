package jp.kjtr.ublox.core

/*
 * スペクトル表示（UBX-MON-SPAN）の集計と目盛り。
 * 移植元: ublox_monitor/spectrum_view.py（SpectrumTrace・nice_ticks・BAND_MARKERS・NO_REPLY_POLLS・状態の文言）。
 */
object Spectrum {
    /** この回数だけ要求しても応答が無ければ「応答がありません」にする */
    const val NO_REPLY_POLLS = 3

    /** 表示する信号の中心周波数 [MHz] */
    val BAND_MARKERS = listOf(
        "GPS/QZSS L1, Galileo E1" to 1575.42, "BeiDou B1I" to 1561.098, "GLONASS G1" to 1602.0,
        "GPS/QZSS L2" to 1227.60, "GLONASS G2" to 1246.0, "GPS/QZSS L5, Galileo E5a" to 1176.45,
        "Galileo E5b, BeiDou B2I" to 1207.14, "BeiDou B3I" to 1268.52,
        "Galileo E6, QZSS L6" to 1278.75,
    )

    /** low〜high に収まる、切りのよい目盛りの値を返す（Python 版 nice_ticks と同じ刻み）。 */
    fun niceTicks(low: Double, high: Double, count: Int = 5): List<Double> {
        val span = maxOf(high - low, 1e-9)
        val raw = span / count
        var power = if (raw >= 1) Math.pow(10.0, raw.toLong().toString().length.toDouble()) / 10 else 1.0
        while (power > raw) power /= 10
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * power }.first { it >= raw }
        // Python の int() は 0 方向への切り捨て
        var first = (low / step).toLong() * step
        if (first < low) first += step
        val ticks = ArrayList<Double>()
        var value = first
        while (value <= high + 1e-9) {
            ticks.add(Math.round(value * 1e6) / 1e6)
            value += step
        }
        return ticks
    }

    /** Python の f"{v:g}" に近い表示（有効 6 桁、末尾の 0 と小数点を落とす）。 */
    fun formatG(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (v == 0.0) return "0"
        return java.math.BigDecimal(v).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()
    }

    /** RF ブロックの見出し（Python 版の title。縦軸の説明も付ける） */
    fun blockTitle(block: SpanBlock): String =
        String.format(
            java.util.Locale.US, "中心 %.3f MHz  幅 %s MHz  分解能 %s kHz  PGA %d dB    縦軸: レベル [dB]", block.center / 1e6, formatG(block.span / 1e6), formatG(block.res / 1e3), block.pga,
        )

    /**
     * 画面の状態の 1 行（Python 版 update_from）。count は MON-SPAN の受信数、connected は要求を送れる u-blox につないでいるか、
     * polls は要求した回数。
     */
    fun statusText(count: Int, connected: Boolean, polls: Int): String = when {
        count > 0 -> "MON-SPAN 受信 $count 回"
        connected && polls >= NO_REPLY_POLLS ->
            "MON-SPAN の応答がありません。この受信機・ファームウェアはスペクトル表示に" +
                "対応していない可能性があります（F9 世代の比較的新しいファームウェアが必要です）。"
        connected -> "MON-SPAN を要求しています。"
        else -> "接続すると、受信機にスペクトルを要求します。記録済みのログに MON-SPAN があれば再生時にも表示します。"
    }
}

/** 1 つの RF ブロックの、現在値・最大値・平均値の集計（Python 版 SpectrumTrace）。 */
class SpectrumTrace {
    var current: DoubleArray? = null
        private set
    var maximum: DoubleArray? = null
        private set
    private var total: DoubleArray? = null
    var count = 0
        private set
    var block: SpanBlock? = null
        private set

    /** 新しいスペクトルを加える。周波数の設定（中心・幅）が変わったら集計をやり直す。 */
    fun add(block: SpanBlock) {
        val prev = this.block
        val same = prev != null && prev.center == block.center && prev.span == block.span
        val values = block.spectrum
        if (!same) {
            maximum = values.copyOf()
            total = DoubleArray(values.size)
            count = 0
        }
        this.block = block
        current = values
        val max = maximum!!
        val tot = total!!
        for (i in values.indices) {
            if (i < max.size && values[i] > max[i]) max[i] = values[i]
            if (i < tot.size) tot[i] += values[i]
        }
        count++
    }

    /** 平均値 */
    val mean: DoubleArray?
        get() {
            val tot = total ?: return null
            if (count == 0) return null
            return DoubleArray(tot.size) { tot[it] / count }
        }

    /** 周波数 [MHz] */
    val frequencies: DoubleArray? get() = block?.let { Ubx.spanFrequencies(it) }
}

/** RF ブロックごとの集計のまとまり（Python 版 SpectrumPanel の _traces・_seen）。 */
class SpectrumSet {
    var traces: List<SpectrumTrace> = emptyList()
        private set
    /** 反映した MON-SPAN の受信数 */
    private var seen = 0

    /** 受信した MON-SPAN を反映する（count が変わったときだけ）。反映したら true。 */
    fun update(blocks: List<SpanBlock>?, count: Int): Boolean {
        if (count < seen) reset()   // データ源が作り直された
        if (blocks.isNullOrEmpty() || count == seen) return false
        seen = count
        if (traces.size != blocks.size) traces = blocks.map { SpectrumTrace() }
        traces.zip(blocks).forEach { (t, b) -> t.add(b) }
        return true
    }

    /** 集計を消す（［クリア］）。 */
    fun clear() {
        traces = emptyList()
    }

    /** データ源が変わったとき。 */
    fun reset() {
        clear()
        seen = 0
    }
}
