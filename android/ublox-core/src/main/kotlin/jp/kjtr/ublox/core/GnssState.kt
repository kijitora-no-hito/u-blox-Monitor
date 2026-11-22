package jp.kjtr.ublox.core

/*
 * 受信したメッセージの集計。受信スレッドが更新し、画面はスナップショットを読む。
 * 移植元: ublox_monitor/state.py。
 */

const val STALE_SECONDS = 2.5
const val TREND_SECONDS = 300.0
const val MAX_PVT_HISTORY = 36000

/** u-blox モニター表示の衛星系の並び。max は表示する衛星数の上限。 */
data class MonitorSystem(val row: Int, val max: Int, val id: Int, val color: String)

val MONITOR_SYSTEMS: Map<String, MonitorSystem> = linkedMapOf(
    "GPS" to MonitorSystem(0, 12, 0, "#2ecc71"),
    "GLONASS" to MonitorSystem(0, 12, 6, "#3498db"),
    "GALILEO" to MonitorSystem(0, 12, 2, "#9b59b6"),
    "BEIDOU" to MonitorSystem(1, 24, 3, "#f1c40f"),
    "QZSS" to MonitorSystem(1, 6, 5, "#e67e22"),
    "OTHER" to MonitorSystem(1, 6, -1, "#95a5a6"),
)
private val GROUPS: Map<Int, String> = MONITOR_SYSTEMS.entries.associate { it.value.id to it.key }

/** gnssId を u-blox モニター表示の衛星系名にする。 */
fun monitorGroup(gnssId: Int): String = GROUPS[gnssId] ?: "OTHER"

/** 時刻付きの NAV-PVT。 */
data class TimedPvt(val t: Double, val pvt: Pvt)

/** 信号 1 つの追尾状態（RAWX）。 */
data class SignalObs(val cno: Int, val pr: Boolean, val cp: Boolean, val t: Double)

/** メイン画面に出す衛星 1 機。 */
data class SatView(
    val system: String,
    val cno: Int,
    val elev: Int?,
    val azim: Int?,
    val used: Boolean,
    val bands: Map<String, Int>,
)

/** メイン画面用の集計。 */
class Snapshot(
    val now: Double,
    val pvt: Pvt?,
    val pvtHistory: List<TimedPvt>,
    /** 衛星名の順に並んでいる */
    val satellites: Map<String, SatView>,
    val signals: Map<String, List<Int>>,
    val counts: Map<MsgId, Int>,
)

/** u-blox モニター表示用の衛星 1 機（MEASX / RAWX）。 */
data class TrackEntry(
    val signals: Map<Int, SignalObs>,
    val lastSeen: Double,
    val cno: Int? = null,
    val mpath: Int? = null,
    val rmse: Int? = null,
    val wChips: Int? = null,
    val fChips: Int? = null,
    val cPhase: Long? = null,
    val iPhase: Int? = null,
)

data class SfrbxEntry(val count: Int, val subframes: Set<Int>)

class MonitorSnapshot(
    val now: Double,
    val seen: Map<String, List<Int>>,
    val tracks: Map<String, Map<Int, TrackEntry>>,
    val sfrbx: Map<String, Map<Int, SfrbxEntry>>,
)

/** トレンド表示用の 1 点。signal は RAWX のとき (gnssId, sigId)、MEASX のとき null。 */
data class TrendPoint(
    val t: Double, val cno: Int, val mpath: Int, val rmse: Int, val pr: Int, val cp: Int,
    val signal: Pair<Int, Int>?,
)

class TrendSnapshot(
    val now: Double,
    val seen: Map<String, List<Int>>,
    val history: Map<String, Map<Int, List<TrendPoint>>>,
)

