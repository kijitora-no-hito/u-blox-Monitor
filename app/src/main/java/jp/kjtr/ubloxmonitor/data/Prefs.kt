package jp.kjtr.ubloxmonitor.data

import android.content.Context
import jp.kjtr.ubloxmonitor.domain.ChartMath
import jp.kjtr.ubloxmonitor.domain.Link

/** 画面の選択の保存（SharedPreferences）。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baud: Int
        get() = sp.getInt("baud", Link.DEFAULT_BAUD).takeIf { it in Link.BAUD_RATES } ?: Link.DEFAULT_BAUD
        set(v) = sp.edit().putInt("baud", v).apply()

    /** 受信機側のポート。null は自動 */
    var receiverPort: String?
        get() = sp.getString("receiver_port", null)
        set(v) = sp.edit().putString("receiver_port", v).apply()

    var autoSetup: Boolean
        get() = sp.getBoolean("auto_setup", true)
        set(v) = sp.edit().putBoolean("auto_setup", v).apply()

    /** 再生速度（0 は最大） */
    var replaySpeed: Double
        get() = sp.getFloat("replay_speed", 1f).toDouble()
        set(v) = sp.edit().putFloat("replay_speed", v.toFloat()).apply()

    /** 地図の種類（MapType.name） */
    var mapType: String
        get() = sp.getString("map_type", "STD") ?: "STD"
        set(v) = sp.edit().putString("map_type", v).apply()

    var mapFollow: Boolean
        get() = sp.getBoolean("map_follow", true)
        set(v) = sp.edit().putBoolean("map_follow", v).apply()

    var mockLocation: Boolean
        get() = sp.getBoolean("mock_location", false)
        set(v) = sp.edit().putBoolean("mock_location", v).apply()

    /** 測位履歴グラフの表示幅（ChartMath.TIME_WINDOWS の番号） */
    var chartWindow: Int
        get() = sp.getInt("chart_window", ChartMath.DEFAULT_WINDOW).coerceIn(0, ChartMath.TIME_WINDOWS.size - 1)
        set(v) = sp.edit().putInt("chart_window", v).apply()
}
