package jp.kjtr.ubloxmonitor.domain

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/*
 * 画面に出す文字列・表・グラフの系列を作る純ロジック。
 * 移植元: app.py（status_text・signal_rows・satellite_rows・message_text・FIX_COLORS）、
 * charts.py（nice_step・time_unit・pvt_series・SYSTEM_COLORS）、map_view.py（thin・track_points）。
 */
object Display {
    /** 測位状態の順位（Ubx.FIX_LABELS の並び）ごとの色 */
    val FIX_COLORS = listOf(
        0xFF7F8C8D, 0xFFD35400, 0xFF7F8C8D, 0xFFD35400, 0xFF2471A3, 0xFF2471A3, 0xFF16A085,
        0xFFB7950B, 0xFF0B6E3A,
    )

    /** 衛星名の接頭辞ごとの色 */
    val SYSTEM_COLORS = mapOf(
        'G' to 0xFF2980B9, 'R' to 0xFFC0392B, 'E' to 0xFF27AE60, 'C' to 0xFF8E44AD,
        'J' to 0xFFD35400, 'S' to 0xFF7F8C8D, 'N' to 0xFF16A085,
    )
    const val OTHER_SYSTEM_COLOR = 0xFF555555

    fun systemColor(name: String): Long = SYSTEM_COLORS[name.firstOrNull() ?: ' '] ?: OTHER_SYSTEM_COLOR

    fun fixColor(rank: Int): Long = FIX_COLORS.getOrElse(rank) { FIX_COLORS[0] }

    val SIGNAL_COLUMNS = listOf("信号", "衛星数", "平均C/N0")
    val SATELLITE_COLUMNS = listOf("衛星", "方位[°]", "仰角[°]", "使用", "C/N0") + Ubx.BANDS

    /** 数値の表示文字列。値が無ければ「—」。 */
    fun formatValue(value: Double?, digits: Int = 1, unit: String = ""): String =
        if (value == null) "—" else "%.${digits}f%s".format(value, unit)

    /** 測位欄の表示（キー: mode utc lat lon height msl nrsv hacc vacc pdop speed heading）。 */
    fun statusText(pvt: Pvt?): Map<String, String> {
        if (pvt == null) {
            return listOf("mode", "utc", "lat", "lon", "height", "msl", "nrsv", "hacc", "vacc", "pdop", "speed", "heading")
                .associateWith { "—" }
        }
        return mapOf(
            "mode" to pvt.fixLabel,
            "utc" to if (pvt.timeValid) pvt.utc else "${pvt.utc}（未確定）",
            "lat" to formatValue(pvt.latitude, 8, "°"),
            "lon" to formatValue(pvt.longitude, 8, "°"),
            "height" to formatValue(pvt.height, 3, " m"),
            "msl" to formatValue(pvt.heightMsl, 3, " m"),
            "nrsv" to pvt.numSv.toString(),
            "hacc" to formatValue(pvt.hacc, 3, " m"),
            "vacc" to formatValue(pvt.vacc, 3, " m"),
            // 内蔵 GNSS の合成 NAV-PVT は PDOP が分からないので 0 を入れている（「—」にする）
            "pdop" to if (pvt.pdop == 0.0) "—" else "%.2f".format(pvt.pdop),
            "speed" to formatValue(pvt.speed, 2, " m/s"),
            "heading" to formatValue(pvt.heading, 1, "°"),
        )
    }

    /** 補正情報の受信は 2 段目で足す。それまでは Python 版の「停止中」の表示と同じ。 */
    fun correctionText(pvt: Pvt?, running: Boolean = false, kbps: Double = 0.0): String {
        val age = pvt?.correctionAgeText ?: "—"
        if (!running) return if (pvt != null && pvt.correctionAge != 0) "停止中（経過 $age）" else "停止中"
        return "受信中 %.1f kbps（経過 %s）".format(kbps, age)
    }

    /** 受信メッセージの表示の上限（既知のメッセージは上限に関係なく全部出す） */
    const val MAX_MESSAGE_ROWS = 6

