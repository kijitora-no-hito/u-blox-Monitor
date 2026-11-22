package jp.kjtr.ublox.android

import android.content.Context
import android.hardware.usb.UsbManager
import jp.kjtr.ublox.core.CorrectionClient
import jp.kjtr.ublox.core.CorrectionConfig
import jp.kjtr.ublox.core.CorrectionKind
import jp.kjtr.ublox.core.CorrectionLink
import jp.kjtr.ublox.core.CorrectionSnapshot
import jp.kjtr.ublox.core.Mount
import jp.kjtr.ublox.core.Ntrip
import jp.kjtr.ublox.core.Pvt
import jp.kjtr.ublox.core.Rtcm
import jp.kjtr.ublox.core.SocketLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.net.ssl.HttpsURLConnection

/** RTK 補正タブの表示。pvt は u-blox の最新の NAV-PVT（受信機の測位状態・補正経過時間・基線長）。 */
class CorrectionView(
    val running: Boolean,
    val snapshot: CorrectionSnapshot?,
    val pvt: Pvt?,
    val baseline: Double?,
)

/** マウントポイントの一覧（ソーステーブル）の取得状態。 */
data class MountList(val loading: Boolean = false, val mounts: List<Mount> = emptyList())

/**
 * 補正情報（RTCM3）の受信と受信機への転送（Python 版 app.py の toggle_correction など + correction_view.py の保存）。
 * 受信は CorrectionClient のスレッド、受信機への書き込みは u-blox の UsbSerialSource の送信待ちに積むだけ
 * （書くのは u-blox の読み取りスレッドなので、読み取りと競合しない）。内蔵 GNSS には転送しない。
 *
 * 画面を閉じても受信を続けるなら、使う側がフォアグラウンドサービスを持つこと（onStarted で起動する。種別は connectedDevice）。
 *
 * @param store 入力（パスワード以外）の読み書き。パスワードは SecretStore（Keystore の AES/GCM）に［パスワードを保存する］のときだけ
 * @param userAgent NTRIP の User-Agent（例 Ntrip.userAgent(版)）
 * @param forward 受信したバイト列を u-blox へ送る（例 UbloxReceiver.send）
 * @param roverPvt u-blox の最新の NAV-PVT（GGA・基線長）
 * @param onStarted 受信を始めたとき（前面から呼ばれる。フォアグラウンドサービスの起動に使う）
 */
