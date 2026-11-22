package jp.kjtr.ubloxmonitor.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import jp.kjtr.ublox.android.ConnectOptions
import jp.kjtr.ublox.android.CorrectionManager
import jp.kjtr.ublox.android.InternalGnssSource
import jp.kjtr.ublox.android.MockLocation
import jp.kjtr.ublox.android.ReceiverStatus
import jp.kjtr.ublox.android.Source
import jp.kjtr.ublox.android.UbloxReceiver
import jp.kjtr.ublox.android.UsbCandidate
import jp.kjtr.ublox.core.CommonCn0
import jp.kjtr.ublox.core.Compare
import jp.kjtr.ublox.core.DiffHistory
import jp.kjtr.ublox.core.LogStatus
import jp.kjtr.ublox.core.MonVer
import jp.kjtr.ublox.core.Ntrip
import jp.kjtr.ublox.core.OutputProfile
import jp.kjtr.ublox.core.Pipeline
import jp.kjtr.ublox.core.PositionDiff
import jp.kjtr.ublox.core.Pvt
import jp.kjtr.ublox.core.ReceiverConfig
import jp.kjtr.ublox.core.ReplayClock
import jp.kjtr.ublox.core.STALE_SECONDS
import jp.kjtr.ublox.core.SideSummary
import jp.kjtr.ublox.core.Snapshot
import jp.kjtr.ublox.core.TimedDiff
import jp.kjtr.ublox.core.Ubx
import jp.kjtr.ubloxmonitor.BuildConfig
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ubloxmonitor.data.source.ReplaySource
import jp.kjtr.ubloxmonitor.service.MonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 画面に出す 1 データ源の状態。 */
class ChannelView(
    val channel: Channel,
    val title: String,
    val snapshot: Snapshot,
    val checksumErrors: Int,
    val version: MonVer?,
    val record: LogStatus?,
    val running: Boolean,
    val canSend: Boolean,
    val progress: Float?,
    /** 接続中の受信機の情報・終了の理由など（Python 版の info_label） */
    val info: String,
)

/** 比較画面の状態。base は比較の基準（u-blox か再生）。 */
class CompareView(
    val base: Channel,
    val baseRunning: Boolean,
    val internalRunning: Boolean,
    val baseSummary: SideSummary?,
    val internalSummary: SideSummary?,
    /** 両方とも新しい位置があるときだけ */
    val diff: PositionDiff?,
    val history: List<TimedDiff>,
    val cn0: CommonCn0?,
)

/**
 * 受信機設定タブの状態。gen は設定に使う世代（手動の選択 → MON-VER の判別 → Unknown）、
 * values は受信機から読めた値（{項目 ID: 値}）、readDone は読み出しの応答がそろった（または時間切れ）か。
 */
class SettingsView(
    val connected: Boolean,
    val gen: String,
    val detected: String?,
    val genChoice: String?,
    val layers: Int,
    val values: Map<String, Int>,
    val status: String,
    val readDone: Boolean,
    val awaitingAck: Boolean,
    /**
     * MON-VER の拡張（GPS;GAL;BDS など）から分かった、受信機が対応しない衛星系とその信号の項目 ID。
     * 拡張に衛星系の行が無い古いファームウェアでは空（値が返ってくるかどうかだけで判断する）。
     */
    val unsupported: Set<String> = emptySet(),
)

/** 擬似ロケーションの状態。 */
enum class MockStatus { OFF, WAITING, ACTIVE, NOT_ALLOWED }

data class MockState(val status: MockStatus, val published: Long = 0)

/**
 * 受信・デコード・集計・記録・擬似ロケーションを持つ（Application スコープ）。
 * 画面は views を 0.5 秒ごとに受け取って描く。接続を保つのは MonitorService。
 * 集計（Pipeline）はデータ源の区分（Channel）ごとに 1 つ持ち、u-blox・内蔵 GNSS・再生を同時に動かせる。
 * u-blox の接続（USB の許可・世代の判別・出力の設定・USB 抜け）は ublox-android の [UbloxReceiver] に任せ、
 * その接続（UbloxConnection）を Channel.UBLOX のデータ源として扱う。
 */
class MonitorRepository(private val app: Context) {
    val prefs = Prefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mock = MockLocation(app)