    /**
     * 受信メッセージ数の表示文字列を返す。既知のメッセージ（Ubx.MESSAGE_NAMES の並び）を先に全部、
     * 未知のもの（27-00 など）は後ろに多い順で、合わせて MAX_MESSAGE_ROWS 行まで。
     * Python 版は件数順の 6 種類だが、実機では未知のメッセージが上に来て NAV-PVT などが隠れるため変えた。
     */
    fun messageText(counts: Map<MsgId, Int>): String {
        if (counts.isEmpty()) return "—"
        val known = Ubx.MESSAGE_NAMES.keys.filter { it in counts }
        val unknown = counts.entries.filter { it.key !in Ubx.MESSAGE_NAMES }.sortedByDescending { it.value }
            .take(maxOf(0, MAX_MESSAGE_ROWS - known.size)).map { it.key }
        return (known + unknown).joinToString("\n") { "$it: ${counts.getValue(it)}" }
    }

    /** 信号別の追尾数の表の行。 */
    fun signalRows(snap: Snapshot): List<List<String>> =
        snap.signals.entries.sortedBy { it.key }.map { (name, vals) ->
            listOf(name, vals.size.toString(), "%.1f".format(vals.sum().toDouble() / vals.size))
        }

    /** 全衛星の表の行。 */
    fun satelliteRows(snap: Snapshot): List<List<String>> =
        snap.satellites.keys.sorted().map { name ->
            val sat = snap.satellites.getValue(name)
            listOf(
                name, sat.azim?.toString() ?: "—", sat.elev?.toString() ?: "—",
                if (sat.used) "○" else "", if (sat.cno != 0) sat.cno.toString() else "—",
            ) + Ubx.BANDS.map { sat.bands[it]?.toString() ?: "—" }
        }

    /** 記録の表示（ファイル名と MB）。 */
    fun recordText(status: LogStatus?): String =
        if (status == null) "停止中" else "${status.file.name}\n%.1f MB".format(status.size / 1e6)
}

/** グラフの 1 点（x は分、y は値）。 */
data class ChartPoint(val x: Double, val y: Double)

/** 測位履歴グラフの 4 枚分の系列。 */
class PvtSeries(
    val mode: Map<String, List<ChartPoint>>,
    val offset: Map<String, List<ChartPoint>>,
    val accuracy: Map<String, List<ChartPoint>>,
    val nrsv: Map<String, List<ChartPoint>>,
)

object ChartMath {
    const val MAX_CHART_POINTS = 600
    const val EARTH_RADIUS = 6378137.0

    /** 記録の長さ [分] の上限と、そのときの時間軸の単位・1 分あたりの換算係数 */
    private val TIME_UNITS = listOf(Triple(2.0, "秒", 60.0), Triple(120.0, "分", 1.0), Triple(Double.POSITIVE_INFINITY, "時間", 1 / 60.0))

    /** グラフの表示幅（表示名, 秒）。null は全期間 */
    val TIME_WINDOWS: List<Pair<String, Double?>> = listOf(
        "表示幅 1 分" to 60.0, "表示幅 5 分" to 300.0, "表示幅 15 分" to 900.0,
        "表示幅 1 時間" to 3600.0, "全期間" to null,
    )
    const val DEFAULT_WINDOW = 1
    const val X_TICKS = 6
    const val Y_TICKS = 4
    const val CN0_MAX = 60

