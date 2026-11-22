package jp.kjtr.ubloxmonitor.domain

import jp.kjtr.ublox.core.TimedPvt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 地図の計算（0.5.0。PC 版にない）。RTK の cm 単位のばらつきを地図で見るための直近の点・ばらつき・精度の文字列と、
 * 地理院（osmdroid）と Google の地図のズームの換算。Android に依存しない（DisplayTest と同じく JVM でテストする）。
 */
object MapMath {
    /** 地図に点で出す直近の測位の数（1 Hz で 2 分） */
    const val RECENT_POINTS = 120

    /** 地球の半径 [m]（ばらつきの計算は狭い範囲なので球で近似） */
    private const val EARTH_R = 6_378_137.0

    /** 測位履歴の後ろから [n] 点の (緯度, 経度)。間引き・丸めはしない（軌跡は 1e-7° ≒ 1 cm に丸めて間引くため）。 */
    fun recentPoints(history: List<TimedPvt>, n: Int = RECENT_POINTS): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(n)
        for (i in history.indices.reversed()) {
            val p = history[i].pvt
            val lat = p.latitude ?: continue
            val lon = p.longitude ?: continue
            out.add(lat to lon)
            if (out.size >= n) break
        }
        out.reverse()
        return out
    }

    /**
     * 点の水平のばらつき（平均の位置からの距離の二乗平均の平方根 = DRMS [m]）と最大の距離 [m]。
     * 2 点未満なら null。
     */
    fun scatter(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.size < 2) return null
        val lat0 = points.sumOf { it.first } / points.size
        val lon0 = points.sumOf { it.second } / points.size
        val k = PI / 180 * EARTH_R
        val c = cos(lat0 * PI / 180)
        var sum = 0.0
        var max = 0.0
        for ((lat, lon) in points) {
            val n = (lat - lat0) * k
            val e = (lon - lon0) * k * c
            val d2 = n * n + e * e
            sum += d2
            if (d2 > max) max = d2
        }
        return sqrt(sum / points.size) to sqrt(max)
    }

    /** 距離の表示。1 m 未満は cm（0.1 cm 単位）、それ以上は m（3 桁）。null は「—」。 */
    fun formatDistance(m: Double?): String = when {
        m == null -> "—"
        m < 1.0 -> "%.1f cm".format(m * 100)
        else -> "%.3f m".format(m)
    }

    /**
     * Google の地図のズーム（タイル 256 dp）→ osmdroid のズーム（isTilesScaledToDpi = false でタイル 256 px）。
     * 同じ縮尺で見せるための換算（[density] = 1 dp の px 数）。
     */
    fun googleToOsmZoom(zoom: Double, density: Float): Double = zoom + ln(density.toDouble()) / ln(2.0)

    fun osmToGoogleZoom(zoom: Double, density: Float): Double = zoom - ln(density.toDouble()) / ln(2.0)
}