/** 集計結果。clock は現在時刻 [s] を返す関数（再生時はデータの時刻で進める）。 */
class GnssState(val clock: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val lock = Any()

    @Volatile
    var pvt: Pvt? = null
        private set
    /** 最新の NAV-PVT を受けた時刻（clock の値。比較画面の「新しいか」の判定に使う） */
    @Volatile
    var pvtTime: Double = 0.0
        private set
    private val pvtHistory = ArrayDeque<TimedPvt>()
    private val counts = LinkedHashMap<MsgId, Int>()
    private var sats: Map<Pair<Int, Int>, Pair<NavSat, Double>> = emptyMap()
    private val signals = LinkedHashMap<Pair<Int, Int>, MutableMap<Int, SignalObs>>()

    private class MutableTrack(var lastSeen: Double = 0.0) {
        val signals = LinkedHashMap<Int, SignalObs>()
        var cno: Int? = null
        var mpath: Int? = null
        var rmse: Int? = null
        var wChips: Int? = null
        var fChips: Int? = null
        var cPhase: Long? = null
        var iPhase: Int? = null
        fun freeze() = TrackEntry(LinkedHashMap(signals), lastSeen, cno, mpath, rmse, wChips, fChips, cPhase, iPhase)
    }

    private class MutableSfrbx(var count: Int = 0, val subframes: MutableSet<Int> = sortedSetOf())

    private val tracks = MONITOR_SYSTEMS.keys.associateWith { LinkedHashMap<Int, MutableTrack>() }
    private val seen = MONITOR_SYSTEMS.keys.associateWith { ArrayList<Int>() }
    private val sfrbx = MONITOR_SYSTEMS.keys.associateWith { LinkedHashMap<Int, MutableSfrbx>() }
    private val history = MONITOR_SYSTEMS.keys.associateWith { LinkedHashMap<Int, ArrayDeque<TrendPoint>>() }

    /** 受信メッセージ数を数える。 */
    fun count(msg: MsgId) = synchronized(lock) {
        counts[msg] = (counts[msg] ?: 0) + 1
    }

    /** NAV-PVT を反映する。 */
    fun updatePvt(pvt: Pvt) = synchronized(lock) {
        this.pvt = pvt
        pvtTime = clock()
        pvtHistory.addLast(TimedPvt(pvtTime, pvt))
        while (pvtHistory.size > MAX_PVT_HISTORY) pvtHistory.removeFirst()
    }

    /** NAV-SAT を反映する。 */
    fun updateSat(list: List<NavSat>) {
        val now = clock()
        synchronized(lock) {
            sats = list.associate { (it.gnss to it.sv) to (it to now) }
        }
    }

    /** u-blox モニター表示用の衛星のエントリを返す。表示枠が無ければ null。 */
    private fun track(gnssId: Int, svId: Int, now: Double): Pair<String, MutableTrack?> {
        val group = monitorGroup(gnssId)
        val list = seen.getValue(group)
        if (svId !in list) {
            if (list.size >= MONITOR_SYSTEMS.getValue(group).max) return group to null
            list.add(svId)
            list.sort()
        }
        val entry = tracks.getValue(group).getOrPut(svId) { MutableTrack() }
        entry.lastSeen = now
        return group to entry
    }

    /** トレンド表示用の履歴に 1 点追加し、古い点を捨てる。 */
    private fun addHistory(group: String, svId: Int, item: TrendPoint) {
        val h = history.getValue(group).getOrPut(svId) { ArrayDeque() }
        h.addLast(item)
        while (h.isNotEmpty() && h.first().t < item.t - TREND_SECONDS) h.removeFirst()
    }

    /** RXM-RAWX を反映する。 */
    fun updateRawx(meas: List<RawxMeas>) {
        val now = clock()
        synchronized(lock) {
            for (m in meas) {
                val signal = SignalObs(m.cno, m.prValid, m.cpValid, now)
                signals.getOrPut(m.gnss to m.sv) { LinkedHashMap() }[m.sig] = signal
                val (group, entry) = track(m.gnss, m.sv, now)
                if (entry == null) continue
                entry.signals[m.sig] = signal
                addHistory(
                    group, m.sv,
                    TrendPoint(
                        now, m.cno, entry.mpath ?: 0, entry.rmse ?: 0,
                        if (m.prValid) 1 else 0, if (m.cpValid) 1 else 0, m.gnss to m.sig,
                    ),
                )
            }
        }
    }

    /** RXM-MEASX を反映する。 */
    fun updateMeasx(meas: List<MeasxMeas>) {
        val now = clock()
        synchronized(lock) {
            for (m in meas) {
                val (group, entry) = track(m.gnss, m.sv, now)
                if (entry == null) continue
                entry.cno = m.cno
                entry.mpath = m.mpath
                entry.rmse = m.rmse
                entry.wChips = m.wChips
                entry.fChips = m.fChips
                entry.cPhase = m.cPhase
                entry.iPhase = m.iPhase
                addHistory(group, m.sv, TrendPoint(now, m.cno, m.mpath, m.rmse, 1, 1, null))
            }
        }
    }

    /** RXM-SFRBX を反映する。 */
    fun updateSfrbx(frame: Sfrbx) = synchronized(lock) {
        val entry = sfrbx.getValue(monitorGroup(frame.gnss)).getOrPut(frame.sv) { MutableSfrbx() }
        entry.count++
        if (frame.subframe in 1..5) entry.subframes.add(frame.subframe)
    }

    private class SatBuilder(
        val system: String, var cno: Int, val elev: Int?, val azim: Int?, val used: Boolean,
        val bands: MutableMap<String, Int> = LinkedHashMap(),
    )

    /** メイン画面用の集計を返す。 */
    fun snapshot(): Snapshot {
        val now = clock()
        synchronized(lock) {
            val satellites = java.util.TreeMap<String, SatBuilder>()
            for ((key, value) in sats) {
                val (sat, t) = value
                if (now - t > STALE_SECONDS * 2 || (sat.cno == 0 && sat.elev == null)) continue
                satellites[Ubx.satName(key.first, key.second)] =
                    SatBuilder(Ubx.systemName(key.first), sat.cno, sat.elev, sat.azim, sat.used)
            }
            val bySignal = java.util.TreeMap<String, MutableList<Int>>()
            for ((key, sigs) in signals) {
                val (gnssId, svId) = key
                for ((sigId, signal) in sigs) {
                    if (now - signal.t > STALE_SECONDS || signal.cno == 0) continue
                    val name = Ubx.satName(gnssId, svId)
                    val sat = satellites.getOrPut(name) {
                        SatBuilder(Ubx.systemName(gnssId), 0, null, null, false)
                    }
                    val band = Ubx.SIGNAL_BANDS[gnssId to sigId]
                    if (band != null) sat.bands[band] = maxOf(sat.bands[band] ?: 0, signal.cno)
                    sat.cno = maxOf(sat.cno, signal.cno)
                    val label = "${Ubx.systemName(gnssId)} ${Ubx.signalName(gnssId, sigId)}"
                    bySignal.getOrPut(label) { ArrayList() }.add(signal.cno)
                }
            }
            if (bySignal.isEmpty()) {
                for (sat in satellites.values) {
                    if (sat.cno > 0) bySignal.getOrPut(sat.system) { ArrayList() }.add(sat.cno)
                }
            }
            val frozen = LinkedHashMap<String, SatView>()
            for ((name, s) in satellites) frozen[name] = SatView(s.system, s.cno, s.elev, s.azim, s.used, s.bands)
            return Snapshot(now, pvt, ArrayList(pvtHistory), frozen, bySignal, LinkedHashMap(counts))
        }
    }

    /** u-blox モニター表示（信号・SFRBX）用の集計を返す。 */
    fun monitorSnapshot(): MonitorSnapshot = synchronized(lock) {
        MonitorSnapshot(
            now = clock(),
            seen = seen.mapValues { it.value.toList() },
            tracks = tracks.mapValues { (_, t) -> t.mapValues { it.value.freeze() } },
            sfrbx = sfrbx.mapValues { (_, f) -> f.mapValues { SfrbxEntry(it.value.count, it.value.subframes.toSet()) } },
        )
    }

    /** トレンド表示用の履歴を返す。 */
    fun trendSnapshot(): TrendSnapshot = synchronized(lock) {
        TrendSnapshot(
            now = clock(),
            seen = seen.mapValues { it.value.toList() },
            history = history.mapValues { (_, h) -> h.mapValues { it.value.toList() } },
        )
    }
}
