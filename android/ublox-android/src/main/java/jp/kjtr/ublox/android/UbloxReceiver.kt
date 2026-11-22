package jp.kjtr.ublox.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import jp.kjtr.ublox.core.GnssFix
import jp.kjtr.ublox.core.Link
import jp.kjtr.ublox.core.LogStatus
import jp.kjtr.ublox.core.MonVer
import jp.kjtr.ublox.core.OutputProfile
import jp.kjtr.ublox.core.Pipeline
import jp.kjtr.ublox.core.Pvt
import jp.kjtr.ublox.core.ReceiverConfig
import jp.kjtr.ublox.core.Ubx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/** [UbloxReceiver.enableRtcmInput] の結果。message は画面にそのまま出せる日本語。 */
data class RtcmInputResult(val ok: Boolean, val message: String)

/** 受信機の接続の状態。 */
sealed interface ReceiverStatus {
    /** つないでいない（利用者が切断した後も） */
    data object Disconnected : ReceiverStatus

    /** USB を開いて MON-VER の応答（世代の判別）を待っている。許可のダイアログを出している間も */
    data class Connecting(val device: String) : ReceiverStatus

    /**
     * つながっている。generation は設定に使う世代（M8 / F9 / X20 / Unknown）、version は MON-VER（応答が無ければ null）、
     * profile は接続時に送った出力の設定（null なら送っていない）。
     */
    data class Connected(
        val device: String,
        val generation: String,
        val version: MonVer?,
        val receiverPort: String,
        val profile: OutputProfile?,
    ) : ReceiverStatus

    /** 開けない・許可されない・通信が切れた（USB が外れたなど）。message は画面にそのまま出せる日本語 */
    data class Error(val message: String) : ReceiverStatus
}

/**
 * 接続の設定。
 *
 * @param profile 接続時（MON-VER の応答の後、無ければ versionTimeoutMs 後）に送る出力の設定。null なら何も送らない
 * @param receiverPort 受信機側のポート（USB / UART1 / UART2）。null なら推定（u-blox 内蔵 USB なら USB、変換チップなら UART1）
 * @param generation 世代を手動で決める（"F9" / "M8" など）。null なら MON-VER から判別
 * @param pipeline 集計・記録に使う Pipeline（ログ欄に出したいなどで自分で作るとき）。null なら新しく作る
 * @param onLog pipeline を渡さないときのログの受け取り先（「HH:mm:ss  本文」の 1 行）
 */
data class ConnectOptions(
    val profile: OutputProfile? = OutputProfile.MONITOR,
    val receiverPort: String? = null,
    val generation: String? = null,
    val pipeline: Pipeline? = null,
    val title: String? = null,
    val onLog: ((String) -> Unit)? = null,
    val versionTimeoutMs: Long = 2000,
)

/**
 * 1 回の接続（USB を開いてから閉じるまで）。[Source] としても使える（u-blox Monitor のデータ源の 1 つ）。
 * stop() はこの接続だけを閉じる（その後に別の接続を始めていれば、そちらは閉じない）。
 */
class UbloxConnection internal constructor(
    private val receiver: UbloxReceiver,
    val device: UsbDevice,
    override val title: String,
    val baud: Int,
    val pipeline: Pipeline,
    val receiverPort: String,
    internal val serial: UsbSerialSource,
    internal val options: ConnectOptions,
) : Source {
    internal val pvtListener: (Pvt) -> Unit = { receiver.onPvt(this, it) }
    internal val rawListener: (ByteArray, Int, Int) -> Unit = { data, off, len -> receiver.onRaw(this, data, off, len) }

    override val canSend = true
    override val isAlive: Boolean get() = serial.isAlive
    override val error: String? get() = serial.error

    /** MON-VER（応答が無ければ null） */
    val version: MonVer? get() = pipeline.version

    /** 設定に使う世代（手動の指定 → MON-VER の判別 → Unknown） */
    val generation: String get() = options.generation ?: pipeline.version?.gen ?: "Unknown"

    /** [UbloxReceiver.connect] で開始済み */
    override fun start() {}

    override fun stop() = receiver.close(this)

    override fun send(packets: List<ByteArray>) = serial.send(packets)
}

