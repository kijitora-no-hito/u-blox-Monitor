package jp.kjtr.ubloxmonitor.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/*
 * u-blox（または再生）とスマホ内蔵 GNSS の比較。Android には依存しない（PC 版には無い画面）。
 */

/** 比較表の片側（1 データ源）の値。 */
data class SideSummary(
    val fixRank: Int?,
    val numSv: Int?,
    /** 受信中（C/N0 > 0）の衛星数 */
    val tracked: Int,
    val hacc: Double?,
    val vacc: Double?,
    /** 0（不明）は null */
    val pdop: Double?,
    val speed: Double?,
    /** 測位に使っている衛星の平均 C/N0 */
    val meanCn0Used: Double?,
    /** NAV-PVT が新しい（STALE_SECONDS × 2 以内）か */
    val fresh: Boolean,
)

/** 基準の位置から見た、もう一方の位置の差。 */
data class PositionDiff(
    val north: Double,
    val east: Double,
    /** 楕円体高の差（もう一方 − 基準）[m] */
    val up: Double?,
    val horizontal: Double,
    /** 基準から見た方位 [deg]（北 0、時計回り） */
    val bearing: Double,
)

/** 時刻付きの位置の差（t は秒）。 */
data class TimedDiff(val t: Double, val diff: PositionDiff)

/** 共通の衛星の C/N0（L1 帯）。rows は (衛星名, 基準の C/N0, 内蔵の C/N0)。 */
data class CommonCn0(val rows: List<Triple<String, Int, Int>>, val onlyBase: Int, val onlyOther: Int)

object Compare {
    /** グラフの表示幅（表示名, 秒） */
    val WINDOWS: List<Pair<String, Double>> = listOf("表示幅 1 分" to 60.0, "表示幅 5 分" to 300.0, "表示幅 15 分" to 900.0)
    const val DEFAULT_WINDOW = 1
    /** 差の履歴の上限（1 Hz で 30 分） */
    const val MAX_HISTORY = 1800

    /** そのデータ源の最新の NAV-PVT が新しいか。 */
    fun isFresh(snap: Snapshot): Boolean {
        val last = snap.pvtHistory.lastOrNull() ?: return false
        return snap.now - last.t <= STALE_SECONDS * 2
    }

    fun summary(snap: Snapshot): SideSummary {
        val pvt = snap.pvt
        val used = snap.satellites.values.filter { it.used && it.cno > 0 }
        return SideSummary(
            fixRank = pvt?.fixRank,
            numSv = pvt?.numSv,
            tracked = snap.satellites.values.count { it.cno > 0 },
            hacc = pvt?.hacc,
            vacc = pvt?.vacc,
            pdop = pvt?.pdop?.takeIf { it > 0.0 },
            speed = pvt?.speed,
            meanCn0Used = if (used.isEmpty()) null else used.sumOf { it.cno }.toDouble() / used.size,
            fresh = isFresh(snap),
        )
    }

    /** base から見た other の位置の差。どちらかに位置が無ければ null。 */
    fun diff(base: Pvt, other: Pvt): PositionDiff? {
        val lat0 = base.latitude ?: return null
        val lon0 = base.longitude ?: return null
        val lat1 = other.latitude ?: return null
        val lon1 = other.longitude ?: return null
        val scale = Math.toRadians(ChartMath.EARTH_RADIUS)
        val north = (lat1 - lat0) * scale
        val east = (lon1 - lon0) * scale * cos(Math.toRadians((lat0 + lat1) / 2))
        val up = if (base.height != null && other.height != null) other.height - base.height else null
        var bearing = Math.toDegrees(atan2(east, north))
        if (bearing < 0) bearing += 360.0
        return PositionDiff(north, east, up, hypot(north, east), bearing)
    }

    /** 衛星ごとの L1 帯の C/N0（RAWX があれば L1 帯の信号、無ければ NAV-SAT の値）。 */
    fun l1Cn0(snap: Snapshot): Map<String, Int> {
        val out = java.util.TreeMap<String, Int>()
        for ((name, sat) in snap.satellites) {
            val cno = if (sat.bands.isEmpty()) sat.cno else sat.bands["L1"] ?: 0
            if (cno > 0) out[name] = cno
        }
        return out
    }

    /** 両方で見えている衛星の C/N0 と、片方だけの衛星の数。 */
    fun commonCn0(base: Snapshot, other: Snapshot): CommonCn0 {
        val a = l1Cn0(base)
        val b = l1Cn0(other)
        val rows = a.keys.filter { it in b }.map { Triple(it, a.getValue(it), b.getValue(it)) }
        return CommonCn0(rows, a.keys.count { it !in b }, b.keys.count { it !in a })
    }

    /** 差の履歴をグラフの系列にする（x は現在からの分、右端が最新）。 */
    fun diffSeries(history: List<TimedDiff>, window: Double): Map<String, List<ChartPoint>> {
        if (history.isEmpty()) return emptyMap()
        val end = history.last().t
        val points = history.filter { it.t >= end - window }
        return linkedMapOf(
            "水平距離" to points.map { ChartPoint((it.t - end) / 60, it.diff.horizontal) },
            "高さの差" to points.mapNotNull { p -> p.diff.up?.let { ChartPoint((p.t - end) / 60, it) } },
        )
    }

    /** 方位を 8 方位の名前にする。 */
    fun bearingName(deg: Double): String {
        val names = listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")
        return names[(((deg + 22.5) % 360) / 45).toInt().coerceIn(0, 7)]
    }
}

/** 位置の差の履歴（スレッド間で共有する）。 */
class DiffHistory(private val max: Int = Compare.MAX_HISTORY) {
    private val items = ArrayDeque<TimedDiff>()

    fun add(item: TimedDiff) = synchronized(items) {
        items.addLast(item)
        while (items.size > max) items.removeFirst()
    }

    fun clear() = synchronized(items) { items.clear() }

    fun toList(): List<TimedDiff> = synchronized(items) { items.toList() }
}