    /** u-blox 受信機の接続（ライブラリ） */
    val receiver = UbloxReceiver(app, scope)

    private class Runtime(val channel: Channel, val pipeline: Pipeline, val source: Source) {
        @Volatile
        var ended = false
        var endMessage: String? = null
        /** 接続後に受信機設定を読み出す（Python 版 _pending_read）。UbloxReceiver が出力を設定してつながったら読む */
        var pendingRead = false
        /** 受信機側のポート（u-blox のみ） */
        var receiverPort: String = "USB"
        var deviceName: String? = null
    }

    private val runtimes = ConcurrentHashMap<Channel, Runtime>()

    private val _views = MutableStateFlow<Map<Channel, ChannelView>>(emptyMap())
    val views: StateFlow<Map<Channel, ChannelView>> = _views.asStateFlow()

    /** メイン画面に出すデータ源 */
    val selected = MutableStateFlow(Channel.UBLOX)

    private val logLines = ArrayDeque<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _candidates = MutableStateFlow<List<UsbCandidate>>(emptyList())
    val candidates: StateFlow<List<UsbCandidate>> = _candidates.asStateFlow()

    /** USB を差して起動されたときに選んでおく機器 */
    val preferredDevice = MutableStateFlow<String?>(null)

    /** 接続欄の 1 行（未接続・切断しました・エラーなど） */
    private val _usbMessage = MutableStateFlow(app.getString(jp.kjtr.ubloxmonitor.R.string.status_not_connected))
    val usbMessage: StateFlow<String> = _usbMessage.asStateFlow()

    private val _mockState = MutableStateFlow(MockState(if (prefs.mockLocation) MockStatus.WAITING else MockStatus.OFF))
    val mockState: StateFlow<MockState> = _mockState.asStateFlow()

    private val _logFiles = MutableStateFlow<List<File>>(emptyList())
    val logFiles: StateFlow<List<File>> = _logFiles.asStateFlow()

    /** 記録の保存先（アプリ固有の外部ストレージ。権限は要らない） */
    val logFolder: File get() = app.getExternalFilesDir("logs") ?: File(app.filesDir, "logs")

    /** 比較の基準（u-blox か再生） */
    private val _compareBase = MutableStateFlow(
        runCatching { Channel.valueOf(prefs.compareBase) }.getOrNull()?.takeIf { it != Channel.INTERNAL } ?: Channel.UBLOX
    )
    val compareBase: StateFlow<Channel> = _compareBase.asStateFlow()

    private val _compare = MutableStateFlow<CompareView?>(null)
    val compare: StateFlow<CompareView?> = _compare.asStateFlow()

    /** 基準から見た内蔵 GNSS の位置の差の履歴（画面を開いていなくても 1 秒ごとに数える） */
    private val diffHistory = DiffHistory()
    private var lastPair: Pair<Pvt?, Pvt?> = null to null

    private var tick = 0

    // ---- 受信機設定（Python 版 settings_panel と _update_settings） ----
    private var settingsStatus = "接続すると現在値を読み出します。"
    private var awaitingAck = false
    private var acksSeen = 0
    /** 読み出しを始めたときの acks の数と時刻（応答がそろったかを見る） */
    private var readAcksStart = 0
    private var readStartedAt = 0L
    private val _settings = MutableStateFlow(
        SettingsView(false, "Unknown", null, prefs.settingsGen, prefs.settingsLayers, emptyMap(), settingsStatus, false, false)
    )
    val settings: StateFlow<SettingsView> = _settings.asStateFlow()

    /** RTK 補正（受信と受信機への転送） */
    val correction = CorrectionManager(
        app, prefs.correctionStore, scope,
        userAgent = Ntrip.userAgent(BuildConfig.VERSION_NAME),
        forward = ::forwardCorrection,
        roverPvt = { runtimes[Channel.UBLOX]?.takeIf { !it.ended }?.pipeline?.state?.pvt },
        ubloxConnected = { isRunning(Channel.UBLOX) },
        log = { addLog("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  [${app.getString(jp.kjtr.ubloxmonitor.R.string.source_correction)}] $it") },
        onStarted = { MonitorService.start(app) },
    )