class CorrectionManager(
    context: Context,
    private val store: CorrectionStore,
    private val scope: CoroutineScope,
    private val userAgent: String,
    private val forward: (ByteArray) -> Unit,
    private val roverPvt: () -> Pvt?,
    private val ubloxConnected: () -> Boolean,
    private val log: (String) -> Unit,
    private val onStarted: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val secrets = SecretStore(app)
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    /** タブ内の 1 行（Python 版 message_label） */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _form = MutableStateFlow(loadForm())
    val form: StateFlow<CorrectionForm> = _form.asStateFlow()

    private val _view = MutableStateFlow(CorrectionView(false, null, null, null))
    val view: StateFlow<CorrectionView> = _view.asStateFlow()

    private val _mounts = MutableStateFlow(MountList())
    val mounts: StateFlow<MountList> = _mounts.asStateFlow()

    @Volatile
    private var client: CorrectionClient? = null
    private var endLogged: CorrectionClient? = null
    private var saveJob: Job? = null

    private fun loadForm(): CorrectionForm {
        val f = store.load()
        if (!f.savePassword) {
            secrets.clear(SecretStore.NTRIP_PASSWORD)
            return f
        }
        val password = secrets.load(SecretStore.NTRIP_PASSWORD)
        if (password == null) {
            // 鍵が消えた・別の端末から来たなど。保存値は捨てて入れ直してもらう
            _message.value = "保存したパスワードを読めませんでした。もう一度入力してください。"
        }
        return f.copy(password = password.orEmpty())
    }

    /** 入力を変えたとき。少し待ってから保存する（パスワードは［パスワードを保存する］が ON のときだけ暗号化して保存）。 */
    fun updateForm(f: CorrectionForm) {
        val old = _form.value
        _form.value = f
        if (old.savePassword && !f.savePassword) secrets.clear(SecretStore.NTRIP_PASSWORD)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            save(f)
        }
    }

    private fun save(f: CorrectionForm) {
        store.save(f)
        if (f.savePassword) {
            if (!secrets.save(SecretStore.NTRIP_PASSWORD, f.password)) _message.value = "パスワードを保存できませんでした。"
        } else {
            secrets.clear(SecretStore.NTRIP_PASSWORD)
        }
    }

    /** 転送の ON/OFF は受信中でも変えられる（Python 版と同じ） */
    private val forwarding: Boolean get() = _form.value.forward

    fun isRunning(): Boolean = client?.isAlive == true

    /** 入力から受信元の設定を作る（Python 版 config）。足りない項目があれば IllegalArgumentException。 */
    private fun buildConfig(f: CorrectionForm): CorrectionConfig {
        if (f.kind == CorrectionKind.SERIAL) {
            val device = f.serialDevice ?: throw IllegalArgumentException("USB シリアル機器を選んでください。")
            val label = UsbDevices.list(app).firstOrNull { it.key == device }?.label ?: device
            return CorrectionConfig(CorrectionKind.SERIAL, serialDevice = device, serialLabel = label, serialBaud = f.serialBaud)
        }
        val host = f.host.trim()
        if (host.isEmpty()) throw IllegalArgumentException("ホストを入力してください。")
        val port = f.port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("ポートは 1〜65535 で入力してください。")
        if (f.kind == CorrectionKind.TCP) return CorrectionConfig(CorrectionKind.TCP, host = host, port = port)
        val mount = f.mount.trim()
        if (mount.isEmpty()) throw IllegalArgumentException("マウントポイントを入力してください。")
        val interval = if (!f.gga) 0 else f.ggaInterval.trim().toIntOrNull()?.takeIf { it in 1..600 }
            ?: throw IllegalArgumentException("GGA の間隔は 1〜600 秒で入力してください。")
        return CorrectionConfig(
            CorrectionKind.NTRIP, host = host, port = port, tls = f.tls, version = f.version, mount = mount,
            user = f.user, password = f.password, ggaInterval = interval,
        )
    }

    private fun openLink(config: CorrectionConfig): CorrectionLink = when (config.kind) {
        CorrectionKind.SERIAL -> UsbCorrectionLink(usbManager, config.serialDevice, config.serialBaud)
        // Android の既定のホスト名検証（JSSE の endpointIdentificationAlgorithm に頼らない）
        else -> SocketLink(config.host, config.port, config.kind == CorrectionKind.NTRIP && config.tls) { host, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        }
    }

    /** 補正情報の受信を開始する（利用者の操作から＝前面から呼ぶ）。 */
    fun start() {
        if (isRunning()) return
        val f = _form.value
        val config = try {
            buildConfig(f)
        } catch (e: IllegalArgumentException) {
            _message.value = e.message.orEmpty()
            return
        }
        saveJob?.cancel()
        save(f)
        if (config.kind == CorrectionKind.SERIAL) {
            val device = usbManager.deviceList.values.firstOrNull { it.deviceName == config.serialDevice }
            if (device == null) {
                _message.value = "USB 機器が見つかりません。［更新］を押してください。"
                return
            }
            UsbDevices.requestPermission(app, device) { granted ->
                if (granted) startClient(config) else _message.value = "USB の使用が許可されませんでした。"
            }
        } else {
            startClient(config)
        }
    }

    private fun startClient(config: CorrectionConfig) {
        if (isRunning()) return
        val c = CorrectionClient(
            config, ::openLink,
            onData = { data -> if (forwarding) forward(data) },
            gga = { Rtcm.buildGga(roverPvt()) },
            userAgent = userAgent,
            onEvent = log,
        )
        client = c
        endLogged = null
        c.start()
        log("補正情報の受信を開始: ${config.target}")
        _message.value = if (ubloxConnected()) "" else "受信機が未接続です。接続すると受信機へ転送します。"
        onStarted()
        publish()
    }

    fun stop() {
        val c = client ?: return
        if (!c.isAlive) return
        c.stop()
        endLogged = c
        log("補正情報の受信を停止しました")
        // スレッドの終わりを待たない（ソケットは閉じたので 1 秒以内に終わる）
        scope.launch {
            c.join(3000)
            publish()
        }
        publish()
    }

    /** ソーステーブル（マウントポイントの一覧）を取得する。 */
    fun fetchMounts() {
        val f = _form.value
        val host = f.host.trim()
        if (host.isEmpty()) {
            _message.value = "ホストを入力してください。"
            return
        }
        val port = f.port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        if (port == null) {
            _message.value = "ポートは 1〜65535 で入力してください。"
            return
        }
        if (_mounts.value.loading) return
        _mounts.value = _mounts.value.copy(loading = true)
        _message.value = "マウントポイントの一覧を取得しています。"
        scope.launch(Dispatchers.IO) {
            try {
                val link = SocketLink(host, port, f.tls) { h, s -> HttpsURLConnection.getDefaultHostnameVerifier().verify(h, s) }
                val mounts = Ntrip.fetchSourcetable(
                    link, host, port, f.user, f.password, f.version, userAgent,
                )
                _mounts.value = MountList(false, mounts)
                _message.value = "マウントポイントを ${mounts.size} 件取得しました。"
            } catch (e: Exception) {
                _mounts.value = _mounts.value.copy(loading = false)
                _message.value = "一覧を取得できません: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun setMessage(text: String) {
        _message.value = text
    }

    /** 画面・通知用の状態を作り直す（MonitorRepository の 0.5 秒ごとの更新から呼ぶ）。 */
    fun publish() {
        val c = client
        val pvt = roverPvt()
        val snap = c?.snapshot()
        // 止まったら（認証失敗など再接続しない理由で）ログとタブに出す（Python 版 _check_correction）
        if (c != null && !c.isAlive && endLogged !== c) {
            endLogged = c
            c.error?.let {
                log("補正情報の受信が止まりました: $it")
                _message.value = "受信が止まりました: $it"
            }
        }
        _view.value = CorrectionView(c?.isAlive == true, snap, pvt, Rtcm.baseline(snap?.station, pvt))
    }

    /** 状態の短い文字列（通知・メインタブ）。受信中でなければ null。 */
    fun statusLine(): String? {
        val snap = _view.value.snapshot ?: return null
        if (!_view.value.running) return null
        return when (snap.status) {
            "再接続待ち" -> "再接続待ち（%.0f 秒後）".format(snap.retryIn ?: 0.0)
            "接続中" -> snap.status
            else -> "受信中 %.1f kbps".format(snap.rate * 8 / 1000)
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 500L
    }
}
