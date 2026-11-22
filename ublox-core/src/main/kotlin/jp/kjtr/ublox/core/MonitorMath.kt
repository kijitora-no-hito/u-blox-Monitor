package jp.kjtr.ublox.core

/*
 * u-blox モニター表示（RAWX / MEASX の信号、SFRBX、5 分間のトレンド）の計算。描くのはアプリ側。
 * 移植元: ublox_monitor/monitor_view.py（system_offset・band_status・trend_points・SLOT・MEASX_FIELDS など）。
 */
object MonitorMath {
    /** 衛星 1 機の枠の間隔（論理座標）。枠そのものは 44 */
    const val SLOT = 48
    const val SLOT_BOX = 44
    /** 信号の図・SFRBX の図の論理サイズ（PC 版） */
    const val SIGNAL_WIDTH = 1840
    const val SIGNAL_HEIGHT = 880
    const val SFRBX_WIDTH = 1840
    const val SFRBX_HEIGHT = 600

    val TREND_METRICS = listOf("C/N0", "MPath", "RMSE", "TrackStatus")
    val MEASX_FIELDS = listOf("wChips", "fChips", "cPhase", "iPhase", "rmse")
    /** RMSE がこれより大きければ赤で出す */
    const val RMSE_WARN = 30

    /** 同じ段で左にある衛星系の幅を足して、衛星系の描画開始位置を返す（Python 版 system_offset）。 */
    fun systemOffset(name: String): Int {
        val names = MONITOR_SYSTEMS.keys.toList()
        val row = MONITOR_SYSTEMS.getValue(name).row
        val previous = names.take(names.indexOf(name)).filter { MONITOR_SYSTEMS.getValue(it).row == row }
        return 20 + previous.sumOf { MONITOR_SYSTEMS.getValue(it).max * SLOT + 35 }
    }

    /** {帯域: (追尾中か, 搬送波位相が有効か)} を返す（Python 版 band_status）。 */
    fun bandStatus(gnssId: Int, signals: Map<Int, SignalObs>, now: Double): Map<String, Pair<Boolean, Boolean>> {
        val status = LinkedHashMap<String, Pair<Boolean, Boolean>>()
        for ((sigId, info) in signals) {
            val band = Ubx.SIGNAL_BANDS[gnssId to sigId] ?: continue
            if (now - info.t >= STALE_SECONDS) continue
            val carrier = status[band]?.second ?: false
            status[band] = true to (carrier || info.cp)
        }
        return status
    }

    /** 棒の高さに使う C/N0（MEASX が無ければ RAWX の信号の最大）。 */
    fun barCno(entry: TrackEntry): Int = entry.cno ?: (entry.signals.values.maxOfOrNull { it.cno } ?: 0)

    /** 棒の下に出す MEASX の値（MEASX_FIELDS の順。無ければ "-"） */
    fun measxValues(entry: TrackEntry): List<String> = listOf(
        entry.wChips, entry.fChips, entry.cPhase, entry.iPhase, entry.rmse,
    ).map { it?.toString() ?: "-" }

    /** 追尾中か（最後に見えてから STALE_SECONDS 以内） */
    fun active(entry: TrackEntry, now: Double): Boolean = now - entry.lastSeen < STALE_SECONDS

    /**
     * 履歴を 4 段（C/N0、マルチパス、RMSE、追尾状態）の折れ線にする（Python 版 trend_points）。
     * 座標は図の幅・高さに対する割合（x 0〜1 は右端が今、y 0〜1 は上から）。band に入らない点・5 分より古い点は除く。
     */
    fun trendLines(items: List<TrendPoint>, band: String, now: Double): List<List<Pair<Double, Double>>> {
        val sub = 0.25
        val lines = List(4) { ArrayList<Pair<Double, Double>>() }
        for (p in items) {
            val b = if (p.signal != null) Ubx.SIGNAL_BANDS[p.signal] else "L1"
            if (b != band) continue
            val x = 1 - (now - p.t) / TREND_SECONDS
            if (x < 0) continue
            lines[0].add(x to (1 - p.cno / 60.0) * sub)
            lines[1].add(x to sub + (1 - p.mpath / 3.0) * sub)
            lines[2].add(x to 2 * sub + (1 - p.rmse / 63.0) * sub)
            lines[3].add(x to 3 * sub + (0.8 - (p.pr * 0.3 + p.cp * 0.4)) * sub)
        }
        return lines
    }

    /** MON-VER の衛星系の項目 ID → u-blox モニターの衛星系名（SBAS・NavIC は OTHER なので対象外） */
    private val GROUP_OF_SYSTEM = mapOf("GPS" to "GPS", "GLO" to "GLONASS", "GAL" to "GALILEO", "BDS" to "BEIDOU", "QZSS" to "QZSS")

    /**
     * MON-VER から受信機が対応しないと分かる u-blox モニターの衛星系名（X20P なら GLONASS）。
     * MON-VER が無い・衛星系の行が無い（古いファームウェア）なら空。OTHER は含めない。
     */
    fun unsupportedGroups(version: MonVer?): Set<String> {
        val supported = ReceiverConfig.supportedSystems(version) ?: return emptySet()
        return GROUP_OF_SYSTEM.filterKeys { it !in supported }.values.toSet()
    }

    /** トレンドの履歴に点がある帯域（L1 / L2 / L5 / L6。MEASX の点は L1） */
    fun bandsWithData(trend: TrendSnapshot): Set<String> {
        val out = LinkedHashSet<String>()
        for (sats in trend.history.values) for (items in sats.values) for (p in items) {
            val band = if (p.signal != null) Ubx.SIGNAL_BANDS[p.signal] else "L1"
            if (band != null && trend.now - p.t <= TREND_SECONDS) out.add(band)
        }
        return out
    }

    /** SFRBX の棒の高さ（論理座標。受信数の 100 ごとに繰り返す） */
    fun sfrbxHeight(entry: SfrbxEntry): Double = minOf(120.0, entry.count % 100 * 1.2)
}
