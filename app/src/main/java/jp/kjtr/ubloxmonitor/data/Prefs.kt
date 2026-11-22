package jp.kjtr.ubloxmonitor.data

import android.content.Context
import jp.kjtr.ubloxmonitor.domain.ChartMath
import jp.kjtr.ubloxmonitor.domain.Compare
import jp.kjtr.ubloxmonitor.domain.CorrectionKind
import jp.kjtr.ubloxmonitor.domain.Link
import jp.kjtr.ubloxmonitor.domain.Ntrip
import jp.kjtr.ubloxmonitor.domain.ReceiverConfig
import jp.kjtr.ubloxmonitor.domain.Ubx

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

    // ---- RTK 補正（パスワードはここには入れない。保存するときは SecretStore） ----

    /** RTK 補正の入力（パスワード以外）を読む。 */
    fun loadCorrectionForm(): CorrectionForm = CorrectionForm(
        kind = runCatching { CorrectionKind.valueOf(sp.getString("corr_kind", null) ?: "") }.getOrDefault(CorrectionKind.NTRIP),
        host = sp.getString("corr_host", "") ?: "",
        port = sp.getString("corr_port", Ntrip.DEFAULT_PORT.toString()) ?: Ntrip.DEFAULT_PORT.toString(),
        tls = sp.getBoolean("corr_tls", false),
        version = sp.getInt("corr_version", 2).takeIf { it == 1 || it == 2 } ?: 2,
        mount = sp.getString("corr_mount", "") ?: "",
        user = sp.getString("corr_user", "") ?: "",
        savePassword = sp.getBoolean("corr_save_password", false),
        gga = sp.getBoolean("corr_gga", false),
        ggaInterval = sp.getString("corr_gga_interval", "10") ?: "10",
        serialDevice = sp.getString("corr_serial_device", null),
        serialBaud = sp.getInt("corr_serial_baud", 115200).takeIf { it in Link.BAUD_RATES } ?: 115200,
        forward = sp.getBoolean("corr_forward", true),
    )

    /** RTK 補正の入力を保存する（パスワードは保存しない）。 */
    fun saveCorrectionForm(f: CorrectionForm) {
        sp.edit()
            .putString("corr_kind", f.kind.name).putString("corr_host", f.host).putString("corr_port", f.port)
            .putBoolean("corr_tls", f.tls).putInt("corr_version", f.version).putString("corr_mount", f.mount)
            .putString("corr_user", f.user).putBoolean("corr_save_password", f.savePassword)
            .putBoolean("corr_gga", f.gga).putString("corr_gga_interval", f.ggaInterval)
            .putString("corr_serial_device", f.serialDevice).putInt("corr_serial_baud", f.serialBaud)
            .putBoolean("corr_forward", f.forward)
            .apply()
    }
}

/** RTK 補正タブの入力。数値は入力欄の文字列のまま持ち、開始するときに確かめる。 */
data class CorrectionForm(
    val kind: CorrectionKind = CorrectionKind.NTRIP,
    val host: String = "",
    val port: String = Ntrip.DEFAULT_PORT.toString(),
    val tls: Boolean = false,
    val version: Int = 2,
    val mount: String = "",
    val user: String = "",
    val password: String = "",
    val savePassword: Boolean = false,
    val gga: Boolean = false,
    val ggaInterval: String = "10",
    /** USB 機器の deviceName */
    val serialDevice: String? = null,
    val serialBaud: Int = 115200,
    val forward: Boolean = true,
) {
    override fun toString() = "CorrectionForm($kind, $host:$port, mount=$mount, user=$user)"
}
