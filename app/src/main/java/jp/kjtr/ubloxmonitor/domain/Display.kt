package jp.kjtr.ubloxmonitor.domain

import jp.kjtr.ublox.core.LogStatus
import jp.kjtr.ublox.core.MsgId
import jp.kjtr.ublox.core.Pvt
import jp.kjtr.ublox.core.Snapshot
import jp.kjtr.ublox.core.Ubx

/*
 * 画面に出す文字列・表・色を作る（アプリ側の表示）。
 * 移植元: app.py（status_text・signal_rows・satellite_rows・message_text・FIX_COLORS）、charts.py（SYSTEM_COLORS）。
 * グラフの系列の計算は ublox-core の ChartMath。
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

    /** メインタブに出す RTK 補正の状態（Python 版 _correction_text）。kbps は補正の受信レート。 */
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
