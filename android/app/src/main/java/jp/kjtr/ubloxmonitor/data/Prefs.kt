package jp.kjtr.ubloxmonitor.data

import android.content.Context
import jp.kjtr.ublox.android.CorrectionStore
import jp.kjtr.ublox.android.SharedPrefsCorrectionStore
import jp.kjtr.ublox.core.ChartMath
import jp.kjtr.ublox.core.Compare
import jp.kjtr.ublox.core.Link
import jp.kjtr.ublox.core.ReceiverConfig
import jp.kjtr.ublox.core.Ubx

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

    /** 地図を表示中か（0.5.0。［地図を表示］を押したら［地図を閉じる］までタブの切り替え・再起動をまたいで表示のまま） */
    var mapShown: Boolean
        get() = sp.getBoolean("map_shown", false)
        set(v) = sp.edit().putBoolean("map_shown", v).apply()

    /** 更新の確認（0.5.0。既定 ON。OFF の間は api.github.com に一切つながない） */
    var updateCheck: Boolean
        get() = sp.getBoolean("update_check", true)
        set(v) = sp.edit().putBoolean("update_check", v).apply()

    var mapFollow: Boolean
        get() = sp.getBoolean("map_follow", true)
        set(v) = sp.edit().putBoolean("map_follow", v).apply()

    var mockLocation: Boolean
        get() = sp.getBoolean("mock_location", false)
        set(v) = sp.edit().putBoolean("mock_location", v).apply()

    /** 比較の基準（Channel.name。UBLOX か REPLAY） */
    var compareBase: String
        get() = sp.getString("compare_base", "UBLOX") ?: "UBLOX"
        set(v) = sp.edit().putString("compare_base", v).apply()

    /** 比較画面の差のグラフの表示幅（Compare.WINDOWS の番号） */
    var compareWindow: Int
        get() = sp.getInt("compare_window", Compare.DEFAULT_WINDOW).coerceIn(0, Compare.WINDOWS.size - 1)
        set(v) = sp.edit().putInt("compare_window", v).apply()

    /** 測位履歴グラフの表示幅（ChartMath.TIME_WINDOWS の番号） */
    var chartWindow: Int
        get() = sp.getInt("chart_window", ChartMath.DEFAULT_WINDOW).coerceIn(0, ChartMath.TIME_WINDOWS.size - 1)
        set(v) = sp.edit().putInt("chart_window", v).apply()

    // ---- 受信機設定 ----

    /** 受信機の世代の手動選択（"F9" / "M8"）。null は自動判別 */
    var settingsGen: String?
        get() = sp.getString("settings_gen", null)?.takeIf { g -> ReceiverConfig.GENERATIONS.any { it.second == g } }
        set(v) = sp.edit().putString("settings_gen", v).apply()

    /** 設定の保存先（既定は RAM のみ） */
    var settingsLayers: Int
        get() = sp.getInt("settings_layers", Ubx.LAYER_RAM).takeIf { l -> ReceiverConfig.LAYERS.any { it.second == l } }
            ?: Ubx.LAYER_RAM
        set(v) = sp.edit().putInt("settings_layers", v).apply()

    /** RTK 補正の入力（パスワード以外。corr_* のキー）。パスワードは SecretStore */
    val correctionStore: CorrectionStore = SharedPrefsCorrectionStore(sp)
}