    init {
        // 候補の一覧を作り直すだけ（つないでいる機器が外れたときの処理は UbloxReceiver）
        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshDevices()
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // システムのブロードキャストなので EXPORTED（他のアプリからは送れない保護されたもの）
        ContextCompat.registerReceiver(app, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshDevices()
        refreshLogFiles()
        scope.launch {
            while (true) {
                delay(REFRESH_MS)
                tick++
                try {
                    checkSources()
                    updatePending()
                    updateSettings()
                    if (tick % MAIN_TICKS == 0) updateDiffHistory()
                    if (tick % MAIN_TICKS == 0 && _views.subscriptionCount.value > 0) publishViews()
                    if (tick % MAIN_TICKS == 0) correction.publish()
                } catch (e: Exception) {
                    addLog("内部エラー: ${e.message}")
                }
            }
        }
    }

    // ---- ログ ----

    private fun addLog(line: String) = synchronized(logLines) {
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        _logs.value = logLines.toList()
    }

    private fun label(channel: Channel) = app.getString(channel.labelRes)

    private fun newPipeline(channel: Channel, clock: () -> Double = { System.currentTimeMillis() / 1000.0 }): Pipeline {
        val tag = label(channel)
        // 「時刻  本文」の本文の前にデータ源の名前を入れる
        return Pipeline(clock) { line -> addLog(line.replaceFirst("  ", "  [$tag] ")) }
    }

    // ---- データ源 ----

    /** 動いている u-blox / 内蔵 GNSS があるか */
    fun hasLiveSource(): Boolean = runtimes.values.any { it.channel.live && !it.ended && it.source.isAlive }

    /** フォアグラウンドサービスを保つ条件（u-blox・内蔵 GNSS・補正の受信のどれかが動いている） */
    fun keepsService(): Boolean = hasLiveSource() || correction.isRunning()

    fun isRunning(channel: Channel): Boolean = runtimes[channel]?.let { !it.ended && it.source.isAlive } == true

    /** そのデータ源の最新の NAV-PVT（通知用） */
    fun latestPvt(channel: Channel) = runtimes[channel]?.pipeline?.state?.pvt

    fun recordStatus(channel: Channel) = runtimes[channel]?.pipeline?.logStatus()

    /**
     * データ源を差し替えて開始する（区分ごとに 1 つ）。内蔵 GNSS もここから入れる。
     * 開始したら集計を作り直す（Python 版 _start と同じく、前のデータは消える）。
     */
    fun startSource(channel: Channel, pipeline: Pipeline, source: Source): Unit = synchronized(runtimes) {
        stopChannel(channel, null)
        val runtime = Runtime(channel, pipeline, source)
        runtimes[channel] = runtime
        source.start()
        selected.value = channel
        if (channel.live) MonitorService.start(app)
        publishViews()
    }

    private fun stopChannel(channel: Channel, message: String?) {
        val runtime = runtimes[channel] ?: return
        runtime.source.stop()
        runtime.pipeline.stopLogging()
        runtime.pendingRead = false
        if (!runtime.ended) {
            runtime.ended = true
            runtime.endMessage = message
            if (message != null) runtime.pipeline.log(message)
        }
        if (channel == Channel.UBLOX) stopMock()
    }

    fun refreshDevices() {
        _candidates.value = receiver.devices()
    }

    /** USB を差して起動されたとき。 */
    fun onDeviceAttached(device: UsbDevice?) {
        refreshDevices()
        if (device != null) preferredDevice.value = device.deviceName
    }

    /** u-blox 受信機に接続する。receiverPortChoice は null で自動。 */
    fun connect(candidate: UsbCandidate, baud: Int, receiverPortChoice: String?, autoSetup: Boolean) {
        prefs.baud = baud
        prefs.receiverPort = receiverPortChoice
        prefs.autoSetup = autoSetup
        // つないでいれば先に止める（UbloxReceiver.connect も前の接続を閉じる。ここでは「切断しました」を出さない）
        synchronized(runtimes) { if (isRunning(Channel.UBLOX)) stopChannel(Channel.UBLOX, null) }
        val pipeline = newPipeline(Channel.UBLOX)
        pipeline.onPvt = { pvt -> if (mock.isActive) mock.publish(pvt) }
        val options = ConnectOptions(
            // 世代によって設定の方法が違うので、UbloxReceiver が MON-VER の応答を待ってから送る
            profile = if (autoSetup) OutputProfile.MONITOR else null,
            receiverPort = receiverPortChoice,
            generation = prefs.settingsGen,
            pipeline = pipeline,
            title = candidate.label,
        )
        receiver.connect(candidate, baud, options) { conn ->
            if (conn == null) {
                (receiver.status.value as? ReceiverStatus.Error)?.let { _usbMessage.value = it.message }
                return@connect
            }
            startSource(Channel.UBLOX, pipeline, conn)
            runtimes[Channel.UBLOX]?.let {
                it.receiverPort = conn.receiverPort
                it.deviceName = candidate.device.deviceName
                it.pendingRead = true
                readStartedAt = 0L
                awaitingAck = false
                settingsStatus = "接続すると現在値を読み出します。"
            }
            _usbMessage.value = ""
            if (prefs.mockLocation) startMock()
        }
    }

    fun disconnect() {
        if (isRunning(Channel.UBLOX)) {
            stopChannel(Channel.UBLOX, app.getString(jp.kjtr.ubloxmonitor.R.string.status_disconnected))
            _usbMessage.value = app.getString(jp.kjtr.ubloxmonitor.R.string.status_disconnected)
        }
        publishViews()
    }

    /** 通知の「切断」。動いているデータ源をすべて止める。 */
    fun disconnectAll() {
        disconnect()
        if (isRunning(Channel.INTERNAL)) stopChannel(Channel.INTERNAL, app.getString(jp.kjtr.ubloxmonitor.R.string.internal_stopped))
        if (isRunning(Channel.REPLAY)) stopChannel(Channel.REPLAY, app.getString(jp.kjtr.ubloxmonitor.R.string.replay_stopped))
        correction.stop()
        publishViews()
    }

    /** u-blox の受信機側のポート（つないでいなければ null） */
    fun receiverPort(): String? = runtimes[Channel.UBLOX]?.takeIf { !it.ended }?.receiverPort

    /** RTK 補正のシリアルの候補（u-blox につないでいる機器は除く） */
    fun correctionCandidates(all: List<UsbCandidate>): List<UsbCandidate> {
        val ublox = runtimes[Channel.UBLOX]?.takeIf { !it.ended }?.deviceName
        return all.filter { it.key != ublox }
    }

    // ---- 内蔵 GNSS ----

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 内蔵 GNSS を開始する（位置情報の権限が許可されてから呼ぶ）。u-blox とは独立に動く。
     * サービスは startSource の中で起動し、権限があるので location 種別になる。
     */
    fun startInternal() {
        if (!hasLocationPermission()) {
            addLog(app.getString(jp.kjtr.ubloxmonitor.R.string.internal_no_permission))
            return
        }
        val pipeline = newPipeline(Channel.INTERNAL)
        val title = "${app.getString(jp.kjtr.ubloxmonitor.R.string.source_internal)}（${Build.MANUFACTURER} ${Build.MODEL}）"
        startSource(Channel.INTERNAL, pipeline, InternalGnssSource(app, pipeline, title))
    }

    fun stopInternal() {
        if (isRunning(Channel.INTERNAL)) stopChannel(Channel.INTERNAL, app.getString(jp.kjtr.ubloxmonitor.R.string.internal_stopped))
        publishViews()
    }

    // ---- 比較 ----

    /** 比較の基準を選ぶ（u-blox か再生）。差の履歴は消す。 */
    fun setCompareBase(channel: Channel) {
        if (channel == Channel.INTERNAL || channel == _compareBase.value) return
        prefs.compareBase = channel.name
        _compareBase.value = channel
        diffHistory.clear()
        lastPair = null to null
        publishViews()
    }

    /** 基準と内蔵の両方に新しい位置があれば、差を履歴に足す（どちらかが更新されたときだけ）。 */
    private fun updateDiffHistory() {
        val base = runtimes[_compareBase.value]?.pipeline?.state ?: return
        val internal = runtimes[Channel.INTERNAL]?.pipeline?.state ?: return
        val bp = base.pvt ?: return
        val ip = internal.pvt ?: return
        if (base.clock() - base.pvtTime > STALE_SECONDS * 2 || internal.clock() - internal.pvtTime > STALE_SECONDS * 2) return
        if (lastPair.first === bp && lastPair.second === ip) return
        lastPair = bp to ip
        val diff = Compare.diff(bp, ip) ?: return
        diffHistory.add(TimedDiff(System.currentTimeMillis() / 1000.0, diff))
    }

    private fun buildCompare(map: Map<Channel, ChannelView>): CompareView {
        val base = _compareBase.value
        val b = map[base]
        val i = map[Channel.INTERNAL]
        val bs = b?.snapshot?.let { Compare.summary(it) }
        val isum = i?.snapshot?.let { Compare.summary(it) }
        val bp = b?.snapshot?.pvt
        val ip = i?.snapshot?.pvt
        val diff = if (bs?.fresh == true && isum?.fresh == true && bp != null && ip != null) Compare.diff(bp, ip) else null
        val cn0 = if (b != null && i != null) Compare.commonCn0(b.snapshot, i.snapshot) else null
        return CompareView(base, b?.running == true, i?.running == true, bs, isum, diff, diffHistory.toList(), cn0)
    }

    /** 設定に使う受信機の世代。手動で選んでいればそれを、なければ判別結果を使う（Python 版 _generation）。 */
    private fun generation(runtime: Runtime?): String = prefs.settingsGen ?: runtime?.pipeline?.version?.gen ?: "Unknown"

    /** 送信できる u-blox（つながっていなければ null） */
    private fun liveUblox(): Runtime? = runtimes[Channel.UBLOX]?.takeIf { !it.ended && it.source.isAlive && it.source.canSend }

    /** 受信した補正情報を u-blox へ送る（補正の受信スレッドから呼ばれる）。書くのは u-blox の読み取りスレッド。 */
    private fun forwardCorrection(data: ByteArray) {
        liveUblox()?.source?.send(listOf(data))
    }

    /** 表示に使うメッセージの出力を有効にする（Python 版 apply_setup）。 */
    fun applySetup() {
        val runtime = runtimes[Channel.UBLOX] ?: return
        if (runtime.ended || !runtime.source.isAlive) return
        receiver.applyProfile(OutputProfile.MONITOR, generation(runtime))
    }

    /**
     * 接続後、UbloxReceiver が世代を判別して（または 2 秒待っても分からなければ）出力を設定し終えたら、
     * 受信機設定を読み出す（Python 版と同じ順: 出力の設定 → 読み出し）。
     */
    private fun updatePending() {
        val runtime = runtimes[Channel.UBLOX] ?: return
        if (!runtime.pendingRead || runtime.ended || !runtime.source.isAlive) return
        if (receiver.status.value !is ReceiverStatus.Connected || receiver.connection !== runtime.source) return
        runtime.pendingRead = false
        readSettings()
    }

    // ---- 受信機設定 ----

    fun setSettingsGen(gen: String?) {
        prefs.settingsGen = gen
        publishSettings()
    }

    fun setSettingsLayers(layers: Int) {
        prefs.settingsLayers = layers
        publishSettings()
    }

    /** 受信機の現在の設定を読み出す（Python 版 read_settings）。 */
    fun readSettings() {
        val runtime = liveUblox() ?: return
        val gen = generation(runtime)
        runtime.pipeline.cfgValues.clear()
        runtime.pipeline.nav5 = null
        runtime.pipeline.rate = null
        readAcksStart = runtime.pipeline.acks.size
        readStartedAt = System.currentTimeMillis()
        runtime.source.send(ReceiverConfig.readPackets(gen))
        settingsStatus = "現在値を読み出しています。"
        publishSettings()
    }

    /** changes を書き込むパケット（空なら送れる変更がない）。 */
    fun settingsPackets(changes: Map<String, Int>, layers: Int): List<ByteArray> {
        val runtime = liveUblox() ?: return emptyList()
        return ReceiverConfig.writePackets(generation(runtime), changes, layers, runtime.pipeline.nav5, runtime.pipeline.rate)
    }

    /** 確認のあとで設定を送る（Python 版 send_settings の確認より後）。応答が来たら読み直す。 */
    fun sendSettings(changes: Map<String, Int>, layers: Int) {
        val runtime = liveUblox() ?: return
        val packets = settingsPackets(changes, layers)
        if (packets.isEmpty()) {
            settingsStatus = "送信できる変更がありません。"
            publishSettings()
            return
        }
        runtime.pipeline.log("設定を送信: " + ReceiverConfig.changeLines(changes).joinToString("、"))
        acksSeen = runtime.pipeline.acks.size
        awaitingAck = true
        settingsStatus = "送信しました。応答を待っています。"
        runtime.source.send(packets)
        publishSettings()
    }

    fun setSettingsStatus(text: String) {
        settingsStatus = text
        publishSettings()
    }

    /** 受信機設定の現在値と送信結果を見る（Python 版 _update_settings。0.25 秒ごと）。 */
    private fun updateSettings() {
        val runtime = liveUblox()
        if (runtime == null) {
            awaitingAck = false
            if (tick % MAIN_TICKS == 0) publishSettings()
            return
        }
        val values = currentSettingValues(runtime)
        if (!awaitingAck) {
            if (values.isNotEmpty() && "読み出しています" in settingsStatus) settingsStatus = "${values.size} 項目を読み出しました。"
        } else {
            val acks = runtime.pipeline.acks
            val replies = synchronized(acks) { acks.drop(acksSeen).toList() }
                .filter { it.target == Ubx.CFG_VALSET || it.target == Ubx.CFG_NAV5 || it.target == Ubx.CFG_RATE }
                .map { it.ok }
            if (replies.isNotEmpty()) {
                awaitingAck = false
                readSettings()
                settingsStatus = if (replies.all { it }) "受信機が設定を受け付けました。"
                else "受信機が設定を拒否しました（NAK）。値の範囲や対応状況を確認してください。"
            }
        }
        if (tick % MAIN_TICKS == 0) publishSettings()
    }

    private fun currentSettingValues(runtime: Runtime): Map<String, Int> =
        ReceiverConfig.currentValues(
            generation(runtime), runtime.pipeline.cfgValues, runtime.pipeline.nav5, runtime.pipeline.rate, unsupportedItems(runtime),
        )

    /** MON-VER から分かる、対応しない衛星系とその信号。 */
    private fun unsupportedItems(runtime: Runtime): Set<String> =
        ReceiverConfig.unsupportedItems(ReceiverConfig.supportedSystems(runtime.pipeline.version))

    /** 読み出しの応答（設定キーごとの ACK / NAK、M8 は CFG-NAV5 / RATE）がそろったか、5 秒たったか。 */
    private fun readDone(runtime: Runtime): Boolean {
        if (readStartedAt == 0L) return false
        if (System.currentTimeMillis() - readStartedAt > READ_TIMEOUT_MS) return true
        val gen = generation(runtime)
        if (gen == "M8") return runtime.pipeline.nav5 != null && runtime.pipeline.rate != null
        val acks = runtime.pipeline.acks
        val replies = synchronized(acks) { acks.drop(readAcksStart).count { it.target == Ubx.CFG_VALGET } }
        return replies >= ReceiverConfig.ITEMS.size
    }

    private fun publishSettings() {
        val runtime = liveUblox()
        _settings.value = SettingsView(
            connected = runtime != null,
            gen = generation(runtime),
            detected = runtime?.pipeline?.version?.gen,
            genChoice = prefs.settingsGen,
            layers = prefs.settingsLayers,
            values = runtime?.let { currentSettingValues(it) } ?: emptyMap(),
            status = if (runtime == null) app.getString(jp.kjtr.ubloxmonitor.R.string.settings_not_connected) else settingsStatus,
            readDone = runtime?.let { readDone(it) } == true,
            awaitingAck = awaitingAck,
            unsupported = runtime?.let { unsupportedItems(it) } ?: emptySet(),
        )
    }

    /**
     * 接続中のポートで RTCM3 の入力を受け付けるよう、受信機を設定する（RAM のみ。Python 版 enable_rtcm_input）。
     * 結果は RTK 補正タブの 1 行に出す。
     */
    fun enableRtcmInput() {
        val runtime = liveUblox()
        if (runtime == null) {
            correction.setMessage("受信機に接続してから押してください。")
            return
        }
        if (!ReceiverConfig.usesKeys(generation(runtime))) {
            correction.setMessage("M8 世代の RTCM 入力の設定には対応していません。")
            return
        }
        val item = ReceiverConfig.ITEM_BY_IDENT.getValue(ReceiverConfig.RTCM3_INPUT.getValue(runtime.receiverPort))
        runtime.source.send(listOf(Ubx.buildValset(mapOf(item.key to 1L))))
        runtime.pipeline.log("${item.label} を有効にしました（RAM）")
        correction.setMessage("${item.label} を有効にしました（RAM のみ。電源を切ると戻ります）。")
    }

    // ---- スペクトル・u-blox モニター（3 段目。表示中の画面から読む） ----

    /** そのデータ源の Pipeline（MON-SPAN・GnssState の monitorSnapshot / trendSnapshot を読む）。無ければ null */
    fun pipeline(channel: Channel): Pipeline? = runtimes[channel]?.pipeline

    /** MON-SPAN を要求できる（u-blox 実機につながっている）か */
    fun canPollSpectrum(): Boolean = liveUblox() != null

    /** MON-SPAN を要求する（Poll なので受信機の設定は変えない）。送れたら true。 */
    fun pollSpectrum(): Boolean {
        val runtime = liveUblox() ?: return false
        runtime.source.send(listOf(Ubx.build(Ubx.MON_SPAN)))
        return true
    }

    /** データ源の終了や切断を検出する。 */
    private fun checkSources() {
        var changed = false
        for (runtime in runtimes.values) {
            if (runtime.ended || runtime.source.isAlive) continue
            val error = runtime.source.error
            val message = when {
                runtime.channel == Channel.INTERNAL ->
                    app.getString(jp.kjtr.ubloxmonitor.R.string.internal_ended, error ?: "")
                error != null -> "通信が切れました: $error"
                runtime.channel.live -> app.getString(jp.kjtr.ubloxmonitor.R.string.status_disconnected)
                else -> app.getString(jp.kjtr.ubloxmonitor.R.string.replay_finished_name, runtime.source.title)
            }
            runtime.ended = true
            runtime.endMessage = message
            runtime.pipeline.log(message)
            runtime.pipeline.stopLogging()
            if (runtime.channel == Channel.UBLOX) {
                awaitingAck = false
                _usbMessage.value = message
                stopMock()
                refreshDevices()
            }
            if (!runtime.channel.live) refreshLogFiles()
            changed = true
        }
        if (changed) publishViews()
    }

    // ---- 再生 ----

    fun startReplay(file: File, speed: Double) {
        prefs.replaySpeed = speed
        try {
            startReplay(FileInputStream(file), file.length(), file.name, speed)
        } catch (e: IOException) {
            addLog("再生できません: ${e.message}")
        }
    }

    fun startReplay(uri: Uri, speed: Double) {
        prefs.replaySpeed = speed
        try {
            var size = 0L
            var name = uri.lastPathSegment ?: "UBX"
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                }
            }
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("開けません")
            startReplay(input, size, name, speed)
        } catch (e: Exception) {
            addLog("再生できません: ${e.message}")
        }
    }

    private fun startReplay(input: java.io.InputStream, size: Long, name: String, speed: Double) {
        val clock = ReplayClock()
        val pipeline = newPipeline(Channel.REPLAY, clock)
        val source = ReplaySource(pipeline, input, speed, clock, size, name)
        startSource(Channel.REPLAY, pipeline, source)
        // 再生でもログ欄に開始を出す（終了は checkSources で「再生が終わりました」）
        val speedText = if (speed > 0) "%.0f 倍速".format(speed) else "最大"
        pipeline.log(app.getString(jp.kjtr.ubloxmonitor.R.string.replay_started, name, speedText, "%.1f".format(size / 1e6)))
    }

    fun stopReplay() {
        if (isRunning(Channel.REPLAY)) stopChannel(Channel.REPLAY, app.getString(jp.kjtr.ubloxmonitor.R.string.replay_stopped))
        publishViews()
    }

    // ---- 記録 ----

    /** 記録を開始/停止する。動いている実機・内蔵 GNSS だけ。 */
    fun toggleRecord(channel: Channel) {
        val runtime = runtimes[channel] ?: return
        if (runtime.pipeline.logStatus() != null) {
            runtime.pipeline.stopLogging()
        } else if (channel.live && !runtime.ended && runtime.source.isAlive) {
            try {
                runtime.pipeline.startLogging(logFolder, channel.logPrefix)
            } catch (e: IOException) {
                runtime.pipeline.log("記録を開始できません: ${e.message}")
            }
        }
        refreshLogFiles()
        publishViews()
    }

    fun refreshLogFiles() {
        val files = logFolder.listFiles { f -> f.isFile && f.name.endsWith(".ubx", ignoreCase = true) }
        _logFiles.value = files?.sortedByDescending { it.name } ?: emptyList()
    }

    /** 記録中のファイルは消さない。 */
    fun deleteLog(file: File): Boolean {
        if (runtimes.values.any { it.pipeline.logStatus()?.file == file }) return false
        val ok = file.delete()
        refreshLogFiles()
        return ok
    }

    fun isRecordingFile(file: File) = runtimes.values.any { it.pipeline.logStatus()?.file == file }

    // ---- 擬似ロケーション ----

    /** 擬似ロケーションの ON/OFF（流すのは u-blox 実機の位置だけ）。 */
    fun setMock(enabled: Boolean) {
        prefs.mockLocation = enabled
        if (!enabled) {
            mock.disable()
            _mockState.value = MockState(MockStatus.OFF)
            return
        }
        if (isRunning(Channel.UBLOX)) {
            startMock()
        } else {
            // 許可の確認だけしておく（つないでいないのに GPS を差し替えたままにしない）
            try {
                mock.enable()
                mock.disable()
                _mockState.value = MockState(MockStatus.WAITING)
            } catch (_: SecurityException) {
                notAllowed()
            }
        }
    }

    private fun startMock() {
        try {
            mock.enable()
            _mockState.value = MockState(MockStatus.ACTIVE)
        } catch (_: SecurityException) {
            notAllowed()
        }
    }

    private fun notAllowed() {
        mock.disable()
        prefs.mockLocation = false
        _mockState.value = MockState(MockStatus.NOT_ALLOWED)
    }

    private fun stopMock() {
        mock.disable()
        if (_mockState.value.status == MockStatus.ACTIVE) _mockState.value = MockState(MockStatus.WAITING)
    }

    /** サービスの終了時など。 */
    fun releaseMock() = stopMock()

    // ---- 表示 ----

    private fun info(runtime: Runtime): String {
        val running = !runtime.ended && runtime.source.isAlive
        if (!running) return runtime.endMessage ?: ""
        if (!runtime.channel.live) return app.getString(jp.kjtr.ubloxmonitor.R.string.replay_running, runtime.source.title)
        runtime.source.statusText?.let { return "${runtime.source.title}\n$it" }
        val v = runtime.pipeline.version
            ?: return app.getString(jp.kjtr.ubloxmonitor.R.string.connecting_wait, runtime.source.title)
        return "${v.gen} ${v.module}  HW ${v.hw}  SW ${v.sw}  PROTVER ${v.protver}"
    }

    /** 画面用のスナップショットを作り直す。 */
    fun publishViews() {
        val map = LinkedHashMap<Channel, ChannelView>()
        for (channel in Channel.entries) {
            val runtime = runtimes[channel] ?: continue
            val running = !runtime.ended && runtime.source.isAlive
            map[channel] = ChannelView(
                channel = channel,
                title = runtime.source.title,
                snapshot = runtime.pipeline.state.snapshot(),
                checksumErrors = runtime.pipeline.stream.checksumErrors,
                version = runtime.pipeline.version,
                record = runtime.pipeline.logStatus(),
                running = running,
                canSend = running && runtime.source.canSend,
                progress = (runtime.source as? ReplaySource)?.progress,
                info = info(runtime),
            )
        }
        _views.value = map
        _compare.value = buildCompare(map)
        publishSettings()
        val ms = _mockState.value
        if (ms.status == MockStatus.ACTIVE) _mockState.value = ms.copy(published = mock.published)
    }

    companion object {
        const val REFRESH_MS = 250L
        /** 画面は 0.5 秒ごとに更新する */
        const val MAIN_TICKS = 2
        const val MAX_LOG_LINES = 2000
        /** 受信機設定の読み出しの応答を待つ時間 */
        const val READ_TIMEOUT_MS = 5000L
    }
}
