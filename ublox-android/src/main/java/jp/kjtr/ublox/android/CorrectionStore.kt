package jp.kjtr.ublox.android

import android.content.SharedPreferences
import jp.kjtr.ublox.core.CorrectionKind
import jp.kjtr.ublox.core.Link
import jp.kjtr.ublox.core.Ntrip

/** RTK 補正の入力。数値は入力欄の文字列のまま持ち、開始するときに確かめる。 */
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

/** RTK 補正の入力（パスワード以外）の保存先。 */
interface CorrectionStore {
    fun load(): CorrectionForm
    fun save(form: CorrectionForm)
}

/** SharedPreferences に corr_* のキーで保存する（パスワードは入れない。保存するときは SecretStore）。 */
class SharedPrefsCorrectionStore(private val sp: SharedPreferences) : CorrectionStore {
    override fun load(): CorrectionForm = CorrectionForm(
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

    override fun save(form: CorrectionForm) {
        val f = form
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