/**
 * u-blox 受信機（USB 直結・USB シリアル変換）の入口。ロガーなどからはこれだけを使えばよい。
 *
 * - [devices] で候補を出し、[connect] で開く（USB の許可が無ければダイアログを出してから）。同時に 1 台
 * - [status] で 未接続 / 接続中 / つながった（世代）/ エラー、[fixes] で NAV-PVT ごとの [GnssFix]（Location は toAndroidLocation）
 * - [rawBytes] / [addRawListener] で受信したバイト列（UBX 記録用）、[startRecording] でそのまま .ubx に保存
 * - USB が外れたら自分で閉じて [ReceiverStatus.Error]（「通信が切れました: USB が外れました」）
 *
 * フォアグラウンドサービスには依存しない（画面を閉じても続けるなら使う側のサービスの下で持つ。種別 connectedDevice）。
 * 受信は専用スレッド。fixes・rawBytes はそのスレッドから流れる（collect 側の Dispatcher で受ける）。
 */
class UbloxReceiver(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val app = context.applicationContext
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()

    @Volatile
    var connection: UbloxConnection? = null
        private set
    private var watchJob: Job? = null
    private var detachReceiver: BroadcastReceiver? = null
    /** connect のたびに増やす（許可を待っている間に切断・やり直しされたら古い結果を捨てる） */
    private var attempt = 0

    private val _status = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Disconnected)
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val _fixes = MutableSharedFlow<GnssFix>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** NAV-PVT ごとの測位（位置の無い No Fix も流す。位置だけ欲しければ hasPosition で絞る） */
    val fixes: SharedFlow<GnssFix> = _fixes.asSharedFlow()

    private val _latestFix = MutableStateFlow<GnssFix?>(null)
    /** 最後の測位（表示用。接続し直すと null に戻る） */
    val latestFix: StateFlow<GnssFix?> = _latestFix.asStateFlow()

    private val _rawBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /**
     * 受信したバイト列（読み取りごとのコピー）。購読者がいるときだけ流す。遅い購読者では古いものから捨てるので、
     * 取りこぼしが許されない記録には [startRecording] か [addRawListener] を使う。
     */
    val rawBytes: SharedFlow<ByteArray> = _rawBytes.asSharedFlow()

    private val rawListeners = CopyOnWriteArrayList<(ByteArray, Int, Int) -> Unit>()

    /** つながっていて受信中か */
    val isConnected: Boolean get() = connection?.isAlive == true

    /** 今の接続の Pipeline（集計 GnssState・MON-VER・ACK など。つないでいなければ null） */
    val pipeline: Pipeline? get() = connection?.pipeline

    // ---- 機器と許可 ----

    /** つながっている USB 機器のうちシリアルとして使えるもの（u-blox を先に並べる）。 */
    fun devices(): List<UsbCandidate> = try {
        UsbDevices.list(app)
    } catch (_: Exception) {
        emptyList()
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** USB の使用許可を求める（許可済みならすぐ true）。結果はメインスレッドで返る。 */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) = UsbDevices.requestPermission(app, device, onResult)

    suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { c ->
        requestPermission(device) { granted -> if (c.isActive) c.resume(granted) }
    }

    // ---- 接続 ----

    /**
     * 受信機に接続する（つながっていれば先に閉じる）。許可が無ければダイアログを出し、許されたら開く。
     * onResult は開けたら接続、許可されない・開けなければ null（理由は [status] の Error）。
     */
    fun connect(
        candidate: UsbCandidate,
        baud: Int = Link.DEFAULT_BAUD,
        options: ConnectOptions = ConnectOptions(),
        onResult: (UbloxConnection?) -> Unit = {},
    ) {
        disconnect()
        val title = options.title ?: candidate.label
        val myAttempt = synchronized(lock) { ++attempt }
        _status.value = ReceiverStatus.Connecting(title)
        requestPermission(candidate.device) { granted ->
            if (synchronized(lock) { attempt != myAttempt }) {
                onResult(null)
                return@requestPermission
            }
            if (!granted) {
                _status.value = ReceiverStatus.Error(PERMISSION_DENIED)
                onResult(null)
                return@requestPermission
            }
            val conn = try {
                open(candidate, baud, options, title)
            } catch (e: Exception) {
                _status.value = ReceiverStatus.Error("接続できません: ${e.message ?: e.javaClass.simpleName}")
                null
            }
            onResult(conn)
        }
    }

    private fun open(candidate: UsbCandidate, baud: Int, options: ConnectOptions, title: String): UbloxConnection {
        val port = candidate.driver.ports.firstOrNull() ?: throw IOException("ポートがありません")
        val pipeline = options.pipeline ?: Pipeline(onLog = options.onLog ?: {})
        val serial = UsbSerialSource(usbManager, candidate.device, port, baud, pipeline, title)
        val conn = UbloxConnection(
            this, candidate.device, title, baud, pipeline, options.receiverPort ?: candidate.receiverPortGuess, serial, options,
        )
        pipeline.addPvtListener(conn.pvtListener)
        pipeline.addRawListener(conn.rawListener)
        synchronized(lock) {
            connection = conn
            _latestFix.value = null
            registerDetach(conn)
            serial.start()
            // 世代によって設定の方法が違うので、MON-VER の応答を待ってから出力を設定する
            serial.send(listOf(Ubx.build(Ubx.MON_VER)))
            watchJob = scope.launch { watch(conn) }
        }
        return conn
    }

    /** 世代が分かったら（または待っても分からなければ）出力を設定してつながった状態にし、その後は切断を見張る。 */
    private suspend fun watch(conn: UbloxConnection) {
        val started = SystemClock.elapsedRealtime()
        var ready = false
        while (scope.isActive) {
            delay(WATCH_MS)
            if (connection !== conn) return
            if (!conn.isAlive) {
                lost(conn)
                return
            }
            if (!ready && (conn.version != null || SystemClock.elapsedRealtime() - started >= conn.options.versionTimeoutMs)) {
                ready = true
                conn.options.profile?.let { applyProfile(it, conn.options.generation) }
                _status.value = ReceiverStatus.Connected(conn.title, conn.generation, conn.version, conn.receiverPort, conn.options.profile)
            } else if (ready) {
                // 待ち時間の後に MON-VER が届いたら世代を出し直す
                val s = _status.value
                if (s is ReceiverStatus.Connected && s.version == null && conn.version != null) {
                    _status.value = s.copy(generation = conn.generation, version = conn.version)
                }
            }
        }
    }

    /** 通信が切れた（USB が外れた・読めなくなった）。 */
    private fun lost(conn: UbloxConnection) = synchronized(lock) {
        if (connection !== conn) return
        connection = null
        unregisterDetach()
        detachListeners(conn)
        conn.pipeline.stopLogging()
        _status.value = ReceiverStatus.Error("通信が切れました: ${conn.error ?: "不明"}")
    }

    /** その接続を閉じる（今の接続なら未接続にする）。 */
    internal fun close(conn: UbloxConnection) {
        val current = synchronized(lock) {
            val cur = connection === conn
            if (cur) {
                connection = null
                watchJob?.cancel()
                watchJob = null
                unregisterDetach()
            }
            cur
        }
        conn.serial.stop()
        conn.pipeline.stopLogging()
        detachListeners(conn)
        if (current) _status.value = ReceiverStatus.Disconnected
    }

    /** 切断する（許可を待っている途中なら取りやめる）。 */
    fun disconnect() {
        synchronized(lock) { attempt++ }
        connection?.let { close(it) }
        if (_status.value is ReceiverStatus.Connecting) _status.value = ReceiverStatus.Disconnected
    }

    private fun detachListeners(conn: UbloxConnection) {
        conn.pipeline.removePvtListener(conn.pvtListener)
        conn.pipeline.removeRawListener(conn.rawListener)
    }

    private fun registerDetach(conn: UbloxConnection) {
        unregisterDetach()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                if (device.deviceName == conn.device.deviceName) conn.serial.detached()
            }
        }
        // システムのブロードキャストなので EXPORTED（他のアプリからは送れない保護されたもの）
        ContextCompat.registerReceiver(app, r, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED)
        detachReceiver = r
    }

    private fun unregisterDetach() {
        detachReceiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        detachReceiver = null
    }

    // ---- 送信・出力の設定 ----

    /** 受信機へ送る（送信待ちに積むだけ。書くのは受信スレッド）。つないでいなければ false。 */
    fun send(packet: ByteArray): Boolean = send(listOf(packet))

    fun send(packets: List<ByteArray>): Boolean {
        val conn = connection?.takeIf { it.isAlive } ?: return false
        conn.send(packets)
        return true
    }

    /**
     * 出力の設定を送る（RAM のみ）。generation は null なら接続の世代（手動の指定 → MON-VER → Unknown。Unknown は両方の方式で送る）。
     */
    fun applyProfile(profile: OutputProfile, generation: String? = null): Boolean {
        val conn = connection?.takeIf { it.isAlive } ?: return false
        val gen = generation ?: conn.generation
        if (profile is OutputProfile.Monitor) {
            conn.pipeline.log("出力メッセージを設定します（$gen、受信機側 ${conn.receiverPort}）")
        } else {
            conn.pipeline.log("出力を設定します: ${profile.label}（$gen、受信機側 ${conn.receiverPort}）")
        }
        conn.send(profile.packets(gen, conn.receiverPort))
        return true
    }

    /**
     * 今の接続の受信機側のポートで RTCM3 の入力を受け付けるよう設定する（RAM のみ。電源を切ると戻る。0.3.0）。
     * generation は null なら接続の世代。結果は画面にそのまま出せる日本語の 1 行。
     */
    fun enableRtcmInput(generation: String? = null): RtcmInputResult {
        val conn = connection?.takeIf { it.isAlive } ?: return RtcmInputResult(false, "受信機に接続してから押してください。")
        val gen = generation ?: conn.generation
        val item = ReceiverConfig.rtcmInputItem(conn.receiverPort)
        val packet = ReceiverConfig.rtcmInputPacket(gen, conn.receiverPort)
        if (item == null || packet == null) {
            return RtcmInputResult(false, if (!ReceiverConfig.usesKeys(gen)) "M8 世代の RTCM 入力の設定には対応していません。" else "受信機側のポート（${conn.receiverPort}）が分かりません。")
        }
        conn.send(listOf(packet))
        conn.pipeline.log("${item.label} を有効にしました（RAM）")
        return RtcmInputResult(true, "${item.label} を有効にしました（RAM のみ。電源を切ると戻ります）。")
    }

    // ---- 受信データ ----

    internal fun onPvt(conn: UbloxConnection, pvt: Pvt) {
        if (connection !== conn) return
        val fix = GnssFix.from(pvt, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        _latestFix.value = fix
        _fixes.tryEmit(fix)
    }

    internal fun onRaw(conn: UbloxConnection, data: ByteArray, offset: Int, length: Int) {
        if (connection !== conn) return
        for (l in rawListeners) l(data, offset, length)
        if (_rawBytes.subscriptionCount.value > 0) _rawBytes.tryEmit(data.copyOfRange(offset, offset + length))
    }

    /** 受信したバイト列を受け取る関数を足す（受信スレッドから呼ばれる。data は使い回されるので、取っておくならコピー）。接続をまたいで残る。 */
    fun addRawListener(listener: (ByteArray, Int, Int) -> Unit) {
        rawListeners.add(listener)
    }

    fun removeRawListener(listener: (ByteArray, Int, Int) -> Unit) {
        rawListeners.remove(listener)
    }

    // ---- 記録（UBX のバイト列をそのまま .ubx に） ----

    /** 記録を始める（`<prefix>_年月日_時分秒.ubx`、上書きしない）。つないでいなければ null。失敗は IOException。 */
    @Throws(IOException::class)
    fun startRecording(folder: File, prefix: String = "ublox"): LogStatus? {
        val conn = connection?.takeIf { it.isAlive } ?: return null
        conn.pipeline.startLogging(folder, prefix)
        return conn.pipeline.logStatus()
    }

    fun stopRecording() {
        connection?.pipeline?.stopLogging()
    }

    /** 記録中ならファイルと大きさ */
    fun recordStatus(): LogStatus? = connection?.pipeline?.logStatus()

    companion object {
        const val PERMISSION_DENIED = "USB の使用が許可されませんでした。"
        private const val WATCH_MS = 250L
    }
}
