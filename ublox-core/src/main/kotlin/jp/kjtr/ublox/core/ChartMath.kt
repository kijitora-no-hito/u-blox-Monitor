package jp.kjtr.ublox.core

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/*
 * グラフの系列・目盛りの計算（純ロジック）。
 * 移植元: charts.py（nice_step・time_unit・pvt_series）、map_view.py（thin・track_points）。
 */

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