    /** span を count 分割する程度の、1・2・5 × 10^n の目盛り間隔を返す。 */
    fun niceStep(span: Double, count: Int, integer: Boolean = false): Double {
        val raw = maxOf(span, 1e-9) / count
        val power = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * power }.first { it >= raw * 0.999 }
        return if (integer) maxOf(step, 1.0) else step
    }

    /** 目盛り間隔に見合う小数点以下の桁数を返す。 */
    fun labelDigits(step: Double): Int = maxOf(0, -floor(log10(step) + 1e-9).toInt())

    /** 記録の長さに合う時間軸の（単位, 1 分あたりの換算係数）を返す。 */
    fun timeUnit(spanMinutes: Double): Pair<String, Double> =
        TIME_UNITS.first { spanMinutes < it.first }.let { it.second to it.third }

    /** 値の幅がこれより狭いときは上下に余白を取る（整数の系列 / それ以外） */
    const val MIN_Y_SPAN_INTEGER = 4.0
    const val MIN_Y_SPAN = 0.2

    /**
     * 縦軸を値の範囲に合わせ、目盛りを切りのよい値にする（lower, upper, step）。
     * 値が一定に近いと線が端に張り付くので、幅が狭いときは中央に来るよう上下に余白を取る
     * （例: 使用衛星数が 15 で一定なら 13〜17）。yFloor より下にはしない。
     */
    fun fitY(values: List<Double>, yFloor: Double?, integer: Boolean): Triple<Double, Double, Double> {
        var low = values.min()
        var high = values.max()
        val minSpan = if (integer) MIN_Y_SPAN_INTEGER else MIN_Y_SPAN
        if (high - low < minSpan) {
            val pad = (minSpan - (high - low)) / 2
            low -= pad
            high += pad
        }
        if (yFloor != null && low < yFloor) {
            high = maxOf(high, yFloor + minOf(minSpan, high - low))
            low = yFloor
        }
        val step = niceStep(maxOf(high - low, if (integer) 1.0 else 0.1), Y_TICKS, integer)
        val lower = floor(low / step) * step
        var upper = ceil(high / step) * step
        if (upper - lower < step * 1.5) upper += step
        return Triple(lower, upper, step)
    }

    /** 位置の履歴を平均位置からの東西・南北・高さの差 [m] にする。 */
    private fun offsets(points: List<TimedPvt>, start: Double): Map<String, List<ChartPoint>> {
        val fixes = points.filter { it.pvt.latitude != null }
        if (fixes.isEmpty()) return emptyMap()
        val lat0 = fixes.sumOf { it.pvt.latitude!! } / fixes.size
        val lon0 = fixes.sumOf { it.pvt.longitude!! } / fixes.size
        val hgt0 = fixes.sumOf { it.pvt.height!! } / fixes.size
        val scale = Math.toRadians(EARTH_RADIUS)
        val c = cos(Math.toRadians(lat0))
        return linkedMapOf(
            "東西" to fixes.map { ChartPoint((it.t - start) / 60, (it.pvt.longitude!! - lon0) * scale * c) },
            "南北" to fixes.map { ChartPoint((it.t - start) / 60, (it.pvt.latitude!! - lat0) * scale) },
            "高さ" to fixes.map { ChartPoint((it.t - start) / 60, it.pvt.height!! - hgt0) },
        )
    }

    /** 測位履歴をグラフの系列に分ける。位置は表示範囲の平均からの差を使う。 */
    fun pvtSeries(all: List<TimedPvt>, window: Double? = null): PvtSeries {
        if (all.isEmpty()) return PvtSeries(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        var start = all.first().t
        var points = all
        if (window != null) {
            start = all.last().t
            points = all.filter { it.t >= start - window }
        }
        fun series(field: (Pvt) -> Double?) = points.mapNotNull { p -> field(p.pvt)?.let { ChartPoint((p.t - start) / 60, it) } }
        return PvtSeries(
            mode = mapOf("測位状態" to series { it.fixRank.toDouble() }),
            offset = offsets(points, start),
            accuracy = linkedMapOf("水平" to series { it.hacc }, "垂直" to series { it.vacc }),
            nrsv = mapOf("使用衛星数" to series { it.numSv.toDouble() }),
        )
    }

    /** 点が多いときに間引く（Python 版の points[::step]）。 */
    fun decimate(points: List<ChartPoint>): List<ChartPoint> {
        val step = maxOf(1, points.size / MAX_CHART_POINTS)
        return if (step == 1) points else points.filterIndexed { i, _ -> i % step == 0 }
    }

    const val MAX_TRACK_POINTS = 2000

    /** [(緯度, 経度)] を MAX_TRACK_POINTS 点程度に間引き、末尾は残す。 */
    fun thin(fixes: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (fixes.isEmpty()) return emptyList()
        val step = maxOf(1, fixes.size / MAX_TRACK_POINTS)
        val sampled = fixes.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
        if (sampled.last() != fixes.last()) sampled.add(fixes.last())
        return sampled.map { round7(it.first) to round7(it.second) }
    }

    private fun round7(v: Double) = Math.round(v * 1e7) / 1e7

    /** 測位履歴を [(緯度, 経度)] のリストにする。多すぎるときは間引く。 */
    fun trackPoints(points: List<TimedPvt>): List<Pair<Double, Double>> =
        thin(points.mapNotNull { p -> p.pvt.latitude?.let { it to p.pvt.longitude!! } })
}
